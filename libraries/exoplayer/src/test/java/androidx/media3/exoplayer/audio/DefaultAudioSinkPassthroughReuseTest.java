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
package androidx.media3.exoplayer.audio;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.media.AudioTrack;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.NuvioEngineConfig;
import androidx.media3.test.utils.FakeClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/**
 * Unit tests for opt-in passthrough {@link AudioTrack} seek reuse in {@link DefaultAudioSink} and
 * post-flush playback head guards in {@link AudioTrackPositionTracker}.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 34)
public final class DefaultAudioSinkPassthroughReuseTest {

  private NuvioEngineConfig previousConfig;

  @Before
  public void setUp() {
    previousConfig = NuvioEngineConfig.get();
  }

  @After
  public void tearDown() {
    NuvioEngineConfig.set(previousConfig);
  }

  @Test
  public void stockMode_flushReleasesAndRecreatesPassthroughAudioTrack() throws Exception {
    NuvioEngineConfig.set(NuvioEngineConfig.stockMode());
    assertThat(NuvioEngineConfig.get().isPassthroughAudioTrackReuseOnFlushEnabled()).isFalse();

    AtomicInteger buildCount = new AtomicInteger();
    AudioTrack track1 = createMockPassthroughTrack();
    AudioTrack track2 = createMockPassthroughTrack();
    DefaultAudioSink sink = createPassthroughSink(buildCount, track1, track2);

    Format ac3Format = createAc3Format();
    sink.configure(ac3Format, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
    sink.play();
    assertThat(sink.handleBuffer(createAc3SyncFrame(), /* presentationTimeUs= */ 1_000_000L, 1))
        .isTrue();
    assertThat(buildCount.get()).isEqualTo(1);

    sink.flush();
    assertThat(sink.handleBuffer(createAc3SyncFrame(), /* presentationTimeUs= */ 30_000_000L, 1))
        .isTrue();
    assertThat(buildCount.get()).isEqualTo(2);
    verify(track1, never()).flush();
  }

  @Test
  public void decoupledMode_flushReusesPassthroughAudioTrackWithoutRecreating() throws Exception {
    NuvioEngineConfig.set(NuvioEngineConfig.stockWithDecoupledAvSync());
    assertThat(NuvioEngineConfig.get().isPassthroughAudioTrackReuseOnFlushEnabled()).isTrue();

    AtomicInteger buildCount = new AtomicInteger();
    AudioTrack track1 = createMockPassthroughTrack();
    AudioTrack track2 = createMockPassthroughTrack();
    DefaultAudioSink sink = createPassthroughSink(buildCount, track1, track2);

    Format ac3Format = createAc3Format();
    sink.configure(ac3Format, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
    sink.play();
    assertThat(sink.handleBuffer(createAc3SyncFrame(), /* presentationTimeUs= */ 1_000_000L, 1))
        .isTrue();
    assertThat(buildCount.get()).isEqualTo(1);

    sink.flush();
    verify(track1, times(1)).pause();
    verify(track1, times(1)).flush();
    verify(track1, never()).release();

    assertThat(sink.handleBuffer(createAc3SyncFrame(), /* presentationTimeUs= */ 30_000_000L, 1))
        .isTrue();
    assertThat(buildCount.get()).isEqualTo(1);
    assertThat(sink.getCurrentPositionUs(/* sourceEnded= */ false)).isEqualTo(30_000_000L);

    sink.reset();
    assertThat(sink.handleBuffer(createAc3SyncFrame(), /* presentationTimeUs= */ 60_000_000L, 1))
        .isTrue();
    assertThat(buildCount.get()).isEqualTo(2);
  }

  @Test
  public void positionTracker_stalePreFlushHalHeadDuringAsyncFlushDevice_isRejected() {
    NuvioEngineConfig.set(NuvioEngineConfig.stockWithDecoupledAvSync());
    AudioTrack track = createMockPassthroughTrack();
    AtomicInteger rawHead = new AtomicInteger(0);
    when(track.getPlaybackHeadPosition()).thenAnswer(inv -> rawHead.get());

    FakeClock clock = new FakeClock(/* initialTimeMs= */ 47_800L);
    AudioTrackPositionTracker tracker =
        new AudioTrackPositionTracker(mock(AudioTrackPositionTracker.Listener.class));
    tracker.setClock(clock);
    tracker.setAudioTrack(
        track,
        /* isPassthrough= */ true,
        C.ENCODING_E_AC3,
        /* outputPcmFrameSize= */ C.LENGTH_UNSET,
        /* bufferSize= */ 65536,
        /* enableOnAudioPositionAdvancingFix= */ true);

    // Mirrors Realtek rtd6748: AudioTrack.flush() resets client head to 0 immediately, while
    // HIDL IStreamOut::flush (flush-device, ~10-20 ms) runs asynchronously on AudioTrack.play().
    assertThat(tracker.resetForReusedAudioTrack()).isEqualTo(0L);
    tracker.start();
    track.play();

    // 5 ms after play(), vendor HAL hasn't finished flush-device yet and leaks stale pre-flush
    // head (744,344 frames = 15.5 s). Must be rejected so it never poisons the monotonic clock!
    rawHead.set(744_344);
    clock.advanceTime(6);
    assertThat(tracker.getCurrentPositionUs()).isEqualTo(0L);

    // 18 ms after play(), flush-device completes and HAL reports dspFrames=0.
    rawHead.set(0);
    clock.advanceTime(12);
    assertThat(tracker.getCurrentPositionUs()).isEqualTo(0L);

    // 25 ms after play(), HAL reports first real post-flush position: 5,280 frames (110 ms).
    // Accepted and smoothly slewed from 0 (never jumping to the stale 15.5 s pre-flush head!).
    rawHead.set(5_280);
    clock.advanceTime(7);
    long posUs = tracker.getCurrentPositionUs();
    assertThat(posUs).isGreaterThan(0L);
    assertThat(posUs).isLessThan(150_000L);
  }

  @Test
  public void positionTracker_delayedHeadResetAfterReuse_doesNotTrigger32BitWrapAround() {
    NuvioEngineConfig.set(NuvioEngineConfig.stockWithDecoupledAvSync());
    AudioTrack track = createMockPassthroughTrack();
    AtomicInteger rawHead = new AtomicInteger(1_440_000);
    when(track.getPlaybackHeadPosition()).thenAnswer(inv -> rawHead.get());

    FakeClock clock = new FakeClock(/* initialTimeMs= */ 10_000L);
    AudioTrackPositionTracker tracker =
        new AudioTrackPositionTracker(mock(AudioTrackPositionTracker.Listener.class));
    tracker.setClock(clock);
    tracker.setAudioTrack(
        track,
        /* isPassthrough= */ true,
        C.ENCODING_AC3,
        /* outputPcmFrameSize= */ C.LENGTH_UNSET,
        /* bufferSize= */ 32768,
        /* enableOnAudioPositionAdvancingFix= */ true);

    assertThat(tracker.resetForReusedAudioTrack()).isEqualTo(1_440_000L);
    tracker.start();
    track.play();

    clock.advanceTime(10);
    assertThat(tracker.getCurrentPositionUs()).isEqualTo(0L);

    rawHead.set(4_800);
    clock.advanceTime(100);
    long posUs = tracker.getCurrentPositionUs();
    assertThat(posUs).isAtLeast(90_000L);
    assertThat(posUs).isLessThan(200_000L);
  }

  @Test
  @Config(sdk = 28)
  public void positionTracker_api28DelayedHeadResetToZeroAfterReuse_clearsBaseOffsetImmediately() {
    NuvioEngineConfig.set(NuvioEngineConfig.stockWithDecoupledAvSync());
    AudioTrack track = createMockPassthroughTrack();
    AtomicInteger rawHead = new AtomicInteger(1_440_000);
    when(track.getPlaybackHeadPosition()).thenAnswer(inv -> rawHead.get());

    FakeClock clock = new FakeClock(/* initialTimeMs= */ 15_000L);
    AudioTrackPositionTracker tracker =
        new AudioTrackPositionTracker(mock(AudioTrackPositionTracker.Listener.class));
    tracker.setClock(clock);
    tracker.setAudioTrack(
        track,
        /* isPassthrough= */ true,
        C.ENCODING_AC3,
        /* outputPcmFrameSize= */ C.LENGTH_UNSET,
        /* bufferSize= */ 32768,
        /* enableOnAudioPositionAdvancingFix= */ true);

    tracker.resetForReusedAudioTrack();
    tracker.start();
    track.play();

    rawHead.set(0);
    clock.advanceTime(10);
    assertThat(tracker.getCurrentPositionUs()).isEqualTo(0L);

    rawHead.set(4_800);
    clock.advanceTime(100);
    long posUs = tracker.getCurrentPositionUs();
    assertThat(posUs).isAtLeast(90_000L);
    assertThat(posUs).isLessThan(200_000L);
  }

  @Test
  public void positionTracker_monotonicHeadAfterReuse_subtractsBaseOffsetCleanly() {
    NuvioEngineConfig.set(NuvioEngineConfig.stockWithDecoupledAvSync());
    AudioTrack track = createMockPassthroughTrack();
    AtomicInteger rawHead = new AtomicInteger(1_440_000);
    when(track.getPlaybackHeadPosition()).thenAnswer(inv -> rawHead.get());

    FakeClock clock = new FakeClock(/* initialTimeMs= */ 20_000L);
    AudioTrackPositionTracker tracker =
        new AudioTrackPositionTracker(mock(AudioTrackPositionTracker.Listener.class));
    tracker.setClock(clock);
    tracker.setAudioTrack(
        track,
        /* isPassthrough= */ true,
        C.ENCODING_AC3,
        /* outputPcmFrameSize= */ C.LENGTH_UNSET,
        /* bufferSize= */ 32768,
        /* enableOnAudioPositionAdvancingFix= */ true);

    tracker.resetForReusedAudioTrack();
    tracker.start();
    track.play();

    rawHead.set(1_444_800);
    clock.advanceTime(100);
    long posUs = tracker.getCurrentPositionUs();
    assertThat(posUs).isAtLeast(90_000L);
    assertThat(posUs).isLessThan(200_000L);
  }

  private static DefaultAudioSink createPassthroughSink(
      AtomicInteger buildCount, AudioTrack... tracks) {
    AudioCapabilities capabilities =
        new AudioCapabilities(new int[] {C.ENCODING_AC3, C.ENCODING_E_AC3}, /* maxChannelCount= */ 8);
    DefaultAudioSink.AudioTrackProvider provider =
        (audioTrackConfig, audioAttributes, audioSessionId, context) -> {
          int idx = buildCount.getAndIncrement();
          return tracks[Math.min(idx, tracks.length - 1)];
        };
    return new DefaultAudioSink.Builder()
        .setAudioCapabilities(capabilities)
        .setAudioTrackProvider(provider)
        .build();
  }

  private static AudioTrack createMockPassthroughTrack() {
    AudioTrack track = mock(AudioTrack.class);
    AtomicInteger playState = new AtomicInteger(AudioTrack.PLAYSTATE_STOPPED);
    when(track.getState()).thenReturn(AudioTrack.STATE_INITIALIZED);
    when(track.getSampleRate()).thenReturn(48_000);
    when(track.getAudioSessionId()).thenReturn(42);
    when(track.getBufferSizeInFrames()).thenReturn(16_384);
    when(track.getPlayState()).thenAnswer(inv -> playState.get());
    when(track.getPlaybackHeadPosition()).thenReturn(0);
    when(track.write(
            org.mockito.ArgumentMatchers.any(ByteBuffer.class),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt()))
        .thenAnswer(
            inv -> {
              ByteBuffer buf = inv.getArgument(0);
              int size = inv.getArgument(1);
              int toConsume = Math.min(size, buf.remaining());
              buf.position(buf.position() + toConsume);
              return toConsume;
            });
    org.mockito.Mockito.doAnswer(
            inv -> {
              playState.set(AudioTrack.PLAYSTATE_PLAYING);
              return null;
            })
        .when(track)
        .play();
    org.mockito.Mockito.doAnswer(
            inv -> {
              playState.set(AudioTrack.PLAYSTATE_PAUSED);
              return null;
            })
        .when(track)
        .pause();
    return track;
  }

  private static Format createAc3Format() {
    return new Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_AC3)
        .setChannelCount(6)
        .setSampleRate(48_000)
        .build();
  }

  private static ByteBuffer createAc3SyncFrame() {
    byte[] frame = new byte[1536];
    frame[0] = 0x0B;
    frame[1] = 0x77;
    return ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
  }
}
