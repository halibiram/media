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

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.extractor.DtsUtil;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for DTS-HD MA / DTS:X detection and non-blocking startup in {@link MatroskaExtractor}. */
@RunWith(AndroidJUnit4.class)
public final class MatroskaDtsDetectionTest {

  @Test
  public void getDtsAudioMimeType_coreOnly_returnsAudioDts() {
    byte[] coreFrame = buildDtsCoreFrame(/* coreFrameSize= */ 16, /* appendExtss= */ false);
    assertThat(DtsUtil.getDtsAudioMimeType(coreFrame)).isEqualTo(MimeTypes.AUDIO_DTS);
  }

  @Test
  public void getDtsAudioMimeType_coreFollowedByExtss_returnsAudioDtsHd() {
    byte[] dtsHdFrame = buildDtsCoreFrame(/* coreFrameSize= */ 16, /* appendExtss= */ true);
    assertThat(DtsUtil.getDtsAudioMimeType(dtsHdFrame)).isEqualTo(MimeTypes.AUDIO_DTS_HD);
  }

  @Test
  public void getDtsAudioMimeType_standaloneExtss_returnsAudioDtsHd() {
    byte[] extss = new byte[] {0x64, 0x58, 0x20, 0x25, 0x00, 0x00, 0x00, 0x00};
    assertThat(DtsUtil.getDtsAudioMimeType(extss)).isEqualTo(MimeTypes.AUDIO_DTS_HD);
  }

  @Test
  public void getDtsAudioMimeType_uhdSyncAndNonSync_returnsAudioDtsX() {
    byte[] uhdSync = new byte[] {0x40, 0x41, 0x1B, (byte) 0xF2, 0x00, 0x00, 0x00, 0x00};
    byte[] uhdNonSync = new byte[] {0x71, (byte) 0xC4, 0x42, (byte) 0xE8, 0x00, 0x00, 0x00, 0x00};
    assertThat(DtsUtil.getDtsAudioMimeType(uhdSync)).isEqualTo(MimeTypes.AUDIO_DTS_X);
    assertThat(DtsUtil.getDtsAudioMimeType(uhdNonSync)).isEqualTo(MimeTypes.AUDIO_DTS_X);
  }

  @Test
  public void getDtsAudioMimeType_shortOrUnknown_returnsAudioDts() {
    assertThat(DtsUtil.getDtsAudioMimeType(new byte[] {0x7F, (byte) 0xFE}))
        .isEqualTo(MimeTypes.AUDIO_DTS);
    assertThat(DtsUtil.getDtsAudioMimeType(new byte[] {0x00, 0x01, 0x02, 0x03}))
        .isEqualTo(MimeTypes.AUDIO_DTS);
  }

  @Test
  public void extractor_dtsHdMaTrack_detectsDtsHdBeforeEndTracksWithoutDuplicateFormat()
      throws Exception {
    byte[] dtsHdSample = buildDtsCoreFrame(/* coreFrameSize= */ 16, /* appendExtss= */ true);
    byte[] mkv = buildDtsMkv(dtsHdSample, /* includeCluster= */ true);

    // Default FakeExtractorOutput uses deduplicateConsecutiveFormats=false, proving format() is
    // published once with the refined DTS-HD MIME type before endTracks().
    FakeExtractorOutput output = extractAll(mkv, /* deduplicateFormats= */ false);
    assertThat(output.tracksEnded).isTrue();
    FakeTrackOutput trackOutput = output.trackOutputs.get(1);
    Format format = trackOutput.lastFormat;
    assertThat(format).isNotNull();
    assertThat(format.sampleMimeType).isEqualTo(MimeTypes.AUDIO_DTS_HD);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleData(0)).isEqualTo(dtsHdSample);
  }

  @Test
  public void extractor_dtsXTrack_detectsDtsXBeforeEndTracks() throws Exception {
    byte[] dtsXSample = new byte[20];
    dtsXSample[0] = 0x40;
    dtsXSample[1] = 0x41;
    dtsXSample[2] = 0x1B;
    dtsXSample[3] = (byte) 0xF2;
    byte[] mkv = buildDtsMkv(dtsXSample, /* includeCluster= */ true);

    FakeExtractorOutput output = extractAll(mkv, /* deduplicateFormats= */ false);
    assertThat(output.tracksEnded).isTrue();
    Format format = output.trackOutputs.get(1).lastFormat;
    assertThat(format).isNotNull();
    assertThat(format.sampleMimeType).isEqualTo(MimeTypes.AUDIO_DTS_X);
  }

  @Test
  public void extractor_dtsTrackWithoutCluster_stillEndsTracksWithProvisionalDtsMime()
      throws Exception {
    byte[] mkv = buildDtsMkv(new byte[0], /* includeCluster= */ false);

    FakeExtractorOutput output = extractAll(mkv, /* deduplicateFormats= */ false);
    assertThat(output.tracksEnded).isTrue();
    Format format = output.trackOutputs.get(1).lastFormat;
    assertThat(format).isNotNull();
    assertThat(format.sampleMimeType).isEqualTo(MimeTypes.AUDIO_DTS);
  }

  @Test
  public void extractor_dtsTrackPlacedAfterCluster_refinesMimeViaWriteSampleDataFallback()
      throws Exception {
    byte[] dtsHdSample = buildDtsCoreFrame(/* coreFrameSize= */ 16, /* appendExtss= */ true);
    byte[] mkv = buildDtsMkvWithTracksAfterCluster(dtsHdSample);

    FakeExtractorOutput output = extractAll(mkv, /* deduplicateFormats= */ true);
    assertThat(output.tracksEnded).isTrue();
    FakeTrackOutput trackOutput = output.trackOutputs.get(1);
    assertThat(trackOutput.lastFormat).isNotNull();
    assertThat(trackOutput.lastFormat.sampleMimeType).isEqualTo(MimeTypes.AUDIO_DTS_HD);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleData(0)).isEqualTo(dtsHdSample);
  }

  private static byte[] buildDtsCoreFrame(int coreFrameSize, boolean appendExtss) {
    int totalLength = appendExtss ? coreFrameSize + 8 : coreFrameSize;
    byte[] data = new byte[totalLength];
    // SYNC_VALUE_BE = 0x7FFE8001
    data[0] = 0x7F;
    data[1] = (byte) 0xFE;
    data[2] = (byte) 0x80;
    data[3] = 0x01;
    int fsizeField = coreFrameSize - 1;
    data[5] = (byte) ((fsizeField >>> 12) & 0x03);
    data[6] = (byte) ((fsizeField >>> 4) & 0xFF);
    data[7] = (byte) ((fsizeField & 0x0F) << 4);
    if (appendExtss) {
      // SYNC_VALUE_EXTSS_BE = 0x64582025
      data[coreFrameSize] = 0x64;
      data[coreFrameSize + 1] = 0x58;
      data[coreFrameSize + 2] = 0x20;
      data[coreFrameSize + 3] = 0x25;
    }
    return data;
  }

  private static FakeExtractorOutput extractAll(byte[] data, boolean deduplicateFormats)
      throws Exception {
    MatroskaExtractor extractor =
        new MatroskaExtractor(
            SubtitleParser.Factory.UNSUPPORTED, MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA);
    FakeExtractorOutput output =
        deduplicateFormats
            ? new FakeExtractorOutput((id, type) -> new FakeTrackOutput(true))
            : new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(data).build();
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
    return output;
  }

  private static byte[] buildDtsMkv(byte[] samplePayload, boolean includeCluster) {
    byte[] ebmlHeader =
        ebmlElement(
            0x1A45DFA3,
            concat(
                ebmlUint(0x42F7, 1),
                ebmlString(0x4282, "matroska"),
                ebmlUint(0x4285, 2)));

    byte[] info = ebmlElement(0x1549A966, ebmlUint(0x2AD7B1, 1_000_000));
    byte[] audio = ebmlElement(0xE1, ebmlUint(0x9F, 6));
    byte[] trackEntry =
        ebmlElement(
            0xAE,
            concat(
                ebmlUint(0xD7, 1),
                ebmlUint(0x83, 2), // Audio
                ebmlString(0x86, "A_DTS"),
                audio));
    byte[] tracks = ebmlElement(0x1654AE6B, trackEntry);

    byte[] cluster = new byte[0];
    if (includeCluster) {
      byte[] simpleBlock =
          ebmlElement(
              0xA3, concat(new byte[] {(byte) 0x81, 0x00, 0x00, (byte) 0x80}, samplePayload));
      cluster = ebmlElement(0x1F43B675, concat(ebmlUint(0xE7, 0), simpleBlock));
    }

    byte[] segment = ebmlElement(0x18538067, concat(info, tracks, cluster));
    return concat(ebmlHeader, segment);
  }

  private static byte[] buildDtsMkvWithTracksAfterCluster(byte[] samplePayload) {
    byte[] ebmlHeader =
        ebmlElement(
            0x1A45DFA3,
            concat(
                ebmlUint(0x42F7, 1),
                ebmlString(0x4282, "matroska"),
                ebmlUint(0x4285, 2)));

    byte[] info = ebmlElement(0x1549A966, ebmlUint(0x2AD7B1, 1_000_000));
    byte[] simpleBlock =
        ebmlElement(0xA3, concat(new byte[] {(byte) 0x81, 0x00, 0x00, (byte) 0x80}, samplePayload));
    byte[] cluster = ebmlElement(0x1F43B675, concat(ebmlUint(0xE7, 0), simpleBlock));

    byte[] audio = ebmlElement(0xE1, ebmlUint(0x9F, 6));
    byte[] trackEntry =
        ebmlElement(
            0xAE,
            concat(
                ebmlUint(0xD7, 1),
                ebmlUint(0x83, 2),
                ebmlString(0x86, "A_DTS"),
                audio));
    byte[] tracks = ebmlElement(0x1654AE6B, trackEntry);

    byte[] placeholderSeekHead = ebmlElement(0x114D9B74, seekEntry(0x1654AE6B, 0x0100));
    int tracksRelPos = placeholderSeekHead.length + info.length + cluster.length;
    byte[] seekHead = ebmlElement(0x114D9B74, seekEntry(0x1654AE6B, tracksRelPos));
    assertThat(seekHead.length).isEqualTo(placeholderSeekHead.length);

    byte[] segment = ebmlElement(0x18538067, concat(seekHead, info, cluster, tracks));
    return concat(ebmlHeader, segment);
  }

  private static byte[] seekEntry(int targetId, int relativePosition) {
    byte[] seekIdBytes =
        new byte[] {
          (byte) ((targetId >>> 24) & 0xFF),
          (byte) ((targetId >>> 16) & 0xFF),
          (byte) ((targetId >>> 8) & 0xFF),
          (byte) (targetId & 0xFF)
        };
    byte[] seekPosBytes =
        new byte[] {(byte) ((relativePosition >>> 8) & 0xFF), (byte) (relativePosition & 0xFF)};
    return ebmlElement(
        0x4DBB, concat(ebmlElement(0x53AB, seekIdBytes), ebmlElement(0x53AC, seekPosBytes)));
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
