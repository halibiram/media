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

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for graceful handling of truncated and zero-padded Matroska tails. */
@RunWith(AndroidJUnit4.class)
public final class MatroskaTruncatedTailTest {

  @Test
  public void varintReader_zeroByteWithAllowEndOfInput_returnsMaxLengthExceeded()
      throws IOException {
    VarintReader reader = new VarintReader();
    ExtractorInput input = new FakeExtractorInput.Builder().setData(new byte[] {0x00}).build();

    long result =
        reader.readUnsignedVarint(
            input,
            /* allowEndOfInput= */ true,
            /* removeLengthMask= */ false,
            /* maximumAllowedLength= */ 4);

    assertThat(result).isEqualTo(C.RESULT_MAX_LENGTH_EXCEEDED);
  }

  @Test
  public void varintReader_zeroByteWithoutAllowEndOfInput_throwsParserException() {
    VarintReader reader = new VarintReader();
    ExtractorInput input = new FakeExtractorInput.Builder().setData(new byte[] {0x00}).build();

    ParserException exception =
        assertThrows(
            ParserException.class,
            () ->
                reader.readUnsignedVarint(
                    input,
                    /* allowEndOfInput= */ false,
                    /* removeLengthMask= */ true,
                    /* maximumAllowedLength= */ 8));

    assertThat(exception).hasMessageThat().contains("No valid varint length mask found");
  }

  @Test
  public void ebmlReader_trailingZerosAfterCompleteElement_endsStream() throws IOException {
    byte[] data = concat(new byte[] {(byte) 0xEC, (byte) 0x80}, new byte[32]);
    DefaultEbmlReader reader = new DefaultEbmlReader();
    RecordingProcessor processor = new RecordingProcessor(Collections.emptySet());
    reader.init(processor);

    ExtractorInput input = new FakeExtractorInput.Builder().setData(data).build();
    assertThat(reader.read(input)).isFalse();
    assertThat(processor.seenIds).containsExactly(0xEC);
  }

  @Test
  public void ebmlReader_resyncSkipsZeroPaddingToNextLevel1Cluster() throws IOException {
    byte[] clusterId = new byte[] {0x1F, 0x43, (byte) 0xB6, 0x75};
    byte[] data =
        concat(
            new byte[] {(byte) 0xEC, (byte) 0x80},
            new byte[8],
            clusterId,
            new byte[] {(byte) 0x80});
    DefaultEbmlReader reader = new DefaultEbmlReader();
    RecordingProcessor processor = new RecordingProcessor(Collections.singleton(0x1F43B675));
    reader.init(processor);

    ExtractorInput input = new FakeExtractorInput.Builder().setData(data).build();
    assertThat(reader.read(input)).isFalse();
    assertThat(processor.seenIds).containsExactly(0xEC, 0x1F43B675).inOrder();
  }

  @Test
  public void extractor_truncatedClusterTailAfterSeekMap_returnsEndOfInput() throws Exception {
    byte[] validSample = new byte[] {0x11, 0x22, 0x33, 0x44};
    byte[] truncatedSecondBlock =
        new byte[] {
          (byte) 0xA3, // SimpleBlock
          (byte) 0x90, // Declares 16 bytes, but file ends after 4 bytes
          (byte) 0x81,
          0x00,
          0x20,
          (byte) 0x80
        };
    byte[] mkv = buildMinimalMkvWithTail(validSample, truncatedSecondBlock);

    FakeExtractorOutput output = extractAll(mkv);
    FakeTrackOutput trackOutput = output.trackOutputs.get(1);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleData(0)).isEqualTo(validSample);
  }

  @Test
  public void extractor_zeroPaddedTailAfterSeekMap_returnsEndOfInput() throws Exception {
    byte[] validSample = new byte[] {0x55, 0x66, 0x77};
    byte[] mkv = buildMinimalMkvWithTail(validSample, new byte[64]);

    FakeExtractorOutput output = extractAll(mkv);
    FakeTrackOutput trackOutput = output.trackOutputs.get(1);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleData(0)).isEqualTo(validSample);
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

  private static byte[] buildMinimalMkvWithTail(byte[] samplePayload, byte[] trailingBytes) {
    byte[] ebmlHeader =
        ebmlElement(
            0x1A45DFA3,
            concat(
                ebmlUint(0x42F7, 1),
                ebmlString(0x4282, "matroska"),
                ebmlUint(0x4285, 2)));

    byte[] info = ebmlElement(0x1549A966, ebmlUint(0x2AD7B1, 1_000_000));
    byte[] trackEntry =
        ebmlElement(
            0xAE,
            concat(
                ebmlUint(0xD7, 1),
                ebmlUint(0x83, 1), // Video
                ebmlString(0x86, "V_VP8")));
    byte[] tracks = ebmlElement(0x1654AE6B, trackEntry);

    byte[] simpleBlock =
        ebmlElement(0xA3, concat(new byte[] {(byte) 0x81, 0x00, 0x00, (byte) 0x80}, samplePayload));
    byte[] cluster =
        ebmlElement(0x1F43B675, concat(ebmlUint(0xE7, 0), simpleBlock));

    // Segment with unknown size so trailingBytes sit directly at the stream tail.
    byte[] segmentHeader =
        new byte[] {0x18, 0x53, (byte) 0x80, 0x67, 0x01, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    return concat(ebmlHeader, segmentHeader, info, tracks, cluster, trailingBytes);
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

  private static final class RecordingProcessor implements EbmlProcessor {
    private final Set<Integer> level1Ids;
    final List<Integer> seenIds = new ArrayList<>();

    RecordingProcessor(Set<Integer> level1Ids) {
      this.level1Ids = level1Ids;
    }

    @Override
    public int getElementType(int id) {
      seenIds.add(id);
      return EbmlProcessor.ELEMENT_TYPE_UNKNOWN;
    }

    @Override
    public boolean isLevel1Element(int id) {
      return level1Ids.contains(id);
    }

    @Override
    public void startMasterElement(int id, long contentPosition, long contentSize) {}

    @Override
    public void endMasterElement(int id) {}

    @Override
    public void integerElement(int id, long value) {}

    @Override
    public void floatElement(int id, double value) {}

    @Override
    public void stringElement(int id, String value) {}

    @Override
    public void binaryElement(int id, int contentsSize, ExtractorInput input) throws IOException {
      input.skipFully(contentsSize);
    }
  }
}
