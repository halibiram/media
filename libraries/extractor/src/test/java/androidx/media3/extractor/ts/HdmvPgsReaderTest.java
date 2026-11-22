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
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link HdmvPgsReader}. */
@RunWith(AndroidJUnit4.class)
public final class HdmvPgsReaderTest {

  @Test
  public void consume_reassemblesDisplaySetAcrossPacketsAndEmitsAtStartPts() {
    FakeExtractorOutput output = new FakeExtractorOutput();
    HdmvPgsReader reader = new HdmvPgsReader("tur", C.ROLE_FLAG_SUBTITLE, MimeTypes.VIDEO_MP2T);
    reader.createTracks(output, new TsPayloadReader.TrackIdGenerator(1, 200));

    byte[] pcs = pgsSegment(HdmvPgsReader.SEGMENT_PRESENTATION_COMPOSITION, new byte[] {1, 2, 3, 4});
    byte[] ods = pgsSegment(0x15, new byte[] {10, 20, 30, 40, 50});
    byte[] end = pgsSegment(HdmvPgsReader.SEGMENT_END, new byte[0]);

    // Split ODS across two PES packets to verify cross-packet segment reassembly.
    byte[] packet1 = concat(pcs, Arrays.copyOfRange(ods, 0, 4));
    byte[] packet2 = concat(Arrays.copyOfRange(ods, 4, ods.length), end);

    feedPacket(reader, 1_500_000L, packet1);
    FakeTrackOutput trackOutput = output.trackOutputs.get(1);
    assertThat(trackOutput.getSampleCount()).isEqualTo(0);

    feedPacket(reader, 1_520_000L, packet2);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(1_500_000L);
    assertThat(trackOutput.getSampleData(0)).isEqualTo(concat(pcs, ods, end));

    Format format = trackOutput.lastFormat;
    assertThat(format).isNotNull();
    assertThat(format.sampleMimeType).isEqualTo(MimeTypes.APPLICATION_PGS);
    assertThat(format.language).isEqualTo("tr");
  }

  @Test
  public void consume_dropsOrphanSegmentsBeforeFirstCompositionAndIncompleteSets() {
    FakeExtractorOutput output = new FakeExtractorOutput();
    HdmvPgsReader reader = new HdmvPgsReader(null, 0, MimeTypes.VIDEO_MP2T);
    reader.createTracks(output, new TsPayloadReader.TrackIdGenerator(1, 200));

    byte[] orphanOds = pgsSegment(0x15, new byte[] {9, 9});
    byte[] orphanEnd = pgsSegment(HdmvPgsReader.SEGMENT_END, new byte[0]);
    feedPacket(reader, 500_000L, concat(orphanOds, orphanEnd));

    FakeTrackOutput trackOutput = output.trackOutputs.get(1);
    assertThat(trackOutput.getSampleCount()).isEqualTo(0);

    // Start set 1 without end segment, then start set 2 with end segment -> only set 2 emitted.
    byte[] set1Pcs = pgsSegment(HdmvPgsReader.SEGMENT_PRESENTATION_COMPOSITION, new byte[] {1});
    byte[] set2Pcs = pgsSegment(HdmvPgsReader.SEGMENT_PRESENTATION_COMPOSITION, new byte[] {2});
    byte[] set2End = pgsSegment(HdmvPgsReader.SEGMENT_END, new byte[0]);
    feedPacket(reader, 2_000_000L, set1Pcs);
    feedPacket(reader, 3_000_000L, concat(set2Pcs, set2End));

    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(3_000_000L);
    assertThat(trackOutput.getSampleData(0)).isEqualTo(concat(set2Pcs, set2End));
  }

  private static void feedPacket(HdmvPgsReader reader, long timeUs, byte[] data) {
    reader.packetStarted(timeUs, /* flags= */ 0);
    reader.consume(new ParsableByteArray(data));
    reader.packetFinished(/* isEndOfInput= */ false);
  }

  private static byte[] pgsSegment(int type, byte[] payload) {
    byte[] segment = new byte[3 + payload.length];
    segment[0] = (byte) type;
    segment[1] = (byte) ((payload.length >>> 8) & 0xFF);
    segment[2] = (byte) (payload.length & 0xFF);
    System.arraycopy(payload, 0, segment, 3, payload.length);
    return segment;
  }

  private static byte[] concat(byte[]... arrays) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] array : arrays) {
      out.write(array, 0, array.length);
    }
    return out.toByteArray();
  }
}
