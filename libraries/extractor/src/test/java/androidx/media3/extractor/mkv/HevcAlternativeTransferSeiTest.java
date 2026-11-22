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
import androidx.media3.common.Format;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link HevcAlternativeTransferSei} and HLG SEI detection in {@link MatroskaExtractor}. */
@RunWith(AndroidJUnit4.class)
public final class HevcAlternativeTransferSeiTest {

  // Prefix SEI as x265 writes it for --atc-sei 18 (HLG).
  private static final byte[] ALTERNATIVE_TRANSFER_SEI =
      TestUtil.createByteArray(0x4E, 0x01, 0x93, 0x01, 0x12, 0x80);

  // User data SEI whose payload needs an emulation prevention byte removed.
  private static final byte[] OTHER_SEI =
      TestUtil.createByteArray(0x4E, 0x01, 0x05, 0x03, 0x00, 0x00, 0x03, 0x01, 0x80);

  private static final byte[] IDR_SLICE = TestUtil.createByteArray(0x28, 0x01, 0xAF, 0x1D, 0x30);

  @Test
  public void parsePreferredTransfer_readsHlgFromX265Sei() {
    assertThat(
            HevcAlternativeTransferSei.parsePreferredTransfer(
                ALTERNATIVE_TRANSFER_SEI, ALTERNATIVE_TRANSFER_SEI.length))
        .isEqualTo(18);
  }

  @Test
  public void parsePreferredTransfer_findsMessageAfterEscapedSei() {
    byte[] sei =
        TestUtil.createByteArray(
            0x4E, 0x01, 0x05, 0x03, 0x00, 0x00, 0x03, 0x01, 0x93, 0x01, 0x12, 0x80);
    assertThat(HevcAlternativeTransferSei.parsePreferredTransfer(sei, sei.length)).isEqualTo(18);
  }

  @Test
  public void parsePreferredTransfer_seiWithoutMessage_returnsNoValue() {
    assertThat(HevcAlternativeTransferSei.parsePreferredTransfer(OTHER_SEI, OTHER_SEI.length))
        .isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void peekPreferredTransfer_findsSeiAheadOfSliceAndLeavesInputUntouched() throws Exception {
    byte[] sample = accessUnit(4, OTHER_SEI, ALTERNATIVE_TRANSFER_SEI, IDR_SLICE);
    ExtractorInput input = new FakeExtractorInput.Builder().setData(sample).build();

    assertThat(HevcAlternativeTransferSei.peekPreferredTransfer(input, sample.length, 4))
        .isEqualTo(18);
    assertThat(input.getPosition()).isEqualTo(0L);
    assertThat(input.getPeekPosition()).isEqualTo(0L);
  }

  @Test
  public void peekPreferredTransfer_stopsAtFirstSlice() throws Exception {
    byte[] sample = accessUnit(4, IDR_SLICE, ALTERNATIVE_TRANSFER_SEI);
    ExtractorInput input = new FakeExtractorInput.Builder().setData(sample).build();

    assertThat(HevcAlternativeTransferSei.peekPreferredTransfer(input, sample.length, 4))
        .isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void peekPreferredTransfer_handlesShortNalLengthFields() throws Exception {
    byte[] sample = accessUnit(2, ALTERNATIVE_TRANSFER_SEI, IDR_SLICE);
    ExtractorInput input = new FakeExtractorInput.Builder().setData(sample).build();

    assertThat(HevcAlternativeTransferSei.peekPreferredTransfer(input, sample.length, 2))
        .isEqualTo(18);
  }

  @Test
  public void peekPreferredTransfer_nalLengthPastSample_returnsNoValue() throws Exception {
    byte[] sample = accessUnit(4, ALTERNATIVE_TRANSFER_SEI);
    ExtractorInput input = new FakeExtractorInput.Builder().setData(sample).build();

    assertThat(HevcAlternativeTransferSei.peekPreferredTransfer(input, sample.length - 1, 4))
        .isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void extractor_hevcSampleWithHlgAlternativeTransferSei_updatesTrackColorInfo()
      throws Exception {
    byte[] accessUnit = accessUnit(4, OTHER_SEI, ALTERNATIVE_TRANSFER_SEI, IDR_SLICE);
    byte[] mkv = buildHevcMkv(accessUnit);

    MatroskaExtractor extractor =
        new MatroskaExtractor(
            SubtitleParser.Factory.UNSUPPORTED, MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA);
    FakeExtractorOutput output =
        new FakeExtractorOutput(
            (id, type) -> new FakeTrackOutput(/* deduplicateConsecutiveFormats= */ true));
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

    FakeTrackOutput trackOutput = output.trackOutputs.get(1);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.lastFormat).isNotNull();
    assertThat(trackOutput.lastFormat.colorInfo).isNotNull();
    assertThat(trackOutput.lastFormat.colorInfo.colorTransfer).isEqualTo(C.COLOR_TRANSFER_HLG);
    assertThat(trackOutput.lastFormat.colorInfo.colorSpace).isEqualTo(C.COLOR_SPACE_BT2020);
  }

  private static byte[] accessUnit(int lengthFieldSize, byte[]... nalUnits) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] nalUnit : nalUnits) {
      for (int shift = lengthFieldSize - 1; shift >= 0; shift--) {
        out.write((nalUnit.length >>> (8 * shift)) & 0xFF);
      }
      out.write(nalUnit, 0, nalUnit.length);
    }
    return out.toByteArray();
  }

  private static byte[] buildHevcMkv(byte[] samplePayload) {
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

    byte[] video = ebmlElement(0xE0, concat(ebmlUint(0xB0, 3840), ebmlUint(0xBA, 2160)));
    byte[] trackEntry =
        ebmlElement(
            0xAE,
            concat(
                ebmlUint(0xD7, 1),
                ebmlUint(0x83, 1),
                ebmlString(0x86, "V_MPEGH/ISO/HEVC"),
                ebmlElement(0x63A2, hevcPrivate),
                video));
    byte[] tracks = ebmlElement(0x1654AE6B, trackEntry);

    byte[] simpleBlock =
        ebmlElement(0xA3, concat(new byte[] {(byte) 0x81, 0x00, 0x00, (byte) 0x80}, samplePayload));
    byte[] cluster = ebmlElement(0x1F43B675, concat(ebmlUint(0xE7, 0), simpleBlock));

    byte[] segment = ebmlElement(0x18538067, concat(info, tracks, cluster));
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
