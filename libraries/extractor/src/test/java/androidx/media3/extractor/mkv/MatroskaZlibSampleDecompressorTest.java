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
import static org.junit.Assert.assertThrows;

import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.Deflater;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link MatroskaZlibSampleDecompressor} and zlib tracks in {@link MatroskaExtractor}. */
@RunWith(AndroidJUnit4.class)
public final class MatroskaZlibSampleDecompressorTest {

  @Test
  public void decompress_restoresZlibCompressedSubtitlePayload() throws Exception {
    byte[] original =
        "Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,Hello".getBytes(StandardCharsets.UTF_8);
    byte[] compressed = deflate(original);

    MatroskaZlibSampleDecompressor decompressor = new MatroskaZlibSampleDecompressor();
    try {
      ParsableByteArray decompressed = decompressor.decompress(compressed, 0, compressed.length);
      assertThat(decompressed.limit()).isEqualTo(original.length);
      assertThat(Arrays.copyOf(decompressed.getData(), decompressed.limit())).isEqualTo(original);
    } finally {
      decompressor.release();
    }
  }

  @Test
  public void decompress_reusesInflaterAndGrowsOutputBufferAcrossCalls() throws Exception {
    byte[] first = "First".getBytes(StandardCharsets.UTF_8);
    byte[] second = new byte[2048];
    Arrays.fill(second, (byte) 'A');

    MatroskaZlibSampleDecompressor decompressor = new MatroskaZlibSampleDecompressor();
    try {
      byte[] firstCompressed = deflate(first);
      ParsableByteArray firstOut =
          decompressor.decompress(firstCompressed, 0, firstCompressed.length);
      assertThat(Arrays.copyOf(firstOut.getData(), firstOut.limit())).isEqualTo(first);

      byte[] secondCompressed = deflate(second);
      ParsableByteArray secondOut =
          decompressor.decompress(secondCompressed, 0, secondCompressed.length);
      assertThat(Arrays.copyOf(secondOut.getData(), secondOut.limit())).isEqualTo(second);
    } finally {
      decompressor.release();
    }
  }

  @Test
  public void decompress_malformedData_throwsParserException() {
    MatroskaZlibSampleDecompressor decompressor = new MatroskaZlibSampleDecompressor();
    try {
      byte[] invalid = new byte[] {0x01, 0x02, 0x03, 0x04};
      assertThrows(
          ParserException.class, () -> decompressor.decompress(invalid, 0, invalid.length));
    } finally {
      decompressor.release();
    }
  }

  @Test
  public void extractor_explicitZlibCompressedSubtitleTrack_extractsDecompressedSample()
      throws Exception {
    byte[] subtitlePayload = "Hello zlib subtitle".getBytes(StandardCharsets.UTF_8);
    byte[] mkv = buildMkvWithCompressedSubtitle(subtitlePayload, /* includeExplicitAlgo= */ true);

    FakeExtractorOutput output = extractAll(mkv);
    FakeTrackOutput trackOutput = output.trackOutputs.get(1);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    String sampleText = new String(trackOutput.getSampleData(0), StandardCharsets.UTF_8);
    assertThat(sampleText).endsWith("Hello zlib subtitle");
  }

  @Test
  public void extractor_defaultZlibCompressionWithoutAlgoElement_extractsDecompressedSample()
      throws Exception {
    byte[] subtitlePayload = "Implicit default zlib".getBytes(StandardCharsets.UTF_8);
    byte[] mkv = buildMkvWithCompressedSubtitle(subtitlePayload, /* includeExplicitAlgo= */ false);

    FakeExtractorOutput output = extractAll(mkv);
    FakeTrackOutput trackOutput = output.trackOutputs.get(1);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    String sampleText = new String(trackOutput.getSampleData(0), StandardCharsets.UTF_8);
    assertThat(sampleText).endsWith("Implicit default zlib");
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

  private static byte[] buildMkvWithCompressedSubtitle(
      byte[] subtitlePayload, boolean includeExplicitAlgo) throws Exception {
    byte[] ebmlHeader =
        ebmlElement(
            0x1A45DFA3,
            concat(
                ebmlUint(0x42F7, 1),
                ebmlString(0x4282, "matroska"),
                ebmlUint(0x4285, 2)));

    byte[] info = ebmlElement(0x1549A966, ebmlUint(0x2AD7B1, 1_000_000));

    byte[] compressionContent =
        includeExplicitAlgo ? ebmlUint(0x4254, 0) : new byte[0];
    byte[] contentCompression = ebmlElement(0x5034, compressionContent);
    byte[] contentEncoding = ebmlElement(0x6240, contentCompression);
    byte[] contentEncodings = ebmlElement(0x6D80, contentEncoding);

    byte[] trackEntry =
        ebmlElement(
            0xAE,
            concat(
                ebmlUint(0xD7, 1), // TrackNumber = 1
                ebmlUint(0x83, 17), // TrackType = subtitle
                ebmlString(0x86, "S_TEXT/UTF8"),
                contentEncodings));
    byte[] tracks = ebmlElement(0x1654AE6B, trackEntry);

    byte[] compressedPayload = deflate(subtitlePayload);
    byte[] blockBytes = concat(new byte[] {(byte) 0x81, 0x00, 0x00, 0x00}, compressedPayload);
    byte[] blockGroup =
        ebmlElement(
            0xA0,
            concat(
                ebmlElement(0xA1, blockBytes), // Block
                ebmlUint(0x9B, 2000))); // BlockDuration = 2000 ms
    byte[] cluster =
        ebmlElement(
            0x1F43B675,
            concat(
                ebmlUint(0xE7, 1000), // Timecode = 1000 ms
                blockGroup));

    byte[] segment = ebmlElement(0x18538067, concat(info, tracks, cluster));
    return concat(ebmlHeader, segment);
  }

  private static byte[] deflate(byte[] input) {
    Deflater deflater = new Deflater();
    deflater.setInput(input);
    deflater.finish();
    ByteArrayOutputStream output = new ByteArrayOutputStream(input.length);
    byte[] scratch = new byte[256];
    while (!deflater.finished()) {
      int count = deflater.deflate(scratch);
      if (count > 0) {
        output.write(scratch, 0, count);
      }
    }
    deflater.end();
    return output.toByteArray();
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
