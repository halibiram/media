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

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.Ac3Util;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link HdmvTrueHdReader}. */
@RunWith(AndroidJUnit4.class)
public final class HdmvTrueHdReaderTest {

  @Test
  public void parseMajorSync_parses48kHz8ChannelAnd6ChannelLayouts() {
    // Rate code 0 -> 48000 Hz; 8ch arrangement bits 0,1,2,3,4 -> 2+1+1+2+2 = 8 channels.
    byte[] sync8ch = buildMajorSyncUnit(/* unitLength= */ 16, /* rateCode= */ 0, 0x0F, 0x1F);
    HdmvTrueHdReader.MajorSyncInfo info8ch = HdmvTrueHdReader.parseMajorSync(sync8ch, 0);
    assertThat(info8ch).isNotNull();
    assertThat(info8ch.sampleRate).isEqualTo(48_000);
    assertThat(info8ch.channelCount).isEqualTo(8);

    // Rate code 1 -> 96000 Hz; 8ch arrangement = 0, 6ch arrangement bits 0,1,2,3 -> 2+1+1+2 = 6ch.
    byte[] sync6ch = buildMajorSyncUnit(/* unitLength= */ 16, /* rateCode= */ 1, 0x0F, 0x00);
    HdmvTrueHdReader.MajorSyncInfo info6ch = HdmvTrueHdReader.parseMajorSync(sync6ch, 0);
    assertThat(info6ch).isNotNull();
    assertThat(info6ch.sampleRate).isEqualTo(96_000);
    assertThat(info6ch.channelCount).isEqualTo(6);
  }

  @Test
  public void consume_skipsInterleavedAc3CoreAndRechunksTrueHdUnits() {
    FakeExtractorOutput output = new FakeExtractorOutput();
    HdmvTrueHdReader reader = new HdmvTrueHdReader("eng", C.ROLE_FLAG_MAIN, MimeTypes.VIDEO_MP2T);
    reader.createTracks(output, new TsPayloadReader.TrackIdGenerator(1, 100));

    // Leading non-major-sync TrueHD unit before first major sync is ignored.
    feedPacket(reader, 1_000L, buildMinorUnit(8), /* isEndOfInput= */ false);
    // Interleaved AC-3 core frame (0x0B 0x77) is ignored.
    feedPacket(reader, 2_000L, new byte[] {0x0B, 0x77, 0x00, 0x01, 0x02, 0x03}, false);

    // Feed 16 units (1 major sync + 15 minor units) -> should emit 1 rechunked sample.
    byte[] majorSync = buildMajorSyncUnit(/* unitLength= */ 16, /* rateCode= */ 0, 0x0F, 0x1F);
    byte[] minorUnit = buildMinorUnit(8);
    ByteArrayOutputStream sixteenUnits = new ByteArrayOutputStream();
    sixteenUnits.write(majorSync, 0, majorSync.length);
    for (int i = 1; i < Ac3Util.TRUEHD_RECHUNK_SAMPLE_COUNT; i++) {
      sixteenUnits.write(minorUnit, 0, minorUnit.length);
    }
    byte[] firstChunkPayload = sixteenUnits.toByteArray();
    feedPacket(reader, 10_000L, firstChunkPayload, /* isEndOfInput= */ false);

    FakeTrackOutput trackOutput = output.trackOutputs.get(1);
    Format format = trackOutput.lastFormat;
    assertThat(format).isNotNull();
    assertThat(format.sampleMimeType).isEqualTo(MimeTypes.AUDIO_TRUEHD);
    assertThat(format.sampleRate).isEqualTo(48_000);
    assertThat(format.channelCount).isEqualTo(8);
    assertThat(format.language).isEqualTo("en");
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(10_000L);
    assertThat(trackOutput.getSampleData(0)).isEqualTo(firstChunkPayload);

    // Feed 2 trailing units and finish stream -> flushed on EOF.
    ByteArrayOutputStream trailingUnits = new ByteArrayOutputStream();
    trailingUnits.write(minorUnit, 0, minorUnit.length);
    trailingUnits.write(minorUnit, 0, minorUnit.length);
    feedPacket(reader, 25_000L, trailingUnits.toByteArray(), /* isEndOfInput= */ true);

    assertThat(trackOutput.getSampleCount()).isEqualTo(2);
    assertThat(trackOutput.getSampleTimeUs(1)).isEqualTo(25_000L);
    assertThat(trackOutput.getSampleData(1)).isEqualTo(trailingUnits.toByteArray());
  }

  @Test
  public void seek_clearsPartialChunkAndWaitsForNextMajorSync() {
    FakeExtractorOutput output = new FakeExtractorOutput();
    HdmvTrueHdReader reader = new HdmvTrueHdReader(null, 0, MimeTypes.VIDEO_MP2T);
    reader.createTracks(output, new TsPayloadReader.TrackIdGenerator(1, 100));

    byte[] majorSync = buildMajorSyncUnit(16, 0, 0x0F, 0x1F);
    byte[] minorUnit = buildMinorUnit(8);
    feedPacket(reader, 10_000L, majorSync, /* isEndOfInput= */ false);
    reader.seek();

    // Minor unit after seek must be dropped until a major sync arrives.
    feedPacket(reader, 50_000L, minorUnit, /* isEndOfInput= */ true);
    FakeTrackOutput trackOutput = output.trackOutputs.get(1);
    assertThat(trackOutput.getSampleCount()).isEqualTo(0);

    // Major sync after seek re-locks and emits on EOF.
    feedPacket(reader, 60_000L, majorSync, /* isEndOfInput= */ true);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(60_000L);
  }

  private static void feedPacket(
      HdmvTrueHdReader reader, long timeUs, byte[] data, boolean isEndOfInput) {
    reader.packetStarted(timeUs, /* flags= */ 0);
    reader.consume(new ParsableByteArray(data));
    reader.packetFinished(isEndOfInput);
  }

  private static byte[] buildMajorSyncUnit(
      int unitLength, int rateCode, int sixChannelMask, int eightChannelMask) {
    byte[] unit = new byte[unitLength];
    int words = unitLength / 2;
    unit[0] = (byte) ((words >>> 8) & 0x0F);
    unit[1] = (byte) (words & 0xFF);
    // TrueHD major sync word: 0xF8 0x72 0x6F 0xBA
    unit[4] = (byte) 0xF8;
    unit[5] = 0x72;
    unit[6] = 0x6F;
    unit[7] = (byte) 0xBA;
    long info =
        ((rateCode & 0xFL) << 28) | ((sixChannelMask & 0x1FL) << 15) | (eightChannelMask & 0x1FFFL);
    unit[8] = (byte) ((info >>> 24) & 0xFF);
    unit[9] = (byte) ((info >>> 16) & 0xFF);
    unit[10] = (byte) ((info >>> 8) & 0xFF);
    unit[11] = (byte) (info & 0xFF);
    return unit;
  }

  private static byte[] buildMinorUnit(int unitLength) {
    byte[] unit = new byte[unitLength];
    int words = unitLength / 2;
    unit[0] = (byte) ((words >>> 8) & 0x0F);
    unit[1] = (byte) (words & 0xFF);
    unit[4] = 0x12;
    unit[5] = 0x34;
    return unit;
  }
}
