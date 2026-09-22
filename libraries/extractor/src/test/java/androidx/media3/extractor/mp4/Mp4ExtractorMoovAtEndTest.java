/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.mp4;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Extracts an MP4 whose {@code moov} atom is after a multi-gigabyte {@code mdat}.
 *
 * <p>The file header and the trailing {@code moov} are local assets. The media body is not stored;
 * a read that falls in that gap is served locally and counted. A scan of the body exceeds {@link
 * SparseFile#MAX_MDAT_BYTES} and fails.
 */
@RunWith(AndroidJUnit4.class)
public final class Mp4ExtractorMoovAtEndTest {

  private static final String HEAD_ASSET = "media/mp4/moov_at_end_head.bin";
  private static final String MOOV_ASSET = "media/mp4/moov_at_end_moov.bin";
  private static final long TWO_GIGABYTES = 2L * 1024 * 1024 * 1024;

  @Test
  public void moovAfterLargeMdat_isIndexedWithoutReadingMediaData() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    byte[] header = TestUtil.getByteArray(context, HEAD_ASSET);
    long moovOffset = moovOffsetFromHeader(header);
    byte[] moov = TestUtil.getByteArray(context, MOOV_ASSET);
    long totalLength = moovOffset + moov.length;
    assertThat(totalLength).isGreaterThan(TWO_GIGABYTES);
    assertWithMessage("moov should sit after the large mdat, not in the faststart position")
        .that(moovOffset)
        .isGreaterThan(TWO_GIGABYTES);
    assertThat(moovOffset).isLessThan(totalLength);
    assertThat(fourcc(moov, 4)).isEqualTo("moov");
    assertThat(readUnsignedInt(moov, 0)).isEqualTo(moov.length);

    SparseFile file = new SparseFile(totalLength, header, moovOffset, moov);
    Mp4Extractor extractor = new Mp4Extractor();
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);

    ExtractorInput sniffInput = file.openInput(/* position= */ 0);
    assertThat(extractor.sniff(sniffInput)).isTrue();
    sniffInput.resetPeekPosition();

    List<Long> seekPositions = new ArrayList<>();
    ExtractorInput input = file.openInput(/* position= */ 0);
    PositionHolder seekPosition = new PositionHolder();
    long firstSamplePosition = C.INDEX_UNSET;
    int iterations = 0;
    while (firstSamplePosition == C.INDEX_UNSET) {
      assertWithMessage("extractor did not finish text prefetch and reach the first sample")
          .that(iterations++)
          .isLessThan(10000);
      int result = extractor.read(input, seekPosition);
      boolean prefetchDone =
          output.seekMap != null && !extractor.isOutOfBandTextPrefetchPending();
      if (result == Extractor.RESULT_SEEK) {
        seekPositions.add(seekPosition.position);
        if (prefetchDone && seekPosition.position < 64 * 1024L) {
          firstSamplePosition = seekPosition.position;
          break;
        }
        input = file.openInput(seekPosition.position);
      } else {
        assertWithMessage("read result").that(result).isEqualTo(Extractor.RESULT_CONTINUE);
        if (prefetchDone && input.getPosition() < 64 * 1024L) {
          firstSamplePosition = input.getPosition();
        }
      }
    }

    assertWithMessage("expected a seek from the leading mdat to the trailing moov")
        .that(seekPositions)
        .contains(moovOffset);
    assertThat(output.tracksEnded).isTrue();
    assertThat(output.seekMap).isNotNull();
    assertThat(output.seekMap.isSeekable()).isTrue();
    // mvhd is 6543.120s. The video track's tkhd is 1ms shorter, and that is the reported duration.
    assertThat(output.seekMap.getDurationUs()).isEqualTo(6_543_119_000L);
    assertThat(firstSamplePosition).isAtLeast(0L);
    assertThat(firstSamplePosition).isLessThan(64 * 1024L);
    assertThat(extractor.isOutOfBandTextPrefetchPending()).isFalse();
    assertThat(extractor.outOfBandTextSampleCountForTests()).isGreaterThan(5_000);
    assertWithMessage(
            "text prefetch fetched "
                + file.mdatBytesFetched
                + " mdat bytes; a scan of the "
                + totalLength
                + " byte file would be much larger")
        .that(file.mdatBytesFetched)
        .isLessThan(SparseFile.MAX_MDAT_BYTES);

    Format video = null;
    boolean foundEac3 = false;
    for (int i = 0; i < output.numberOfTracks; i++) {
      FakeTrackOutput trackOutput = output.trackOutputs.valueAt(i);
      Format format = trackOutput.lastFormat;
      if (format == null || format.sampleMimeType == null) {
        continue;
      }
      if (MimeTypes.isVideo(format.sampleMimeType)) {
        video = format;
      } else if (MimeTypes.AUDIO_E_AC3.equals(format.sampleMimeType)
          || MimeTypes.AUDIO_E_AC3_JOC.equals(format.sampleMimeType)) {
        foundEac3 = true;
      }
    }
    assertThat(video).isNotNull();
    // hev1 sample entry also carries dvvC (profile 8, HDR10 compatible), so the mime is dolby-vision.
    assertThat(video.sampleMimeType).isEqualTo(MimeTypes.VIDEO_DOLBY_VISION);
    assertThat(video.width).isEqualTo(3840);
    assertThat(video.height).isEqualTo(2160);
    assertThat(foundEac3).isTrue();

    // The opening is interleaved: the first 12MB stays near the start of the file.
    long position = firstSamplePosition;
    long payloadBytes = 0;
    long openingBytes = 12L * 1024 * 1024;
    long[] offsetAndSize = new long[2];
    while (payloadBytes < openingBytes) {
      assertThat(extractor.consumeNextSampleForTests(position, offsetAndSize)).isTrue();
      assertWithMessage(
              "next sample is at "
                  + offsetAndSize[0]
                  + ", away from the interleaved audio and video at the start of the file")
          .that(offsetAndSize[0])
          .isLessThan(64L * 1024 * 1024);
      position = offsetAndSize[0] + offsetAndSize[1];
      payloadBytes += offsetAndSize[1];
    }

    // Past the opening, tx3g cues live gigabytes away from the picture. They were read while the
    // cursor was still at the end of the file, so playback must not seek out to them.
    int largeReloads = 0;
    int samplesRead = 0;
    long playbackBytes = 400L * 1024 * 1024;
    while (payloadBytes < playbackBytes) {
      assertWithMessage("sample walk did not advance").that(samplesRead++).isLessThan(2_000_000);
      assertThat(extractor.consumeNextSampleForTests(position, offsetAndSize)).isTrue();
      long sampleOffset = offsetAndSize[0];
      assertWithMessage("playback seek to " + sampleOffset + " while reading audio and video")
          .that(sampleOffset)
          .isLessThan(1_000_000_000L);
      long gap = sampleOffset - position;
      if ((gap < 0 || gap >= 256 * 1024L) && Math.abs(gap) >= 1024L * 1024L) {
        largeReloads++;
      }
      position = sampleOffset + offsetAndSize[1];
      payloadBytes += offsetAndSize[1];
    }
    assertThat(largeReloads).isEqualTo(0);
  }

  /** Byte offset of the atom that follows the leading {@code ftyp}+{@code mdat} pair. */
  private static long moovOffsetFromHeader(byte[] header) {
    long ftypSize = readUnsignedInt(header, 0);
    assertThat(fourcc(header, 4)).isEqualTo("ftyp");
    int mdatHeader = (int) ftypSize;
    long mdatSize = readUnsignedInt(header, mdatHeader);
    assertThat(fourcc(header, mdatHeader + 4)).isEqualTo("mdat");
    if (mdatSize == 1) {
      mdatSize = readUnsignedLong(header, mdatHeader + 8);
    }
    return ftypSize + mdatSize;
  }

  private static long readUnsignedInt(byte[] data, int offset) {
    return ((data[offset] & 0xFFL) << 24)
        | ((data[offset + 1] & 0xFFL) << 16)
        | ((data[offset + 2] & 0xFFL) << 8)
        | (data[offset + 3] & 0xFFL);
  }

  private static long readUnsignedLong(byte[] data, int offset) {
    return (readUnsignedInt(data, offset) << 32) | readUnsignedInt(data, offset + 4);
  }

  private static String fourcc(byte[] data, int offset) {
    return new String(data, offset, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
  }

  /**
   * A file view that serves a leading prefix and the trailing {@code moov}. Reads in the {@code
   * mdat} gap are filled locally. A read that would scan the body exceeds {@link #MAX_MDAT_BYTES}
   * and fails.
   */
  private static final class SparseFile {
    static final long MAX_MDAT_BYTES = 3L * 1024 * 1024;

    final long length;
    final byte[] head;
    final long tailOffset;
    final byte[] tail;
    final List<Segment> segments;
    long bytesServed;
    long mdatBytesFetched;

    SparseFile(long length, byte[] head, long tailOffset, byte[] tail) {
      this.length = length;
      this.head = head;
      this.tailOffset = tailOffset;
      this.tail = tail;
      segments = new ArrayList<>();
    }

    ExtractorInput openInput(long position) {
      return new DefaultExtractorInput(new Cursor(position), position, length);
    }

    int readAt(long position, byte[] buffer, int offset, int readLength) throws IOException {
      if (position >= length) {
        return C.RESULT_END_OF_INPUT;
      }
      int fromMapped = copyMapped(position, buffer, offset, readLength);
      if (fromMapped >= 0) {
        return fromMapped;
      }
      fetchMdat(position, readLength);
      int afterFetch = copyMapped(position, buffer, offset, readLength);
      if (afterFetch < 0) {
        throw new IOException("Fetched mdat range at " + position + " but it was not readable.");
      }
      return afterFetch;
    }

    /** @return bytes copied, or {@code -1} when {@code position} is not in a populated range. */
    private int copyMapped(long position, byte[] buffer, int offset, int readLength) {
      if (position < head.length) {
        int available = (int) Math.min(readLength, head.length - position);
        System.arraycopy(head, (int) position, buffer, offset, available);
        return available;
      }
      long tailEnd = tailOffset + tail.length;
      if (position >= tailOffset && position < tailEnd) {
        int tailIndex = (int) (position - tailOffset);
        int available = (int) Math.min(readLength, tail.length - tailIndex);
        available = (int) Math.min(available, length - position);
        System.arraycopy(tail, tailIndex, buffer, offset, available);
        return available;
      }
      for (int i = 0; i < segments.size(); i++) {
        Segment segment = segments.get(i);
        long segmentEnd = segment.offset + segment.data.length;
        if (position >= segment.offset && position < segmentEnd) {
          int segmentIndex = (int) (position - segment.offset);
          int available = (int) Math.min(readLength, segment.data.length - segmentIndex);
          available = (int) Math.min(available, length - position);
          System.arraycopy(segment.data, segmentIndex, buffer, offset, available);
          return available;
        }
      }
      return -1;
    }

    private void fetchMdat(long position, int minLength) throws IOException {
      int window = Math.max(minLength, 64 * 1024);
      if (position >= length) {
        return;
      }
      if (position + window > length) {
        window = (int) (length - position);
      }
      if (window <= 0) {
        return;
      }
      if (mdatBytesFetched + window > MAX_MDAT_BYTES) {
        throw new IOException(
            String.format(
                Locale.US,
                "mdat read budget exceeded at %d (already fetched %d).",
                position,
                mdatBytesFetched));
      }
      segments.add(new Segment(position, new byte[window]));
      mdatBytesFetched += window;
    }

    private static final class Segment {
      final long offset;
      final byte[] data;

      Segment(long offset, byte[] data) {
        this.offset = offset;
        this.data = data;
      }
    }

    private final class Cursor implements DataReader {
      private long position;

      Cursor(long position) {
        this.position = position;
      }

      @Override
      public int read(byte[] buffer, int offset, int readLength) throws IOException {
        int read = readAt(position, buffer, offset, readLength);
        if (read > 0) {
          position += read;
          bytesServed += read;
        }
        return read;
      }
    }
  }
}
