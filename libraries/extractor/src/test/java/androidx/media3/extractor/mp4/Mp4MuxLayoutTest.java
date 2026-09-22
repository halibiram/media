/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.mp4;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import androidx.media3.common.MimeTypes;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Playback behavior for the unfragmented MP4 layouts that change how {@link Mp4Extractor} walks the
 * file.
 *
 * <p>Codec-specific samples live in {@link Mp4ExtractorParameterizedTest}. Fragmented MP4
 * ({@code moof}/{@code mdat}) lives in {@link FragmentedMp4ExtractorParameterizedTest}.
 */
@RunWith(AndroidJUnit4.class)
public final class Mp4MuxLayoutTest {

  private static final int RELOAD_BYTES = 256 * 1024;
  private static final byte[] VIDEO_BYTES = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
  private static final byte[] AUDIO_BYTES = new byte[] {9, 8, 7, 6, 5, 4, 3, 2};

  @Test
  public void fastStart_interleavedAudioVideoAndInBandText_readsWithoutReloading() throws Exception {
    Mux mux = new Mux();
    Track video = mux.addVideo();
    Track audio = mux.addAudio();
    Track text = mux.addText();
    for (int i = 0; i < 4; i++) {
      mux.place(video, VIDEO_BYTES, 1000);
      mux.place(audio, AUDIO_BYTES, 1000);
      mux.place(text, tx3gCue("cue" + i), 1000);
    }

    byte[] file = mux.build();
    assertSniffs(file);
    Play play = play(file);

    assertThat(play.indexSeeks).isEqualTo(0);
    assertThat(play.prefetchedTextSamples).isEqualTo(0);
    assertThat(play.playbackReloads).isEqualTo(0);
    assertThat(play.videoSamples).isEqualTo(4);
    assertThat(play.audioSamples).isEqualTo(4);
    assertThat(play.textSamples).isEqualTo(4);
  }

  @Test
  public void moovAfter32BitMdat_opensAndReadsSamples() throws Exception {
    Mux mux = new Mux().moovAtEnd();
    mux.videoSamples(6);
    mux.gap(RELOAD_BYTES);

    byte[] file = mux.build();
    assertThat(fourcc(file, indexOf(file, "mdat"))).isEqualTo("mdat");
    assertSniffs(file);
    Play play = play(file);

    assertThat(play.indexSeeks).isGreaterThan(0);
    assertThat(play.indexSeeks).isLessThan(8);
    assertThat(play.playbackReloads).isEqualTo(0);
    assertThat(play.videoSamples).isEqualTo(6);
    assertThat(play.seekMap.getDurationUs()).isGreaterThan(0);
  }

  @Test
  public void moovAfter64BitMdat_opensWithoutScanningMedia() throws Exception {
    Mux mux = new Mux().moovAtEnd().mdat64();
    mux.videoSamples(4);
    mux.gap(8 * 1024);

    byte[] file = mux.build();
    int mdat = indexOf(file, "mdat");
    assertThat(read32(file, mdat - 4)).isEqualTo(1);
    assertSniffs(file);
    Play play = play(file);

    assertThat(play.indexSeeks).isLessThan(8);
    assertThat(play.playbackReloads).isEqualTo(0);
    assertThat(play.videoSamples).isEqualTo(4);
  }

  @Test
  public void largeFreeAtomBeforeMdat_stillSniffsAndSeeksOverThePadding() throws Exception {
    Mux mux = new Mux().moovAtEnd().freeBeforeMdat(300 * 1024);
    mux.videoSamples(3);

    byte[] file = mux.build();
    assertSniffs(file);
    Play play = play(file);

    assertThat(play.indexSeeks).isGreaterThan(0);
    assertThat(play.playbackReloads).isEqualTo(0);
    assertThat(play.videoSamples).isEqualTo(3);
  }

  @Test
  public void wideAtomAndEmptyMdatBeforeTrailingMoov_areSkipped() throws Exception {
    Mux mux = new Mux().moovAtEnd().wideBeforeMdat().emptyMdatBeforeMoov().freeBeforeMoov(8);
    mux.videoSamples(3);
    mux.gap(RELOAD_BYTES);

    byte[] file = mux.build();
    assertSniffs(file);
    Play play = play(file);

    assertThat(play.playbackReloads).isEqualTo(0);
    assertThat(play.videoSamples).isEqualTo(3);
  }

  @Test
  public void mdatExtendingToEndOfFile_withMoovFirst_readsSamples() throws Exception {
    Mux mux = new Mux().mdatExtendsToEnd();
    mux.videoSamples(5);

    byte[] file = mux.build();
    int mdat = indexOf(file, "mdat");
    assertThat(read32(file, mdat - 4)).isEqualTo(0);
    assertSniffs(file);
    Play play = play(file);

    assertThat(play.playbackReloads).isEqualTo(0);
    assertThat(play.videoSamples).isEqualTo(5);
  }

  @Test
  public void co64ChunkOffsets_roundTrip() throws Exception {
    Mux mux = new Mux().moovAtEnd().forceCo64();
    mux.videoSamples(4);

    byte[] file = mux.build();
    assertThat(indexOf(file, "co64")).isGreaterThan(0);
    assertThat(indexOf(file, "stco")).isEqualTo(-1);
    Play play = play(file);

    assertThat(play.playbackReloads).isEqualTo(0);
    assertThat(play.videoSamples).isEqualTo(4);
  }

  @Test
  public void severalSamplesPerChunk_readInOrder() throws Exception {
    Mux mux = new Mux().samplesPerChunk(4);
    Track video = mux.addVideo();
    for (int i = 0; i < 8; i++) {
      mux.place(video, VIDEO_BYTES, 1000);
    }

    Play play = play(mux.build());

    assertThat(play.playbackReloads).isEqualTo(0);
    assertThat(play.videoSamples).isEqualTo(8);
  }

  @Test
  public void twoMdatAtoms_bothTracksAreRead() throws Exception {
    Mux mux = new Mux().moovAtEnd();
    Track video = mux.addVideo();
    Track audio = mux.addAudio();
    for (int i = 0; i < 4; i++) {
      mux.place(video, VIDEO_BYTES, 1000);
    }
    mux.gap(RELOAD_BYTES);
    mux.nextMdat();
    for (int i = 0; i < 4; i++) {
      mux.place(audio, AUDIO_BYTES, 1000);
    }
    mux.gap(RELOAD_BYTES);

    Play play = play(mux.build());

    assertThat(play.indexSeeks).isAtLeast(2);
    assertThat(play.videoSamples).isEqualTo(4);
    assertThat(play.audioSamples).isEqualTo(4);
  }

  @Test
  public void nonInterleavedAudioAfterVideo_stillFinishesBothTracks() throws Exception {
    Mux mux = new Mux().moovAtEnd();
    Track video = mux.addVideo();
    Track audio = mux.addAudio();
    for (int i = 0; i < 4; i++) {
      mux.place(video, VIDEO_BYTES, 1000);
    }
    mux.gap(2 * 1024 * 1024);
    for (int i = 0; i < 4; i++) {
      mux.place(audio, AUDIO_BYTES, 1000);
    }

    Play play = play(mux.build());

    assertThat(play.playbackReloads).isGreaterThan(0);
    assertThat(play.videoSamples).isEqualTo(4);
    assertThat(play.audioSamples).isEqualTo(4);
  }

  @Test
  public void originalQuickTimeWithoutFtyp_mdatThenMoov_opens() throws Exception {
    Mux mux = new Mux().omitFtyp().moovAtEnd();
    mux.videoSamples(3);
    mux.gap(8 * 1024);

    byte[] file = mux.build();
    assertSniffs(file);
    Play play = play(file);

    assertThat(play.videoSamples).isEqualTo(3);
    assertThat(play.playbackReloads).isEqualTo(0);
  }

  @Test
  public void quickTimeBrand_sniffsAndPlays() throws Exception {
    Mux mux = new Mux().brand("qt  ");
    mux.videoSamples(3);

    byte[] file = mux.build();
    assertSniffs(file);
    assertThat(play(file).videoSamples).isEqualTo(3);
  }

  @Test
  public void compatibleBrands_sniffAndPlay() throws Exception {
    String[] brands =
        new String[] {
          "isom", "iso2", "iso5", "iso6", "mp41", "mp42", "avc1", "hvc1", "hev1", "av01", "M4V ",
          "M4A ", "f4v ", "dby1", "3gp4", "3gp6", "piff", "isml"
        };
    for (String brand : brands) {
      Mux mux = new Mux().brand(brand);
      mux.videoSamples(2);
      byte[] file = mux.build();
      assertWithMessage(brand).that(Sniffer.sniffUnfragmented(input(file), /* acceptHeic= */ false))
          .isNull();
      assertWithMessage(brand).that(play(file).videoSamples).isEqualTo(2);
    }
  }

  @Test
  public void heicBrand_isLeftToTheHeicSniffer() throws Exception {
    Mux mux = new Mux().brand("heic");
    mux.videoSamples(1);
    byte[] file = mux.build();

    assertThat(Sniffer.sniffUnfragmented(input(file), /* acceptHeic= */ false)).isNotNull();
    assertThat(Sniffer.sniffUnfragmented(input(file), /* acceptHeic= */ true)).isNull();
  }

  @Test
  public void unknownBrand_isRejectedByTheSniffer() throws Exception {
    Mux mux = new Mux().brand("xxxx").noCompatibleBrand();
    mux.videoSamples(1);

    assertThat(Sniffer.sniffUnfragmented(input(mux.build()), /* acceptHeic= */ false)).isNotNull();
  }

  @Test
  public void editListCoveringTheWholeTrack_keepsEverySample() throws Exception {
    Mux mux = new Mux();
    Track video = mux.addVideo().withEditList();
    for (int i = 0; i < 4; i++) {
      mux.place(video, VIDEO_BYTES, 1000);
    }

    Play play = play(mux.build());

    assertThat(play.videoSamples).isEqualTo(4);
    assertThat(play.playbackReloads).isEqualTo(0);
  }

  @Test
  public void clusteredTx3gAtEndOfFile_isPrefetchedAndDoesNotReloadDuringPlayback()
      throws Exception {
    Mux mux = new Mux().moovAtEnd().mdat64();
    Track video = mux.addVideo();
    Track audio = mux.addAudio();
    Track text = mux.addText();
    for (int i = 0; i < 4; i++) {
      mux.place(video, VIDEO_BYTES, 1000);
      mux.place(audio, AUDIO_BYTES, 1000);
    }
    mux.gap(2 * 1024 * 1024 + 4096);
    for (int i = 0; i < 12; i++) {
      mux.place(text, i % 3 == 0 ? emptyTx3g() : tx3gCue("end" + i), 1000);
    }

    Play play = play(mux.build());

    assertThat(play.prefetchedTextSamples).isEqualTo(12);
    assertThat(play.prefetchSeeks).isGreaterThan(0);
    assertThat(play.prefetchSeeks).isAtMost(4);
    assertThat(play.playbackReloads).isEqualTo(0);
    assertThat(play.videoSamples).isEqualTo(4);
    assertThat(play.audioSamples).isEqualTo(4);
    assertThat(play.textSamples).isEqualTo(12);
  }

  @Test
  public void thirtyTwoOutOfBandTextRuns_areStillPrefetched() throws Exception {
    Play play = play(scatteredText(/* runs= */ 32, /* empty= */ false));

    assertThat(play.prefetchedTextSamples).isEqualTo(32);
    assertThat(play.prefetchSeeks).isAtMost(32);
    assertThat(play.playbackReloads).isEqualTo(0);
    assertThat(play.textSamples).isEqualTo(32);
    assertThat(play.videoSamples).isEqualTo(4);
  }

  @Test
  public void thirtyThreeOutOfBandTextRuns_areNotPrefetchedButStillPlayed() throws Exception {
    Play play = play(scatteredText(/* runs= */ 33, /* empty= */ false));

    assertThat(play.prefetchedTextSamples).isEqualTo(0);
    assertThat(play.prefetchSeeks).isEqualTo(0);
    assertThat(play.playbackReloads).isGreaterThan(0);
    assertThat(play.textSamples).isEqualTo(33);
    assertThat(play.videoSamples).isEqualTo(4);
  }

  @Test
  public void scatteredEmptyTx3g_doesNotPrefetchOrReloadAndKeepsRealCues() throws Exception {
    Mux mux = new Mux().moovAtEnd().mdat64().freeBeforeMdat(300 * 1024).emptyMdatBeforeMoov();
    Track video = mux.addVideo();
    Track audio = mux.addAudio();
    Track text = mux.addText();
    for (int i = 0; i < 4; i++) {
      mux.place(video, VIDEO_BYTES, 1000);
      mux.place(audio, AUDIO_BYTES, 1000);
      mux.place(text, tx3gCue("live" + i), 1000);
    }
    mux.gap(2 * 1024 * 1024 + 4096);
    for (int i = 0; i < 40; i++) {
      mux.place(text, emptyTx3g(), 1000);
      mux.gap(300 * 1024);
    }

    byte[] file = mux.build();
    assertSniffs(file);
    Play play = play(file);

    assertThat(play.prefetchedTextSamples).isEqualTo(0);
    assertThat(play.prefetchSeeks).isEqualTo(0);
    assertThat(play.indexSeeks).isLessThan(8);
    assertThat(play.playbackReloads).isEqualTo(0);
    assertThat(play.videoSamples).isEqualTo(4);
    assertThat(play.audioSamples).isEqualTo(4);
    assertThat(play.textSamples).isEqualTo(4);
  }

  @Test
  public void compressedMoov_endsWithoutASeekMap() throws Exception {
    byte[] ftyp = box("ftyp", ascii("isom", 0, "isom"));
    byte[] cmov = box("cmov", new byte[8]);
    byte[] file = concat(ftyp, cmov);

    Play play = play(file);

    assertThat(play.ended).isTrue();
    assertThat(play.seekMap).isNull();
    assertThat(play.videoSamples).isEqualTo(0);
  }

  private static byte[] scatteredText(int runs, boolean empty) {
    Mux mux = new Mux().moovAtEnd();
    Track video = mux.addVideo();
    Track text = mux.addText();
    for (int i = 0; i < 4; i++) {
      mux.place(video, VIDEO_BYTES, 1000);
    }
    mux.gap(2 * 1024 * 1024 + 4096);
    for (int i = 0; i < runs; i++) {
      mux.place(text, empty ? emptyTx3g() : tx3gCue("r" + i), 1000);
      mux.gap(300 * 1024);
    }
    return mux.build();
  }

  private static void assertSniffs(byte[] file) throws Exception {
    assertThat(Sniffer.sniffUnfragmented(input(file), /* acceptHeic= */ false)).isNull();
  }

  private static FakeExtractorInput input(byte[] file) {
    return new FakeExtractorInput.Builder().setData(file).build();
  }

  private static Play play(byte[] file) throws Exception {
    Mp4Extractor extractor =
        new Mp4Extractor(
            SubtitleParser.Factory.UNSUPPORTED, Mp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput extractorInput = input(file);
    PositionHolder holder = new PositionHolder();
    Play play = new Play();
    play.output = output;
    boolean ignoredFirstPlaybackSeek = false;
    int steps = 0;
    while (steps++ < 200_000) {
      int result = extractor.read(extractorInput, holder);
      if (result == Extractor.RESULT_SEEK) {
        long jump = Math.abs(holder.position - extractorInput.getPosition());
        if (output.seekMap == null) {
          play.indexSeeks++;
        } else if (extractor.isOutOfBandTextPrefetchPending()) {
          play.prefetchSeeks++;
        } else if (!ignoredFirstPlaybackSeek) {
          ignoredFirstPlaybackSeek = true;
        } else if (jump >= RELOAD_BYTES) {
          play.playbackReloads++;
        }
        extractorInput.setPosition((int) holder.position);
      } else if (result == Extractor.RESULT_END_OF_INPUT) {
        play.ended = true;
        break;
      }
    }
    assertWithMessage("extractor did not finish").that(play.ended).isTrue();
    play.seekMap = output.seekMap;
    play.prefetchedTextSamples = extractor.outOfBandTextSampleCountForTests();
    play.videoSamples = samplesWithMime(output, MimeTypes.VIDEO_MPEG);
    play.audioSamples = samplesWithMime(output, MimeTypes.AUDIO_E_AC3);
    play.textSamples = samplesWithMime(output, MimeTypes.APPLICATION_TX3G);
    return play;
  }

  private static int samplesWithMime(FakeExtractorOutput output, String mime) {
    int count = 0;
    for (int i = 0; i < output.trackOutputs.size(); i++) {
      FakeTrackOutput track = output.trackOutputs.valueAt(i);
      if (track.lastFormat != null && mime.equals(track.lastFormat.sampleMimeType)) {
        count += track.getSampleCount();
      }
    }
    return count;
  }

  private static byte[] tx3gCue(String text) {
    byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
    byte[] sample = new byte[2 + utf8.length];
    sample[0] = (byte) (utf8.length >> 8);
    sample[1] = (byte) utf8.length;
    System.arraycopy(utf8, 0, sample, 2, utf8.length);
    return sample;
  }

  private static byte[] emptyTx3g() {
    return new byte[] {0, 0};
  }

  private static final class Play {
    private FakeExtractorOutput output;
    private androidx.media3.extractor.SeekMap seekMap;
    private boolean ended;
    private int indexSeeks;
    private int prefetchSeeks;
    private int playbackReloads;
    private int prefetchedTextSamples;
    private int videoSamples;
    private int audioSamples;
    private int textSamples;
  }

  /** Builds a small unfragmented MP4 with chosen atom order and sample placement. */
  private static final class Mux {
    private String brand = "isom";
    private boolean writeCompatibleBrand = true;
    private boolean moovAtEnd;
    private boolean mdat64;
    private boolean mdatExtendsToEnd;
    private boolean omitFtyp;
    private boolean wideBeforeMdat;
    private boolean emptyMdatBeforeMoov;
    private boolean forceCo64;
    private int freeBeforeMdat;
    private int freeBeforeMoov;
    private int samplesPerChunk = 1;
    private final List<Track> tracks = new ArrayList<>();
    private final List<byte[]> finishedPayloads = new ArrayList<>();
    private Bytes payload = new Bytes();
    private int currentMdat;

    private Mux brand(String brand) {
      this.brand = brand;
      return this;
    }

    private Mux noCompatibleBrand() {
      writeCompatibleBrand = false;
      return this;
    }

    private Mux moovAtEnd() {
      moovAtEnd = true;
      return this;
    }

    private Mux mdat64() {
      mdat64 = true;
      return this;
    }

    private Mux mdatExtendsToEnd() {
      mdatExtendsToEnd = true;
      moovAtEnd = false;
      return this;
    }

    private Mux omitFtyp() {
      omitFtyp = true;
      return this;
    }

    private Mux wideBeforeMdat() {
      wideBeforeMdat = true;
      return this;
    }

    private Mux emptyMdatBeforeMoov() {
      emptyMdatBeforeMoov = true;
      return this;
    }

    private Mux freeBeforeMdat(int padding) {
      freeBeforeMdat = padding;
      return this;
    }

    private Mux freeBeforeMoov(int padding) {
      freeBeforeMoov = padding;
      return this;
    }

    private Mux forceCo64() {
      forceCo64 = true;
      return this;
    }

    private Mux samplesPerChunk(int samplesPerChunk) {
      this.samplesPerChunk = samplesPerChunk;
      return this;
    }

    private Track addVideo() {
      return addTrack("vide", "m1v ");
    }

    private Track addAudio() {
      return addTrack("soun", "ec-3");
    }

    private Track addText() {
      return addTrack("sbtl", "tx3g");
    }

    private Track addTrack(String handler, String sampleEntry) {
      Track track = new Track(handler, sampleEntry);
      tracks.add(track);
      return track;
    }

    private Mux videoSamples(int count) {
      Track video = addVideo();
      for (int i = 0; i < count; i++) {
        place(video, VIDEO_BYTES, 1000);
      }
      return this;
    }

    private void gap(int bytes) {
      payload.pad(bytes);
    }

    private void nextMdat() {
      finishedPayloads.add(payload.toByteArray());
      payload = new Bytes();
      currentMdat++;
    }

    private void place(Track track, byte[] data, int duration) {
      Sample sample = new Sample(currentMdat, payload.size(), data, duration);
      track.samples.add(sample);
      payload.bytes(data);
    }

    private byte[] build() {
      if (payload.size() > 0 || finishedPayloads.isEmpty()) {
        finishedPayloads.add(payload.toByteArray());
      }
      byte[] ftyp = omitFtyp ? new byte[0] : ftypBox();
      byte[] freeBefore = freeBeforeMdat > 0 ? freeBox(freeBeforeMdat) : new byte[0];
      byte[] wide = wideBeforeMdat ? box("wide", new byte[0]) : new byte[0];
      int prefix = ftyp.length + freeBefore.length + wide.length;
      int moovSize = moov(zeroOffsets(), forceCo64, /* validateChunks= */ false).length;
      long cursor = prefix + (moovAtEnd ? 0 : moovSize);
      long[][] offsets = new long[tracks.size()][];
      for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
        offsets[trackIndex] = new long[tracks.get(trackIndex).samples.size()];
      }
      List<byte[]> mdatBoxes = new ArrayList<>();
      for (int mdatIndex = 0; mdatIndex < finishedPayloads.size(); mdatIndex++) {
        byte[] payloadBytes = finishedPayloads.get(mdatIndex);
        byte[] mdat = mdatBox(payloadBytes);
        int header = mdatExtendsToEnd ? 8 : (mdat64 ? 16 : 8);
        long base = cursor + header;
        for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
          Track track = tracks.get(trackIndex);
          for (int sampleIndex = 0; sampleIndex < track.samples.size(); sampleIndex++) {
            Sample sample = track.samples.get(sampleIndex);
            if (sample.mdatIndex == mdatIndex) {
              offsets[trackIndex][sampleIndex] = base + sample.offsetInPayload;
            }
          }
        }
        mdatBoxes.add(mdat);
        cursor += mdat.length;
      }
      byte[] moov = moov(offsets, forceCo64, /* validateChunks= */ true);
      if (moov.length != moovSize) {
        throw new IllegalStateException("moov size changed from " + moovSize + " to " + moov.length);
      }
      Bytes file = new Bytes();
      file.bytes(ftyp);
      file.bytes(freeBefore);
      file.bytes(wide);
      if (!moovAtEnd) {
        file.bytes(moov);
      }
      for (byte[] mdat : mdatBoxes) {
        file.bytes(mdat);
      }
      if (moovAtEnd) {
        if (freeBeforeMoov > 0) {
          file.bytes(freeBox(freeBeforeMoov));
        }
        if (emptyMdatBeforeMoov) {
          file.bytes(box("mdat", new byte[0]));
        }
        file.bytes(moov);
      }
      return file.toByteArray();
    }

    private long[][] zeroOffsets() {
      long[][] offsets = new long[tracks.size()][];
      for (int i = 0; i < tracks.size(); i++) {
        offsets[i] = new long[tracks.get(i).samples.size()];
      }
      return offsets;
    }

    private byte[] ftypBox() {
      Bytes body = new Bytes();
      body.ascii(brand);
      body.u32(0);
      if (writeCompatibleBrand) {
        body.ascii(brand);
      }
      return box("ftyp", body.toByteArray());
    }

    private byte[] mdatBox(byte[] payloadBytes) {
      if (mdatExtendsToEnd) {
        return concat(u32(0), ascii("mdat"), payloadBytes);
      }
      if (mdat64) {
        return concat(u32(1), ascii("mdat"), u64(16L + payloadBytes.length), payloadBytes);
      }
      return box("mdat", payloadBytes);
    }

    private byte[] moov(long[][] offsets, boolean co64, boolean validateChunks) {
      Bytes body = new Bytes();
      body.bytes(mvhd());
      for (int i = 0; i < tracks.size(); i++) {
        body.bytes(trak(tracks.get(i), i + 1, offsets[i], co64, validateChunks));
      }
      return box("moov", body.toByteArray());
    }

    private byte[] mvhd() {
      int duration = 0;
      for (Track track : tracks) {
        duration = Math.max(duration, track.duration());
      }
      Bytes body = new Bytes();
      body.u32(0);
      body.u32(0);
      body.u32(0);
      body.u32(1000);
      body.u32(duration);
      body.u32(0x00010000);
      body.u16(0x0100);
      body.u16(0);
      body.u32(0);
      body.u32(0);
      writeMatrix(body);
      body.pad(24);
      body.u32(tracks.size() + 1);
      return box("mvhd", body.toByteArray());
    }

    private byte[] trak(
        Track track, int id, long[] offsets, boolean co64, boolean validateChunks) {
      Bytes body = new Bytes();
      body.bytes(tkhd(track, id));
      if (track.editList) {
        body.bytes(elst(track));
      }
      body.bytes(mdia(track, offsets, co64, validateChunks));
      return box("trak", body.toByteArray());
    }

    private byte[] tkhd(Track track, int id) {
      Bytes body = new Bytes();
      body.u32(0);
      body.u32(0);
      body.u32(0);
      body.u32(id);
      body.u32(0);
      body.u32(track.duration());
      body.u32(0);
      body.u32(0);
      body.u16(0);
      body.u16(0);
      body.u16(track.handler.equals("soun") ? 0x0100 : 0);
      body.u16(0);
      writeMatrix(body);
      body.u32(track.handler.equals("vide") ? (320 << 16) : 0);
      body.u32(track.handler.equals("vide") ? (240 << 16) : 0);
      return box("tkhd", body.toByteArray());
    }

    private byte[] elst(Track track) {
      Bytes body = new Bytes();
      body.u32(0);
      body.u32(1);
      body.u32(track.duration());
      body.u32(0);
      body.u16(1);
      body.u16(0);
      return box("edts", box("elst", body.toByteArray()));
    }

    private byte[] mdia(Track track, long[] offsets, boolean co64, boolean validateChunks) {
      return box(
          "mdia",
          concat(
              mdhd(track),
              hdlr(track.handler),
              box("minf", box("stbl", stbl(track, offsets, co64, validateChunks)))));
    }

    private byte[] mdhd(Track track) {
      Bytes body = new Bytes();
      body.u32(0);
      body.u32(0);
      body.u32(0);
      body.u32(1000);
      body.u32(track.duration());
      body.u16(0x55C4);
      body.u16(0);
      return box("mdhd", body.toByteArray());
    }

    private static byte[] hdlr(String handler) {
      Bytes body = new Bytes();
      body.u32(0);
      body.u32(0);
      body.ascii(handler);
      body.pad(12);
      body.u8(0);
      return box("hdlr", body.toByteArray());
    }

    private byte[] stbl(Track track, long[] offsets, boolean co64, boolean validateChunks) {
      return concat(
          stsd(track),
          stts(track),
          stsc(track),
          stsz(track),
          chunkOffsets(track, offsets, co64, validateChunks));
    }

    private static byte[] stsd(Track track) {
      byte[] entry =
          track.sampleEntry.equals("m1v ")
              ? visualEntry()
              : track.sampleEntry.equals("ec-3") ? audioEntry() : textEntry();
      Bytes body = new Bytes();
      body.u32(0);
      body.u32(1);
      body.bytes(entry);
      return box("stsd", body.toByteArray());
    }

    private static byte[] visualEntry() {
      Bytes body = new Bytes();
      body.pad(6);
      body.u16(1);
      body.pad(16);
      body.u16(320);
      body.u16(240);
      body.u32(0x00480000);
      body.u32(0x00480000);
      body.u32(0);
      body.u16(1);
      body.pad(32);
      body.u16(0x0018);
      body.u16(0xFFFF);
      return box("m1v ", body.toByteArray());
    }

    private static byte[] audioEntry() {
      Bytes body = new Bytes();
      body.pad(6);
      body.u16(1);
      body.u16(0);
      body.u16(0);
      body.u32(0);
      body.u16(2);
      body.u16(16);
      body.u16(0);
      body.u16(0);
      body.u32(44100 << 16);
      return box("ec-3", body.toByteArray());
    }

    private static byte[] textEntry() {
      Bytes body = new Bytes();
      body.pad(6);
      body.u16(1);
      return box("tx3g", body.toByteArray());
    }

    private static byte[] stts(Track track) {
      Bytes body = new Bytes();
      body.u32(0);
      body.u32(track.samples.size());
      for (Sample sample : track.samples) {
        body.u32(1);
        body.u32(sample.duration);
      }
      return box("stts", body.toByteArray());
    }

    private byte[] stsc(Track track) {
      int chunks = track.samples.size() / samplesPerChunk;
      Bytes body = new Bytes();
      body.u32(0);
      body.u32(1);
      body.u32(1);
      body.u32(samplesPerChunk);
      body.u32(1);
      if (chunks * samplesPerChunk != track.samples.size()) {
        throw new IllegalStateException("sample count must divide samplesPerChunk");
      }
      return box("stsc", body.toByteArray());
    }

    private static byte[] stsz(Track track) {
      Bytes body = new Bytes();
      body.u32(0);
      body.u32(0);
      body.u32(track.samples.size());
      for (Sample sample : track.samples) {
        body.u32(sample.data.length);
      }
      return box("stsz", body.toByteArray());
    }

    private byte[] chunkOffsets(Track track, long[] offsets, boolean co64, boolean validateChunks) {
      int chunks = track.samples.size() / samplesPerChunk;
      Bytes body = new Bytes();
      body.u32(0);
      body.u32(chunks);
      for (int chunk = 0; chunk < chunks; chunk++) {
        long offset = offsets[chunk * samplesPerChunk];
        long cursor = offset;
        for (int sampleInChunk = 0; sampleInChunk < samplesPerChunk; sampleInChunk++) {
          int sampleIndex = chunk * samplesPerChunk + sampleInChunk;
          if (validateChunks && offsets[sampleIndex] != cursor) {
            throw new IllegalStateException("samples in a chunk are not contiguous");
          }
          cursor += track.samples.get(sampleIndex).data.length;
        }
        if (co64) {
          body.u64(offset);
        } else {
          body.u32(offset);
        }
      }
      return box(co64 ? "co64" : "stco", body.toByteArray());
    }
  }

  private static void writeMatrix(Bytes body) {
    body.u32(0x00010000);
    body.u32(0);
    body.u32(0);
    body.u32(0);
    body.u32(0x00010000);
    body.u32(0);
    body.u32(0);
    body.u32(0);
    body.u32(0x40000000);
  }

  private static byte[] freeBox(int padding) {
    return box("free", new byte[padding]);
  }

  private static byte[] box(String type, byte[] body) {
    byte[] out = new byte[8 + body.length];
    put32(out, 0, out.length);
    putAscii(out, 4, type);
    System.arraycopy(body, 0, out, 8, body.length);
    return out;
  }

  private static byte[] concat(byte[]... parts) {
    int size = 0;
    for (byte[] part : parts) {
      size += part.length;
    }
    byte[] out = new byte[size];
    int offset = 0;
    for (byte[] part : parts) {
      System.arraycopy(part, 0, out, offset, part.length);
      offset += part.length;
    }
    return out;
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] ascii(String major, int minor, String compatible) {
    return concat(ascii(major), u32(minor), ascii(compatible));
  }

  private static byte[] u32(long value) {
    byte[] out = new byte[4];
    put32(out, 0, value);
    return out;
  }

  private static byte[] u64(long value) {
    byte[] out = new byte[8];
    put32(out, 0, value >>> 32);
    put32(out, 4, value);
    return out;
  }

  private static void put32(byte[] out, int offset, long value) {
    out[offset] = (byte) (value >>> 24);
    out[offset + 1] = (byte) (value >>> 16);
    out[offset + 2] = (byte) (value >>> 8);
    out[offset + 3] = (byte) value;
  }

  private static void putAscii(byte[] out, int offset, String value) {
    for (int i = 0; i < 4; i++) {
      out[offset + i] = (byte) value.charAt(i);
    }
  }

  private static int indexOf(byte[] file, String type) {
    byte[] needle = ascii(type);
    for (int i = 0; i <= file.length - needle.length; i++) {
      boolean match = true;
      for (int j = 0; j < needle.length; j++) {
        if (file[i + j] != needle[j]) {
          match = false;
          break;
        }
      }
      if (match) {
        return i;
      }
    }
    return -1;
  }

  private static String fourcc(byte[] file, int offset) {
    return new String(file, offset, 4, StandardCharsets.US_ASCII);
  }

  private static long read32(byte[] file, int offset) {
    return ((file[offset] & 0xFFL) << 24)
        | ((file[offset + 1] & 0xFFL) << 16)
        | ((file[offset + 2] & 0xFFL) << 8)
        | (file[offset + 3] & 0xFFL);
  }

  private static final class Track {
    private final String handler;
    private final String sampleEntry;
    private final List<Sample> samples = new ArrayList<>();
    private boolean editList;

    private Track(String handler, String sampleEntry) {
      this.handler = handler;
      this.sampleEntry = sampleEntry;
    }

    private Track withEditList() {
      editList = true;
      return this;
    }

    private int duration() {
      int duration = 0;
      for (Sample sample : samples) {
        duration += sample.duration;
      }
      return duration;
    }
  }

  private static final class Sample {
    private final int mdatIndex;
    private final int offsetInPayload;
    private final byte[] data;
    private final int duration;

    private Sample(int mdatIndex, int offsetInPayload, byte[] data, int duration) {
      this.mdatIndex = mdatIndex;
      this.offsetInPayload = offsetInPayload;
      this.data = data;
      this.duration = duration;
    }
  }

  private static final class Bytes {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private void u8(int value) {
      out.write(value);
    }

    private void u16(int value) {
      out.write(value >> 8);
      out.write(value);
    }

    private void u32(long value) {
      out.write((int) (value >> 24));
      out.write((int) (value >> 16));
      out.write((int) (value >> 8));
      out.write((int) value);
    }

    private void u64(long value) {
      u32(value >>> 32);
      u32(value);
    }

    private void ascii(String value) {
      for (int i = 0; i < value.length(); i++) {
        out.write(value.charAt(i));
      }
    }

    private void bytes(byte[] value) {
      out.write(value, 0, value.length);
    }

    private void pad(int count) {
      for (int i = 0; i < count; i++) {
        out.write(0);
      }
    }

    private int size() {
      return out.size();
    }

    private byte[] toByteArray() {
      return out.toByteArray();
    }
  }
}
