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

import static java.lang.Math.max;

import androidx.annotation.Nullable;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.ExtractorInput;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reusable zlib inflater for Matroska {@code ContentCompAlgo=0} samples.
 *
 * <p>Buffers and the {@link Inflater} are reused across samples to avoid per-sample allocations
 * and inflater construction during playback.
 */
/* package */ final class MatroskaZlibSampleDecompressor {

  private static final int INITIAL_DECOMPRESSED_CAPACITY = 512;

  private byte[] compressedBuffer = Util.EMPTY_BYTE_ARRAY;
  private byte[] decompressedBuffer = new byte[INITIAL_DECOMPRESSED_CAPACITY];
  private final ParsableByteArray decompressedSample = new ParsableByteArray();

  @Nullable private Inflater inflater;

  public void release() {
    if (inflater != null) {
      inflater.end();
      inflater = null;
    }
  }

  public ParsableByteArray decompress(ExtractorInput input, int compressedSize) throws IOException {
    ensureCompressedCapacity(compressedSize);
    input.readFully(compressedBuffer, /* offset= */ 0, compressedSize);
    return inflate(compressedBuffer, /* offset= */ 0, compressedSize);
  }

  /** Visible for unit tests that exercise the inflater without an {@link ExtractorInput}. */
  public ParsableByteArray decompress(byte[] compressed, int offset, int length)
      throws ParserException {
    return inflate(compressed, offset, length);
  }

  private ParsableByteArray inflate(byte[] compressed, int offset, int length)
      throws ParserException {
    Inflater activeInflater = inflater;
    if (activeInflater == null) {
      activeInflater = new Inflater();
      inflater = activeInflater;
    } else {
      activeInflater.reset();
    }
    activeInflater.setInput(compressed, offset, length);

    ensureDecompressedCapacity(max(length * 4, INITIAL_DECOMPRESSED_CAPACITY));

    int totalOut = 0;
    try {
      while (!activeInflater.finished()) {
        if (activeInflater.needsInput()) {
          break;
        }
        if (totalOut == decompressedBuffer.length) {
          ensureDecompressedCapacity(decompressedBuffer.length * 2);
        }
        int count =
            activeInflater.inflate(
                decompressedBuffer, totalOut, decompressedBuffer.length - totalOut);
        if (count > 0) {
          totalOut += count;
        } else if (activeInflater.needsDictionary()) {
          throw ParserException.createForMalformedContainer(
              "Zlib sample uses an unsupported preset dictionary", /* cause= */ null);
        } else if (!activeInflater.finished()) {
          ensureDecompressedCapacity(decompressedBuffer.length * 2);
        }
      }
    } catch (DataFormatException e) {
      throw ParserException.createForMalformedContainer(
          "Failed to inflate zlib-compressed Matroska sample", e);
    }

    if (!activeInflater.finished() || totalOut == 0) {
      throw ParserException.createForMalformedContainer(
          "Zlib-compressed Matroska sample did not decompress completely", /* cause= */ null);
    }

    decompressedSample.reset(decompressedBuffer, totalOut);
    return decompressedSample;
  }

  private void ensureCompressedCapacity(int requiredLength) {
    if (compressedBuffer.length < requiredLength) {
      compressedBuffer =
          Arrays.copyOf(
              compressedBuffer,
              max(
                  requiredLength,
                  compressedBuffer.length == 0 ? requiredLength : compressedBuffer.length * 2));
    }
  }

  private void ensureDecompressedCapacity(int requiredLength) {
    if (decompressedBuffer.length < requiredLength) {
      decompressedBuffer =
          Arrays.copyOf(
              decompressedBuffer, max(requiredLength, decompressedBuffer.length * 2));
    }
  }
}
