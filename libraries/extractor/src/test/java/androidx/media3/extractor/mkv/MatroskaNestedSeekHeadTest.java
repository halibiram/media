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
package androidx.media3.extractor.mkv;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for following nested SeekHead elements in {@link MatroskaExtractor}. */
@RunWith(AndroidJUnit4.class)
public final class MatroskaNestedSeekHeadTest {

  private static final long UNSET = C.INDEX_UNSET;

  @Test
  public void firstSeekHeadThatOnlyNamesAnotherSeekHead_isFollowed() {
    long pendingTrailingSeekHead = 2_837_202_547L;
    assertThat(
            MatroskaExtractor.shouldFollowNestedSeekHead(
                /* seekForCuesEnabled= */ true,
                /* cuesContentPosition= */ UNSET,
                pendingTrailingSeekHead,
                /* followedSeekHeadCount= */ 0))
        .isTrue();
  }

  @Test
  public void directCuesPointer_isPreferredOverNestedSeekHead() {
    assertThat(
            MatroskaExtractor.shouldFollowNestedSeekHead(
                /* seekForCuesEnabled= */ true,
                /* cuesContentPosition= */ 2_837_048_772L,
                /* pendingSeekHeadPosition= */ 2_837_202_547L,
                /* followedSeekHeadCount= */ 0))
        .isFalse();
  }

  @Test
  public void missingCuesAndMissingTrailingSeekHead_isNotFollowed() {
    assertThat(
            MatroskaExtractor.shouldFollowNestedSeekHead(
                /* seekForCuesEnabled= */ true,
                /* cuesContentPosition= */ UNSET,
                /* pendingSeekHeadPosition= */ UNSET,
                /* followedSeekHeadCount= */ 0))
        .isFalse();
  }

  @Test
  public void nestedSeekHeadHop_isCapped() {
    assertThat(
            MatroskaExtractor.shouldFollowNestedSeekHead(
                /* seekForCuesEnabled= */ true,
                /* cuesContentPosition= */ UNSET,
                /* pendingSeekHeadPosition= */ 100L,
                /* followedSeekHeadCount= */ 4))
        .isFalse();
  }

  @Test
  public void nextNestedSeekHeadPosition_recordsTrailingAndIgnoresEarlierSeekHead() {
    long firstSeekHead = 52L;
    long cluster = 5_843L;
    long trailingSeekHead = 2_837_202_547L;

    long afterFirst =
        MatroskaExtractor.nextNestedSeekHeadPosition(trailingSeekHead, UNSET, UNSET, UNSET);
    assertThat(afterFirst).isEqualTo(trailingSeekHead);

    long afterTrailingPointsBack =
        MatroskaExtractor.nextNestedSeekHeadPosition(
            firstSeekHead, UNSET, trailingSeekHead, cluster);
    assertThat(afterTrailingPointsBack).isEqualTo(trailingSeekHead);
  }

  @Test
  public void extractor_nestedSeekHeadToTrailingCues_buildsSeekableSeekMap() throws Exception {
    byte[] sampleData = new byte[] {0x01, 0x02, 0x03, 0x04};
    byte[] mkv = buildMkvWithNestedSeekHead(sampleData);

    MatroskaExtractor extractor =
        new MatroskaExtractor(
            SubtitleParser.Factory.UNSUPPORTED, MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(mkv).build();
    PositionHolder positionHolder = new PositionHolder();
    try {
      while (true) {
        int result = extractor.read(input, positionHolder);
        if (result == Extractor.RESULT_END_OF_INPUT) {
          break;
        }
        if (result == Extractor.RESULT_SEEK) {
          input.setPosition((int) positionHolder.position);
        }
      }
    } finally {
      extractor.release();
    }

    assertThat(output.seekMap).isNotNull();
    assertThat(output.seekMap.isSeekable()).isTrue();
    FakeTrackOutput trackOutput = output.trackOutputs.get(1);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleData(0)).isEqualTo(sampleData);
  }

  private static byte[] buildMkvWithNestedSeekHead(byte[] samplePayload) {
    byte[] ebmlHeader =
        ebmlElement(
            0x1A45DFA3,
            concat(
                ebmlUint(0x42F7, 1),
                ebmlString(0x4282, "matroska"),
                ebmlUint(0x4285, 2)));

    byte[] info =
        ebmlElement(
            0x1549A966,
            concat(
                ebmlUint(0x2AD7B1, 1_000_000),
                ebmlFloat64(0x4489, 5000.0))); // Duration = 5000 ms

    byte[] trackEntry =
        ebmlElement(
            0xAE,
            concat(
                ebmlUint(0xD7, 1),
                ebmlUint(0x83, 1),
                ebmlString(0x86, "V_VP8")));
    byte[] tracks = ebmlElement(0x1654AE6B, trackEntry);

    byte[] simpleBlock =
        ebmlElement(0xA3, concat(new byte[] {(byte) 0x81, 0x00, 0x00, (byte) 0x80}, samplePayload));
    byte[] cluster = ebmlElement(0x1F43B675, concat(ebmlUint(0xE7, 0), simpleBlock));

    // Fixed-width 2-byte SeekPosition so SeekHead sizes don't shift when offsets are filled in.
    byte[] placeholderFirstSeekHead = buildSeekHeadEntry(0x114D9B74, 0x0100);
    int clusterRelPos = placeholderFirstSeekHead.length + info.length + tracks.length;
    int trailingSeekHeadRelPos = clusterRelPos + cluster.length;

    byte[] firstSeekHead = buildSeekHeadEntry(0x114D9B74, trailingSeekHeadRelPos);
    assertThat(firstSeekHead.length).isEqualTo(placeholderFirstSeekHead.length);

    byte[] placeholderTrailingSeekHead = buildSeekHeadEntry(0x1C53BB6B, 0x0100);
    int cuesRelPos = trailingSeekHeadRelPos + placeholderTrailingSeekHead.length;
    byte[] trailingSeekHead = buildSeekHeadEntry(0x1C53BB6B, cuesRelPos);
    assertThat(trailingSeekHead.length).isEqualTo(placeholderTrailingSeekHead.length);

    byte[] cueTrackPositions = ebmlElement(0xB7, ebmlUint(0xF1, clusterRelPos));
    byte[] cuePoint = ebmlElement(0xBB, concat(ebmlUint(0xB3, 0), cueTrackPositions));
    byte[] cues = ebmlElement(0x1C53BB6B, cuePoint);

    byte[] segmentPayload = concat(firstSeekHead, info, tracks, cluster, trailingSeekHead, cues);
    byte[] segment = ebmlElement(0x18538067, segmentPayload);
    return concat(ebmlHeader, segment);
  }

  private static byte[] buildSeekHeadEntry(int targetId, int relativePosition) {
    byte[] seekIdBytes =
        new byte[] {
          (byte) ((targetId >>> 24) & 0xFF),
          (byte) ((targetId >>> 16) & 0xFF),
          (byte) ((targetId >>> 8) & 0xFF),
          (byte) (targetId & 0xFF)
        };
    byte[] seekPosBytes =
        new byte[] {(byte) ((relativePosition >>> 8) & 0xFF), (byte) (relativePosition & 0xFF)};
    byte[] seek =
        ebmlElement(
            0x4DBB, concat(ebmlElement(0x53AB, seekIdBytes), ebmlElement(0x53AC, seekPosBytes)));
    return ebmlElement(0x114D9B74, seek);
  }

  private static byte[] ebmlFloat64(int id, double value) {
    return ebmlElement(id, ByteBuffer.allocate(8).putDouble(value).array());
  }

  private static byte[] ebmlElement(int id, byte[] payload) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writeId(out, id);
    writeSize(out, payload.length);
    out.write(payload, 0, payload.length);
    return out.toByteArray();
  }

  private static byte[] ebmlUint(int id, long value) {
    ByteArrayOutputStream valueBytes = new ByteArrayOutputStream();
    if (value == 0) {
      valueBytes.write(0);
    } else {
      int shift = 56;
      while (shift > 0 && ((value >>> shift) & 0xFF) == 0) {
        shift -= 8;
      }
      while (shift >= 0) {
        valueBytes.write((int) ((value >>> shift) & 0xFF));
        shift -= 8;
      }
    }
    return ebmlElement(id, valueBytes.toByteArray());
  }

  private static byte[] ebmlString(int id, String value) {
    return ebmlElement(id, value.getBytes(StandardCharsets.US_ASCII));
  }

  private static void writeId(ByteArrayOutputStream out, int id) {
    if (id > 0xFFFFFF) {
      out.write((id >>> 24) & 0xFF);
    }
    if (id > 0xFFFF) {
      out.write((id >>> 16) & 0xFF);
    }
    if (id > 0xFF) {
      out.write((id >>> 8) & 0xFF);
    }
    out.write(id & 0xFF);
  }

  private static void writeSize(ByteArrayOutputStream out, int size) {
    if (size < 0x7F) {
      out.write(0x80 | size);
    } else if (size < 0x3FFF) {
      out.write(0x40 | (size >>> 8));
      out.write(size & 0xFF);
    } else {
      out.write(0x20 | (size >>> 16));
      out.write((size >>> 8) & 0xFF);
      out.write(size & 0xFF);
    }
  }

  private static byte[] concat(byte[]... arrays) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] array : arrays) {
      out.write(array, 0, array.length);
    }
    return out.toByteArray();
  }
}
