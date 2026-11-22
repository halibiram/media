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
import androidx.media3.extractor.Ac3Util;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import java.util.Arrays;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Parses a Blu-ray HDMV Dolby TrueHD elementary stream (stream type {@code 0x83}).
 *
 * <p>Blu-ray TrueHD PIDs interleave AC-3 core frames (starting with {@code 0x0B 0x77}) and TrueHD
 * access units. This reader skips the AC-3 core packets and rechunks TrueHD access units starting
 * from the first major sync frame, emitting {@link Ac3Util#TRUEHD_RECHUNK_SAMPLE_COUNT} access
 * units per sample.
 */
@UnstableApi
public final class HdmvTrueHdReader implements ElementaryStreamReader {

  /** Channels contributed by each bit of a TrueHD channel arrangement, LSB first. */
  private static final int[] CHANNELS_PER_BIT = {2, 1, 1, 2, 2, 2, 2, 1, 1, 2, 2, 1, 1};

  @Nullable private final String language;
  private final @C.RoleFlags int roleFlags;
  private final String containerMimeType;
  private final ParsableByteArray chunkBuffer;
  private final byte[] syncframePrefixScratch;

  private @MonotonicNonNull String formatId;
  private @MonotonicNonNull TrackOutput output;

  private byte[] packet;
  private int packetLength;
  private long packetTimeUs;

  private boolean formatEmitted;
  private int sampleRate;
  private long unitDurationUs;
  private boolean synced;
  private long nextUnitTimeUs;

  private byte[] chunk;
  private int chunkLength;
  private int chunkUnits;
  private long chunkTimeUs;

  public HdmvTrueHdReader(
      @Nullable String language, @C.RoleFlags int roleFlags, String containerMimeType) {
    this.language = language;
    this.roleFlags = roleFlags;
    this.containerMimeType = containerMimeType;
    this.chunkBuffer = new ParsableByteArray();
    this.syncframePrefixScratch = new byte[Ac3Util.TRUEHD_SYNCFRAME_PREFIX_LENGTH];
    this.packet = new byte[8 * 1024];
    this.packetTimeUs = C.TIME_UNSET;
    this.nextUnitTimeUs = C.TIME_UNSET;
    this.chunk = new byte[64 * 1024];
    this.chunkTimeUs = C.TIME_UNSET;
  }

  @Override
  public void seek() {
    packetLength = 0;
    packetTimeUs = C.TIME_UNSET;
    nextUnitTimeUs = C.TIME_UNSET;
    synced = false;
    resetChunk();
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    packetLength = 0;
    packetTimeUs = pesTimeUs;
  }

  @Override
  public void consume(ParsableByteArray data) {
    checkStateNotNull(output);
    int length = data.bytesLeft();
    if (packetLength + length > packet.length) {
      packet = Arrays.copyOf(packet, max(packet.length * 2, packetLength + length));
    }
    data.readBytes(packet, packetLength, length);
    packetLength += length;
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
    if (packetLength > 0) {
      readPacket();
      packetLength = 0;
    }
    if (isEndOfInput) {
      flushChunk();
    }
  }

  private void readPacket() {
    // The interleaved AC-3 core shares the PID; skip frames starting with the AC-3 sync word.
    if (packetLength >= 2 && packet[0] == 0x0B && packet[1] == 0x77) {
      return;
    }
    long unitTimeUs = packetTimeUs != C.TIME_UNSET ? packetTimeUs : nextUnitTimeUs;
    int position = 0;
    while (position + 4 <= packetLength) {
      // Each access unit begins with a 4-bit check nibble and a 12-bit length in 16-bit words.
      int unitLength = (((packet[position] & 0x0F) << 8) | (packet[position + 1] & 0xFF)) * 2;
      if (unitLength < 4 || position + unitLength > packetLength) {
        break;
      }
      if (isMajorSync(packet, position, unitLength)) {
        onMajorSync(position);
      }
      if (synced && unitTimeUs != C.TIME_UNSET) {
        appendToChunk(position, unitLength, unitTimeUs);
        if (chunkUnits == Ac3Util.TRUEHD_RECHUNK_SAMPLE_COUNT) {
          flushChunk();
        }
      }
      if (unitTimeUs != C.TIME_UNSET && unitDurationUs > 0) {
        unitTimeUs += unitDurationUs;
      }
      position += unitLength;
    }
    nextUnitTimeUs = unitTimeUs;
  }

  private void onMajorSync(int position) {
    TrackOutput trackOutput = checkStateNotNull(output);
    if (!formatEmitted) {
      @Nullable MajorSyncInfo info = parseMajorSync(packet, position);
      if (info == null) {
        return;
      }
      sampleRate = info.sampleRate;
      trackOutput.format(
          new Format.Builder()
              .setId(formatId)
              .setContainerMimeType(containerMimeType)
              .setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
              .setChannelCount(info.channelCount)
              .setSampleRate(info.sampleRate)
              .setLanguage(language)
              .setRoleFlags(roleFlags)
              .build());
      formatEmitted = true;
    }
    if (position + Ac3Util.TRUEHD_SYNCFRAME_PREFIX_LENGTH <= packetLength) {
      System.arraycopy(
          packet, position, syncframePrefixScratch, 0, Ac3Util.TRUEHD_SYNCFRAME_PREFIX_LENGTH);
      int samples = Ac3Util.parseTrueHdSyncframeAudioSampleCount(syncframePrefixScratch);
      if (samples > 0 && sampleRate > 0) {
        unitDurationUs = (samples * C.MICROS_PER_SECOND) / sampleRate;
      }
    }
    synced = true;
  }

  private void appendToChunk(int position, int length, long timeUs) {
    if (chunkLength + length > chunk.length) {
      chunk = Arrays.copyOf(chunk, max(chunk.length * 2, chunkLength + length));
    }
    System.arraycopy(packet, position, chunk, chunkLength, length);
    if (chunkUnits == 0) {
      chunkTimeUs = timeUs;
    }
    chunkLength += length;
    chunkUnits++;
  }

  private void flushChunk() {
    if (chunkUnits > 0 && formatEmitted) {
      TrackOutput trackOutput = checkStateNotNull(output);
      chunkBuffer.reset(chunk, chunkLength);
      trackOutput.sampleData(chunkBuffer, chunkLength);
      trackOutput.sampleMetadata(
          chunkTimeUs, C.BUFFER_FLAG_KEY_FRAME, chunkLength, /* offset= */ 0, /* cryptoData= */ null);
    }
    resetChunk();
  }

  private void resetChunk() {
    chunkLength = 0;
    chunkUnits = 0;
    chunkTimeUs = C.TIME_UNSET;
  }

  /** Parsed sample rate and channel count from a TrueHD major sync header. */
  /* package */ static final class MajorSyncInfo {
    public final int sampleRate;
    public final int channelCount;

    public MajorSyncInfo(int sampleRate, int channelCount) {
      this.sampleRate = sampleRate;
      this.channelCount = channelCount;
    }
  }

  /* package */ static boolean isMajorSync(byte[] data, int position, int unitLength) {
    return unitLength >= 12
        && data[position + 4] == (byte) 0xF8
        && data[position + 5] == 0x72
        && data[position + 6] == 0x6F
        && data[position + 7] == (byte) 0xBA;
  }

  /**
   * Parses the TrueHD format info after the major sync word: 4-bit sample rate code, followed by
   * the 6-channel presentation's 5-bit and 8-channel presentation's 13-bit channel arrangement.
   */
  @Nullable
  /* package */ static MajorSyncInfo parseMajorSync(byte[] data, int position) {
    if (position + 12 > data.length) {
      return null;
    }
    long info =
        ((data[position + 8] & 0xFFL) << 24)
            | ((data[position + 9] & 0xFFL) << 16)
            | ((data[position + 10] & 0xFFL) << 8)
            | (data[position + 11] & 0xFFL);
    int rateCode = (int) ((info >>> 28) & 0xF);
    if (rateCode == 0xF) {
      return null;
    }
    int sampleRate = ((rateCode & 8) != 0 ? 44_100 : 48_000) << (rateCode & 7);
    int sixChannel = (int) ((info >>> 15) & 0x1F);
    int eightChannel = (int) (info & 0x1FFF);
    int eightChannelCount = channelCount(eightChannel);
    int channels = eightChannelCount > 0 ? eightChannelCount : channelCount(sixChannel);
    return channels > 0 ? new MajorSyncInfo(sampleRate, channels) : null;
  }

  private static int channelCount(int arrangement) {
    int count = 0;
    for (int bit = 0; bit < CHANNELS_PER_BIT.length; bit++) {
      if (((arrangement >>> bit) & 1) != 0) {
        count += CHANNELS_PER_BIT[bit];
      }
    }
    return count;
  }
}
