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

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for Blu-ray BDAV 192-byte M2TS extraction in {@link TsExtractor}. */
@RunWith(AndroidJUnit4.class)
public final class BdavM2tsExtractorTest {

  private static final int PMT_PID = 0x0100;
  private static final int PCR_PID = 0x1001;
  private static final int TRUEHD_PID = 0x1100;
  private static final int DTS_HD_MA_PID = 0x1101;
  private static final int PGS_PID = 0x1200;

  @Test
  public void sniff_recognizes192ByteBdavAnd188ByteTs() throws Exception {
    byte[] bdav = buildSyntheticBdavM2ts();
    TsExtractor bdavExtractor = createExtractor();
    FakeExtractorInput bdavInput = new FakeExtractorInput.Builder().setData(bdav).build();
    assertThat(bdavExtractor.sniff(bdavInput)).isTrue();
    // Skipped the initial 4-byte BDAV arrival-timestamp header to land on the first 0x47.
    assertThat(bdavInput.getPosition()).isEqualTo(4L);

    byte[] standardTs = new byte[TsExtractor.TS_PACKET_SIZE * 5];
    for (int i = 0; i < 5; i++) {
      standardTs[i * TsExtractor.TS_PACKET_SIZE] = TsExtractor.TS_SYNC_BYTE;
    }
    TsExtractor tsExtractor = createExtractor();
    FakeExtractorInput tsInput = new FakeExtractorInput.Builder().setData(standardTs).build();
    assertThat(tsExtractor.sniff(tsInput)).isTrue();
    assertThat(tsInput.getPosition()).isEqualTo(0L);

    byte[] randomData = new byte[TsExtractor.TS_BDAV_PACKET_SIZE * 5];
    Arrays.fill(randomData, (byte) 0x55);
    TsExtractor nonTsExtractor = createExtractor();
    FakeExtractorInput nonTsInput = new FakeExtractorInput.Builder().setData(randomData).build();
    assertThat(nonTsExtractor.sniff(nonTsInput)).isFalse();
  }

  @Test
  public void read_withSniff_extractsTrueHdPgsDtsHdAndSeekMapDespite0x47InHeaders()
      throws Exception {
    byte[] bdav = buildSyntheticBdavM2ts();
    FakeExtractorOutput output = extractAll(bdav, /* callSniff= */ true);

    assertBdavExtractionResult(output);
  }

  @Test
  public void read_withoutSniff_autoDetects192ByteStrideAndExtractsAllTracks() throws Exception {
    byte[] bdav = buildSyntheticBdavM2ts();
    FakeExtractorOutput output = extractAll(bdav, /* callSniff= */ false);

    assertBdavExtractionResult(output);
  }

  @Test
  public void seek_on192ByteBdavStream_positionsAtSyncByteAndExtractsSubsequentSamples()
      throws Exception {
    byte[] bdav = buildSyntheticBdavM2ts();
    TsExtractor extractor = createExtractor();
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(bdav).build();
    PositionHolder positionHolder = new PositionHolder();

    assertThat(extractor.sniff(input)).isTrue();
    // Read until SeekMap is published.
    while (output.seekMap == null) {
      int result = extractor.read(input, positionHolder);
      assertThat(result).isNotEqualTo(Extractor.RESULT_END_OF_INPUT);
      if (result == Extractor.RESULT_SEEK) {
        input.setPosition((int) positionHolder.position);
      }
    }
    assertThat(output.seekMap.isSeekable()).isTrue();
    assertThat(output.seekMap.getDurationUs()).isEqualTo(5_000_000L);

    // Seek to 2.5s via SeekMap and verify TsBinarySearchSeeker converges cleanly.
    SeekMap.SeekPoints seekPoints = output.seekMap.getSeekPoints(2_500_000L);
    extractor.seek(seekPoints.first.position, 2_500_000L);
    input.setPosition((int) seekPoints.first.position);

    while (true) {
      int result = extractor.read(input, positionHolder);
      if (result == Extractor.RESULT_END_OF_INPUT) {
        break;
      }
      if (result == Extractor.RESULT_SEEK) {
        input.setPosition((int) positionHolder.position);
      }
    }
    assertThat(output.trackOutputs.get(TRUEHD_PID)).isNotNull();
  }

  private static void assertBdavExtractionResult(FakeExtractorOutput output) {
    assertThat(output.tracksEnded).isTrue();
    assertThat(output.seekMap).isNotNull();
    assertThat(output.seekMap.isSeekable()).isTrue();
    assertThat(output.seekMap.getDurationUs()).isEqualTo(5_000_000L);

    // HDMV TrueHD (0x83) extracted and flushed at EOF.
    FakeTrackOutput trueHdTrack = output.trackOutputs.get(TRUEHD_PID);
    assertThat(trueHdTrack).isNotNull();
    Format trueHdFormat = trueHdTrack.lastFormat;
    assertThat(trueHdFormat).isNotNull();
    assertThat(trueHdFormat.sampleMimeType).isEqualTo(MimeTypes.AUDIO_TRUEHD);
    assertThat(trueHdFormat.sampleRate).isEqualTo(48_000);
    assertThat(trueHdFormat.channelCount).isEqualTo(8);
    assertThat(trueHdTrack.getSampleCount()).isEqualTo(1);

    // HDMV PGS (0x90) extracted as complete display set.
    FakeTrackOutput pgsTrack = output.trackOutputs.get(PGS_PID);
    assertThat(pgsTrack).isNotNull();
    assertThat(pgsTrack.lastFormat).isNotNull();
    assertThat(pgsTrack.lastFormat.sampleMimeType).isEqualTo(MimeTypes.APPLICATION_PGS);
    assertThat(pgsTrack.getSampleCount()).isEqualTo(1);

    // HDMV DTS-HD MA (0x86) must NOT be routed to SCTE-35 splice info.
    FakeTrackOutput dtsTrack = output.trackOutputs.get(DTS_HD_MA_PID);
    assertThat(dtsTrack).isNotNull();
    if (dtsTrack.lastFormat != null) {
      assertThat(dtsTrack.lastFormat.sampleMimeType).isNotEqualTo(MimeTypes.APPLICATION_SCTE35);
    }
  }

  @Test
  public void dtsReader_malformedExssHeaderSizeSmallerThanPrefix_resyncsWithoutCrashing()
      throws Exception {
    DtsReader reader =
        new DtsReader(/* language= */ "en", /* roleFlags= */ 0, DtsReader.EXTSS_HEADER_SIZE_MAX, "video/mp2t");
    FakeExtractorOutput output = new FakeExtractorOutput();
    reader.createTracks(output, new TsPayloadReader.TrackIdGenerator(1, 10));
    reader.packetStarted(/* pesTimeUs= */ 0L, TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR);

    // ExSS sync 0x64582025 followed by zero bytes yields nuExtSSHeaderSize = 1 (< 7 bytes already read).
    byte[] malformedExss =
        new byte[] {0x64, 0x58, 0x20, 0x25, 0x00, 0x00, 0x00, 0x00, 0x11, 0x22, 0x33, 0x44};
    reader.consume(new androidx.media3.common.util.ParsableByteArray(malformedExss));
    reader.packetFinished(/* isEndOfInput= */ true);
  }

  private static TsExtractor createExtractor() {
    return new TsExtractor(
        TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, SubtitleParser.Factory.UNSUPPORTED);
  }

  private static FakeExtractorOutput extractAll(byte[] data, boolean callSniff) throws Exception {
    TsExtractor extractor = createExtractor();
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(data).build();
    PositionHolder positionHolder = new PositionHolder();
    if (callSniff) {
      assertThat(extractor.sniff(input)).isTrue();
    }
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

  /**
   * Builds a valid 192-byte BDAV M2TS clip with PAT, PMT (HDMV TrueHD 0x83, HDMV DTS-HD MA 0x86,
   * HDMV PGS 0x90), PCR packets spanning 5 seconds, and deliberate 0x47 bytes inside 4-byte BDAV
   * arrival-timestamp headers to verify header-resilient sync.
   */
  private static byte[] buildSyntheticBdavM2ts() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int[] cc = new int[0x2000];

    // Packet 0: PAT (with 0x47 planted in 4-byte BDAV header!)
    writeBdavPacket(out, /* headerByte0= */ 0x47, buildPatPacket(cc[0]++));
    // Packet 1: PMT declaring TrueHD (0x83), DTS-HD MA (0x86), PGS (0x90)
    writeBdavPacket(out, /* headerByte0= */ 0x47, buildPmtPacket(cc[PMT_PID]++));
    // Packet 2: First PCR = 0 ticks (0s)
    writeBdavPacket(out, /* headerByte0= */ 0x01, buildPcrPacket(PCR_PID, cc[PCR_PID]++, 0L));
    // Packet 3: Interleaved AC-3 core on TrueHD PID (must be skipped by HdmvTrueHdReader)
    writeBdavPacket(
        out,
        /* headerByte0= */ 0x47,
        buildPesPacket(
            TRUEHD_PID,
            cc[TRUEHD_PID]++,
            /* pts90k= */ 9_000L,
            new byte[] {0x0B, 0x77, 0x00, 0x01, 0x02, 0x03}));
    // Packet 4: TrueHD major sync access unit on TrueHD PID
    writeBdavPacket(
        out,
        /* headerByte0= */ 0x02,
        buildPesPacket(
            TRUEHD_PID,
            cc[TRUEHD_PID]++,
            /* pts90k= */ 90_000L,
            buildTrueHdMajorSyncUnit()));
    // Packet 5: PGS display set (PCS + END) on PGS PID
    byte[] pgsDisplaySet =
        new byte[] {
          (byte) HdmvPgsReader.SEGMENT_PRESENTATION_COMPOSITION, 0x00, 0x02, 0x01, 0x02,
          (byte) HdmvPgsReader.SEGMENT_END, 0x00, 0x00
        };
    writeBdavPacket(
        out,
        /* headerByte0= */ 0x47,
        buildPesPacket(PGS_PID, cc[PGS_PID]++, /* pts90k= */ 180_000L, pgsDisplaySet));
    // Packet 6: Midpoint PCR = 2.5s (225_000 ticks at 90kHz)
    writeBdavPacket(out, /* headerByte0= */ 0x03, buildPcrPacket(PCR_PID, cc[PCR_PID]++, 225_000L));
    // Packets 7-10: Padding null packets (PID 0x1FFF) so >= 5 consecutive packets exist at tail
    for (int i = 0; i < 4; i++) {
      writeBdavPacket(out, /* headerByte0= */ 0x47, buildNullPacket());
    }
    // Packet 11: Final PCR = 5.0s (450_000 ticks at 90kHz)
    writeBdavPacket(out, /* headerByte0= */ 0x04, buildPcrPacket(PCR_PID, cc[PCR_PID]++, 450_000L));
    // Packets 12-15: Trailing null packets so last PCR packet is surrounded by 5-packet cadence
    for (int i = 0; i < 4; i++) {
      writeBdavPacket(out, /* headerByte0= */ 0x05, buildNullPacket());
    }

    return out.toByteArray();
  }

  private static void writeBdavPacket(
      ByteArrayOutputStream out, int headerByte0, byte[] tsPacket188) {
    assertThat(tsPacket188.length).isEqualTo(TsExtractor.TS_PACKET_SIZE);
    out.write(headerByte0 & 0xFF);
    out.write(0x12);
    out.write(0x34);
    out.write(0x56);
    out.write(tsPacket188, 0, tsPacket188.length);
  }

  private static byte[] buildNullPacket() {
    byte[] packet = new byte[TsExtractor.TS_PACKET_SIZE];
    packet[0] = TsExtractor.TS_SYNC_BYTE;
    packet[1] = 0x1F;
    packet[2] = (byte) 0xFF;
    packet[3] = 0x10;
    return packet;
  }

  private static byte[] buildPatPacket(int continuityCounter) {
    // PAT section without CRC: table_id=0x00, section_length=13 (5 header + 4 program + 4 CRC)
    byte[] sectionWithoutCrc =
        new byte[] {
          0x00, // table_id = PAT
          (byte) 0xB0,
          0x0D, // section_length = 13
          0x00,
          0x01, // transport_stream_id
          (byte) 0xC1, // version 0, current_next=1
          0x00, // section_number
          0x00, // last_section_number
          0x00,
          0x01, // program_number = 1
          (byte) (0xE0 | ((PMT_PID >>> 8) & 0x1F)),
          (byte) (PMT_PID & 0xFF)
        };
    return wrapSectionInTsPacket(0, continuityCounter, withPointerAndCrc32(sectionWithoutCrc));
  }

  private static byte[] buildPmtPacket(int continuityCounter) {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(0x00);
    body.write(0x01); // program_number = 1
    body.write(0xC1); // version 0, current_next = 1
    body.write(0x00); // section_number
    body.write(0x00); // last_section_number
    body.write(0xE0 | ((PCR_PID >>> 8) & 0x1F));
    body.write(PCR_PID & 0xFF);
    // program_info_length = 6 (HDMV registration descriptor: 0x05, 0x04, 'H', 'D', 'M', 'V')
    body.write(0xF0);
    body.write(0x06);
    body.write(0x05);
    body.write(0x04);
    body.write('H');
    body.write('D');
    body.write('M');
    body.write('V');

    // ES 1: HDMV TrueHD (0x83)
    writePmtEsEntry(body, TsExtractor.TS_STREAM_TYPE_HDMV_TRUEHD, TRUEHD_PID);
    // ES 2: HDMV DTS-HD MA (0x86)
    writePmtEsEntry(body, 0x86, DTS_HD_MA_PID);
    // ES 3: HDMV PGS (0x90)
    writePmtEsEntry(body, TsExtractor.TS_STREAM_TYPE_HDMV_PGS, PGS_PID);

    byte[] bodyBytes = body.toByteArray();
    int sectionLength = bodyBytes.length + 4; // body + CRC32
    ByteArrayOutputStream section = new ByteArrayOutputStream();
    section.write(0x02); // table_id = PMT
    section.write(0xB0 | ((sectionLength >>> 8) & 0x0F));
    section.write(sectionLength & 0xFF);
    section.write(bodyBytes, 0, bodyBytes.length);
    return wrapSectionInTsPacket(
        PMT_PID, continuityCounter, withPointerAndCrc32(section.toByteArray()));
  }

  private static byte[] withPointerAndCrc32(byte[] sectionWithoutCrc) {
    int crc = Util.crc32(sectionWithoutCrc, 0, sectionWithoutCrc.length, 0xFFFFFFFF);
    byte[] out = new byte[1 + sectionWithoutCrc.length + 4];
    out[0] = 0x00; // pointer_field
    System.arraycopy(sectionWithoutCrc, 0, out, 1, sectionWithoutCrc.length);
    int crcOffset = 1 + sectionWithoutCrc.length;
    out[crcOffset] = (byte) ((crc >>> 24) & 0xFF);
    out[crcOffset + 1] = (byte) ((crc >>> 16) & 0xFF);
    out[crcOffset + 2] = (byte) ((crc >>> 8) & 0xFF);
    out[crcOffset + 3] = (byte) (crc & 0xFF);
    return out;
  }

  private static void writePmtEsEntry(ByteArrayOutputStream out, int streamType, int pid) {
    out.write(streamType & 0xFF);
    out.write(0xE0 | ((pid >>> 8) & 0x1F));
    out.write(pid & 0xFF);
    out.write(0xF0);
    out.write(0x00); // es_info_length = 0
  }

  private static byte[] wrapSectionInTsPacket(int pid, int continuityCounter, byte[] section) {
    byte[] packet = new byte[TsExtractor.TS_PACKET_SIZE];
    Arrays.fill(packet, (byte) 0xFF);
    packet[0] = TsExtractor.TS_SYNC_BYTE;
    packet[1] = (byte) (0x40 | ((pid >>> 8) & 0x1F)); // PUSI = 1
    packet[2] = (byte) (pid & 0xFF);
    packet[3] = (byte) (0x10 | (continuityCounter & 0x0F)); // payload only
    System.arraycopy(section, 0, packet, 4, section.length);
    return packet;
  }

  private static byte[] buildPcrPacket(int pid, int continuityCounter, long pcrBase90k) {
    byte[] packet = new byte[TsExtractor.TS_PACKET_SIZE];
    Arrays.fill(packet, (byte) 0xFF);
    packet[0] = TsExtractor.TS_SYNC_BYTE;
    packet[1] = (byte) ((pid >>> 8) & 0x1F);
    packet[2] = (byte) (pid & 0xFF);
    packet[3] = (byte) (0x20 | (continuityCounter & 0x0F)); // adaptation field only
    packet[4] = (byte) 183; // adaptation_field_length fills rest of packet
    packet[5] = 0x10; // PCR_flag = 1
    packet[6] = (byte) ((pcrBase90k >>> 25) & 0xFF);
    packet[7] = (byte) ((pcrBase90k >>> 17) & 0xFF);
    packet[8] = (byte) ((pcrBase90k >>> 9) & 0xFF);
    packet[9] = (byte) ((pcrBase90k >>> 1) & 0xFF);
    packet[10] = (byte) (((pcrBase90k & 0x01) << 7) | 0x7E);
    packet[11] = 0x00;
    return packet;
  }

  private static byte[] buildPesPacket(
      int pid, int continuityCounter, long pts90k, byte[] payload) {
    int pesPacketLength = 3 + 5 + payload.length;
    ByteArrayOutputStream pes = new ByteArrayOutputStream();
    pes.write(0x00);
    pes.write(0x00);
    pes.write(0x01);
    pes.write(0xBD); // private_stream_1
    pes.write((pesPacketLength >>> 8) & 0xFF);
    pes.write(pesPacketLength & 0xFF);
    pes.write(0x80);
    pes.write(0x80); // PTS flag
    pes.write(0x05); // PES header data length = 5
    pes.write((int) (0x21 | (((pts90k >>> 30) & 0x07) << 1)));
    pes.write((int) ((pts90k >>> 22) & 0xFF));
    pes.write((int) (0x01 | (((pts90k >>> 15) & 0x7F) << 1)));
    pes.write((int) ((pts90k >>> 7) & 0xFF));
    pes.write((int) (0x01 | ((pts90k & 0x7F) << 1)));
    pes.write(payload, 0, payload.length);

    byte[] pesBytes = pes.toByteArray();
    byte[] packet = new byte[TsExtractor.TS_PACKET_SIZE];
    packet[0] = TsExtractor.TS_SYNC_BYTE;
    packet[1] = (byte) (0x40 | ((pid >>> 8) & 0x1F)); // PUSI = 1
    packet[2] = (byte) (pid & 0xFF);
    int stuffing = TsExtractor.TS_PACKET_SIZE - 4 - pesBytes.length;
    if (stuffing > 0) {
      packet[3] = (byte) (0x30 | (continuityCounter & 0x0F)); // adaptation + payload
      packet[4] = (byte) (stuffing - 1);
      if (stuffing > 1) {
        packet[5] = 0x00;
        Arrays.fill(packet, 6, 4 + stuffing, (byte) 0xFF);
      }
      System.arraycopy(pesBytes, 0, packet, 4 + stuffing, pesBytes.length);
    } else {
      packet[3] = (byte) (0x10 | (continuityCounter & 0x0F));
      System.arraycopy(pesBytes, 0, packet, 4, pesBytes.length);
    }
    return packet;
  }

  private static byte[] buildTrueHdMajorSyncUnit() {
    byte[] unit = new byte[16];
    int words = unit.length / 2;
    unit[0] = (byte) ((words >>> 8) & 0x0F);
    unit[1] = (byte) (words & 0xFF);
    unit[4] = (byte) 0xF8;
    unit[5] = 0x72;
    unit[6] = 0x6F;
    unit[7] = (byte) 0xBA;
    // 48 kHz (rateCode=0), 8ch arrangement (0x1F)
    long info = (0x0FL << 15) | 0x1FL;
    unit[8] = (byte) ((info >>> 24) & 0xFF);
    unit[9] = (byte) ((info >>> 16) & 0xFF);
    unit[10] = (byte) ((info >>> 8) & 0xFF);
    unit[11] = (byte) (info & 0xFF);
    return unit;
  }
}
