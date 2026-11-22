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

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for ignoring stale Matroska Dolby Vision metadata on SDR tracks. */
@RunWith(AndroidJUnit4.class)
public final class MatroskaStaleDolbyVisionTest {

  private static final int BLOCK_ADD_ID_TYPE_DVVC = 0x64767643;
  private static final int ISO_PRIMARIES_BT709 = 1;
  private static final int ISO_PRIMARIES_BT2020 = 9;
  private static final int ISO_TRANSFER_BT709 = 1;
  private static final int ISO_TRANSFER_PQ = 16;

  @Test
  public void isStaleContainerDolbyVisionConfig_onlyTrueWhenColorInfoIndicatesSdr() {
    assertThat(
            MatroskaExtractor.isStaleContainerDolbyVisionConfig(
                /* hasColorInfo= */ true, C.COLOR_TRANSFER_SDR))
        .isTrue();
    assertThat(
            MatroskaExtractor.isStaleContainerDolbyVisionConfig(
                /* hasColorInfo= */ false, C.COLOR_TRANSFER_SDR))
        .isFalse();
    assertThat(
            MatroskaExtractor.isStaleContainerDolbyVisionConfig(
                /* hasColorInfo= */ true, C.COLOR_TRANSFER_ST2084))
        .isFalse();
    assertThat(
            MatroskaExtractor.isStaleContainerDolbyVisionConfig(
                /* hasColorInfo= */ true, C.COLOR_TRANSFER_HLG))
        .isFalse();
    assertThat(
            MatroskaExtractor.isStaleContainerDolbyVisionConfig(
                /* hasColorInfo= */ true, Format.NO_VALUE))
        .isFalse();
  }

  @Test
  public void extractor_sdrColorMetadataWithStaleDvConfig_keepsHevcMimeType() throws Exception {
    byte[] mkv =
        buildHevcDvMkv(
            /* colorPrimaries= */ ISO_PRIMARIES_BT709,
            /* transferCharacteristics= */ ISO_TRANSFER_BT709);

    FakeExtractorOutput output = extractAll(mkv);
    Format format = output.trackOutputs.get(1).lastFormat;
    assertThat(format).isNotNull();
    assertThat(format.sampleMimeType).isEqualTo(MimeTypes.VIDEO_H265);
    assertThat(format.colorInfo).isNotNull();
    assertThat(format.colorInfo.colorTransfer).isEqualTo(C.COLOR_TRANSFER_SDR);
  }

  @Test
  public void extractor_pqColorMetadataWithDvConfig_usesDolbyVisionMimeType() throws Exception {
    byte[] mkv =
        buildHevcDvMkv(
            /* colorPrimaries= */ ISO_PRIMARIES_BT2020,
            /* transferCharacteristics= */ ISO_TRANSFER_PQ);

    FakeExtractorOutput output = extractAll(mkv);
    Format format = output.trackOutputs.get(1).lastFormat;
    assertThat(format).isNotNull();
    assertThat(format.sampleMimeType).isEqualTo(MimeTypes.VIDEO_DOLBY_VISION);
    assertThat(format.codecs).isEqualTo("dvhe.08.06");
  }

  @Test
  public void extractor_noColorMetadataWithDvConfig_usesDolbyVisionMimeType() throws Exception {
    byte[] mkv = buildHevcDvMkv(/* colorPrimaries= */ null, /* transferCharacteristics= */ null);

    FakeExtractorOutput output = extractAll(mkv);
    Format format = output.trackOutputs.get(1).lastFormat;
    assertThat(format).isNotNull();
    assertThat(format.sampleMimeType).isEqualTo(MimeTypes.VIDEO_DOLBY_VISION);
    assertThat(format.codecs).isEqualTo("dvhe.08.06");
  }

  private static FakeExtractorOutput extractAll(byte[] data) throws Exception {
    MatroskaExtractor extractor =
        new MatroskaExtractor(
            SubtitleParser.Factory.UNSUPPORTED, MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA);
    FakeExtractorOutput output = new FakeExtractorOutput();
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

  private static byte[] buildHevcDvMkv(
      @Nullable Integer colorPrimaries, @Nullable Integer transferCharacteristics) {
    byte[] ebmlHeader =
        ebmlElement(
            0x1A45DFA3,
            concat(
                ebmlUint(0x42F7, 1),
                ebmlString(0x4282, "matroska"),
                ebmlUint(0x4285, 2)));

    byte[] info = ebmlElement(0x1549A966, ebmlUint(0x2AD7B1, 1_000_000));

    // Minimal valid HEVCDecoderConfigurationRecord (23 bytes, 4-byte NAL length, 0 arrays).
    byte[] hevcPrivate = new byte[23];
    hevcPrivate[21] = 0x03;

    // Valid Dolby Vision profile 8, level 6 config ("dvhe.08.06").
    byte[] dvConfig = new byte[] {0x01, 0x00, (byte) (8 << 1), (byte) (6 << 3)};
    byte[] blockAdditionMapping =
        ebmlElement(
            0x41E4,
            concat(
                ebmlUint(0x41E7, BLOCK_ADD_ID_TYPE_DVVC),
                ebmlElement(0x41ED, dvConfig)));

    byte[] colour =
        colorPrimaries != null && transferCharacteristics != null
            ? ebmlElement(
                0x55B0,
                concat(ebmlUint(0x55BB, colorPrimaries), ebmlUint(0x55BA, transferCharacteristics)))
            : new byte[0];
    byte[] video =
        ebmlElement(
            0xE0,
            concat(
                ebmlUint(0xB0, 1920),
                ebmlUint(0xBA, 1080),
                colour));

    byte[] trackEntry =
        ebmlElement(
            0xAE,
            concat(
                ebmlUint(0xD7, 1),
                ebmlUint(0x83, 1),
                ebmlString(0x86, "V_MPEGH/ISO/HEVC"),
                ebmlElement(0x63A2, hevcPrivate),
                video,
                blockAdditionMapping));
    byte[] tracks = ebmlElement(0x1654AE6B, trackEntry);

    byte[] segment = ebmlElement(0x18538067, concat(info, tracks));
    return concat(ebmlHeader, segment);
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
