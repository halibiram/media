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
package androidx.media3.extractor.ts;

import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static java.lang.Math.max;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import java.util.Arrays;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Parses a Blu-ray HDMV Presentation Graphics Stream (PGS, stream type {@code 0x90}).
 *
 * <p>PES packets carry PGS segments (1-byte type + 2-byte big-endian payload length). A complete
 * display set runs from a presentation composition segment ({@code 0x16}) through an end of display
 * set segment ({@code 0x80}) and is emitted as a single sample at the starting packet's PTS.
 */
@UnstableApi
public final class HdmvPgsReader implements ElementaryStreamReader {

  private static final int SEGMENT_HEADER_LENGTH = 3;
  /* package */ static final int SEGMENT_PRESENTATION_COMPOSITION = 0x16;
  /* package */ static final int SEGMENT_END = 0x80;

  @Nullable private final String language;
  private final @C.RoleFlags int roleFlags;
  private final String containerMimeType;
  private final ParsableByteArray displaySetBuffer;

  private @MonotonicNonNull TrackOutput output;

  private byte[] pending;
  private int pendingLength;
  private long packetTimeUs;

  private byte[] displaySet;
  private int displaySetLength;
  private long displaySetTimeUs;

  public HdmvPgsReader(
      @Nullable String language, @C.RoleFlags int roleFlags, String containerMimeType) {
    this.language = language;
    this.roleFlags = roleFlags;
    this.containerMimeType = containerMimeType;
    this.displaySetBuffer = new ParsableByteArray();
    this.pending = new byte[16 * 1024];
    this.packetTimeUs = C.TIME_UNSET;
    this.displaySet = new byte[16 * 1024];
    this.displaySetTimeUs = C.TIME_UNSET;
  }

  @Override
  public void seek() {
    pendingLength = 0;
    packetTimeUs = C.TIME_UNSET;
    resetDisplaySet();
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_TEXT);
    output.format(
        new Format.Builder()
            .setId(idGenerator.getFormatId())
            .setContainerMimeType(containerMimeType)
            .setSampleMimeType(MimeTypes.APPLICATION_PGS)
            .setLanguage(language)
            .setRoleFlags(roleFlags)
            .build());
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    packetTimeUs = pesTimeUs;
  }

  @Override
  public void consume(ParsableByteArray data) {
    checkStateNotNull(output);
    int length = data.bytesLeft();
    if (pendingLength + length > pending.length) {
      pending = Arrays.copyOf(pending, max(pending.length * 2, pendingLength + length));
    }
    data.readBytes(pending, pendingLength, length);
    pendingLength += length;
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
    int position = 0;
    while (position + SEGMENT_HEADER_LENGTH <= pendingLength) {
      int type = pending[position] & 0xFF;
      int segmentLength =
          SEGMENT_HEADER_LENGTH
              + (((pending[position + 1] & 0xFF) << 8) | (pending[position + 2] & 0xFF));
      // A segment split across PES packets waits for its remaining bytes in the next packet.
      if (position + segmentLength > pendingLength) {
        break;
      }
      onSegment(type, position, segmentLength);
      position += segmentLength;
    }
    pendingLength -= position;
    if (pendingLength > 0 && position > 0) {
      System.arraycopy(pending, position, pending, 0, pendingLength);
    }
    if (isEndOfInput) {
      pendingLength = 0;
    }
  }

  private void onSegment(int type, int position, int length) {
    if (type == SEGMENT_PRESENTATION_COMPOSITION) {
      // Starting a new display set discards any previous incomplete set.
      resetDisplaySet();
      displaySetTimeUs = packetTimeUs;
    }
    // Segments before the first composition (e.g. after a seek) belong to a missed display set.
    if (displaySetTimeUs == C.TIME_UNSET) {
      return;
    }
    if (displaySetLength + length > displaySet.length) {
      displaySet = Arrays.copyOf(displaySet, max(displaySet.length * 2, displaySetLength + length));
    }
    System.arraycopy(pending, position, displaySet, displaySetLength, length);
    displaySetLength += length;
    if (type == SEGMENT_END) {
      TrackOutput trackOutput = checkStateNotNull(output);
      displaySetBuffer.reset(displaySet, displaySetLength);
      trackOutput.sampleData(displaySetBuffer, displaySetLength);
      trackOutput.sampleMetadata(
          displaySetTimeUs,
          C.BUFFER_FLAG_KEY_FRAME,
          displaySetLength,
          /* offset= */ 0,
          /* cryptoData= */ null);
      resetDisplaySet();
    }
  }

  private void resetDisplaySet() {
    displaySetLength = 0;
    displaySetTimeUs = C.TIME_UNSET;
  }
}
