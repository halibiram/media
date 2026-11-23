/*
 * Copyright 2026 The Android Open Source Project
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
import static org.junit.Assert.assertThrows;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.AuxEffectInfo;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.util.Clock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link AsyncAudioSink}. */
@RunWith(AndroidJUnit4.class)
public final class AsyncAudioSinkTest {

  private static final Format PCM_FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.AUDIO_RAW)
          .setPcmEncoding(C.ENCODING_PCM_16BIT)
          .setChannelCount(2)
          .setSampleRate(48_000)
          .build();

  private FakeDelegateAudioSink delegate;
  private AsyncAudioSink asyncAudioSink;

  @Before
  public void setUp() {
    delegate = new FakeDelegateAudioSink();
    asyncAudioSink = new AsyncAudioSink(delegate);
    asyncAudioSink.setClock(Clock.DEFAULT);
  }

  @After
  public void tearDown() {
    asyncAudioSink.release();
  }

  @Test
  public void handleBuffer_doesNotBlockCallerWhenDelegateStalls() throws Exception {
    CountDownLatch delegateEntered = new CountDownLatch(1);
    CountDownLatch releaseDelegate = new CountDownLatch(1);
    delegate.handleBufferLatch = delegateEntered;
    delegate.blockHandleBufferUntil = releaseDelegate;

    asyncAudioSink.configure(PCM_FORMAT, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
    asyncAudioSink.play();

    ByteBuffer buf1 = createPcmBuffer(1024);
    long startNs = System.nanoTime();
    boolean consumed = asyncAudioSink.handleBuffer(buf1, /* presentationTimeUs= */ 0L, 1);
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

    assertThat(consumed).isTrue();
    assertThat(buf1.hasRemaining()).isFalse();
    assertThat(elapsedMs).isLessThan(100L);
    assertThat(delegateEntered.await(2, TimeUnit.SECONDS)).isTrue();

    // Even while the audio thread is blocked inside delegate.handleBuffer, caller can keep
    // enqueueing and reading the clock without blocking.
    ByteBuffer buf2 = createPcmBuffer(1024);
    assertThat(asyncAudioSink.handleBuffer(buf2, /* presentationTimeUs= */ 5_333L, 1)).isTrue();
    assertThat(asyncAudioSink.hasPendingData()).isTrue();

    releaseDelegate.countDown();
  }

  @Test
  public void delegateCalls_executeExclusivelyOnDedicatedAudioThread() throws Exception {
    String callerThreadName = Thread.currentThread().getName();
    CountDownLatch handled = new CountDownLatch(1);
    delegate.onHandleBufferDone = handled;

    asyncAudioSink.getFormatSupport(PCM_FORMAT);
    asyncAudioSink.configure(PCM_FORMAT, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
    asyncAudioSink.play();
    asyncAudioSink.handleBuffer(createPcmBuffer(512), /* presentationTimeUs= */ 1_000L, 1);

    assertThat(handled.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(delegate.observedThreadNames).isNotEmpty();
    for (String threadName : delegate.observedThreadNames) {
      assertThat(threadName).isEqualTo("ExoPlayer:AudioSink");
      assertThat(threadName).isNotEqualTo(callerThreadName);
    }
  }

  @Test
  public void flush_immediatelyInvalidatesClockSnapshot() throws Exception {
    delegate.reportedPositionUs.set(1_500_000L);
    CountDownLatch handled = new CountDownLatch(1);
    delegate.onHandleBufferDone = handled;

    asyncAudioSink.configure(PCM_FORMAT, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
    asyncAudioSink.play();
    asyncAudioSink.handleBuffer(createPcmBuffer(512), /* presentationTimeUs= */ 1_500_000L, 1);
    assertThat(handled.await(2, TimeUnit.SECONDS)).isTrue();

    // Wait briefly for snapshot publication.
    long pos = waitForPosition(asyncAudioSink);
    assertThat(pos).isAtLeast(1_500_000L);

    asyncAudioSink.flush();
    assertThat(asyncAudioSink.getCurrentPositionUs(/* sourceEnded= */ false))
        .isEqualTo(AudioSink.CURRENT_POSITION_NOT_SET);
  }

  @Test
  public void initializationException_propagatesToPlaybackThread() throws Exception {
    AudioSink.InitializationException expected =
        new AudioSink.InitializationException(
            "init failed", 0, PCM_FORMAT, /* isRecoverable= */ false, /* cause= */ null);
    CountDownLatch threwLatch = new CountDownLatch(1);
    delegate.initExceptionToThrow.set(expected);
    delegate.onHandleBufferDone = threwLatch;

    asyncAudioSink.configure(PCM_FORMAT, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
    assertThat(asyncAudioSink.handleBuffer(createPcmBuffer(512), 0L, 1)).isTrue();
    assertThat(threwLatch.await(2, TimeUnit.SECONDS)).isTrue();

    AudioSink.InitializationException thrown =
        assertThrows(
            AudioSink.InitializationException.class,
            () -> asyncAudioSink.handleBuffer(createPcmBuffer(512), 10_000L, 1));
    assertThat(thrown).isSameInstanceAs(expected);
  }

  @Test
  public void writeException_propagatesToPlaybackThread() throws Exception {
    AudioSink.WriteException expected =
        new AudioSink.WriteException(-32, PCM_FORMAT, /* isRecoverable= */ true);
    CountDownLatch threwLatch = new CountDownLatch(1);
    delegate.writeExceptionToThrow.set(expected);
    delegate.onHandleBufferDone = threwLatch;

    asyncAudioSink.configure(PCM_FORMAT, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
    assertThat(asyncAudioSink.handleBuffer(createPcmBuffer(512), 0L, 1)).isTrue();
    assertThat(threwLatch.await(2, TimeUnit.SECONDS)).isTrue();

    AudioSink.WriteException thrown =
        assertThrows(
            AudioSink.WriteException.class,
            () -> asyncAudioSink.handleBuffer(createPcmBuffer(512), 10_000L, 1));
    assertThat(thrown).isSameInstanceAs(expected);
  }

  @Test
  public void onPositionDiscontinuity_deliveredOnCallerThreadDuringGetPosition() throws Exception {
    AtomicReference<String> callbackThread = new AtomicReference<>();
    CountDownLatch handled = new CountDownLatch(1);
    delegate.triggerDiscontinuityOnNextBuffer.set(true);
    delegate.onHandleBufferDone = handled;

    asyncAudioSink.setListener(
        new AudioSink.Listener() {
          @Override
          public void onPositionDiscontinuity() {
            callbackThread.set(Thread.currentThread().getName());
          }

          @Override
          public void onUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {}

          @Override
          public void onSkipSilenceEnabledChanged(boolean skipSilenceEnabled) {}
        });
    asyncAudioSink.configure(PCM_FORMAT, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
    asyncAudioSink.handleBuffer(createPcmBuffer(512), 0L, 1);
    assertThat(handled.await(2, TimeUnit.SECONDS)).isTrue();

    asyncAudioSink.getCurrentPositionUs(/* sourceEnded= */ false);
    assertThat(callbackThread.get()).isEqualTo(Thread.currentThread().getName());
  }

  @Test
  public void pausedAndTrackFull_doesNotSpinUntilPlay() throws Exception {
    AtomicInteger handleCalls = new AtomicInteger(0);
    CountDownLatch firstAttempt = new CountDownLatch(1);
    CountDownLatch consumedAfterPlay = new CountDownLatch(1);
    AtomicBoolean acceptBuffers = new AtomicBoolean(false);
    delegate.customHandleBuffer =
        () -> {
          handleCalls.incrementAndGet();
          firstAttempt.countDown();
          if (acceptBuffers.get()) {
            consumedAfterPlay.countDown();
            return true;
          }
          return false;
        };

    asyncAudioSink.configure(PCM_FORMAT, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
    assertThat(asyncAudioSink.handleBuffer(createPcmBuffer(512), 0L, 1)).isTrue();
    assertThat(firstAttempt.await(2, TimeUnit.SECONDS)).isTrue();

    // Sleep 80ms while paused with a full AudioTrack; should NOT spin every 10ms.
    Thread.sleep(80);
    assertThat(handleCalls.get()).isEqualTo(1);
    assertThat(asyncAudioSink.hasPendingData()).isTrue();

    // Once play() is called, draining resumes immediately.
    acceptBuffers.set(true);
    asyncAudioSink.play();
    assertThat(consumedAfterPlay.await(2, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  public void release_isIdempotent() {
    asyncAudioSink.getFormatSupport(PCM_FORMAT);
    asyncAudioSink.release();
    asyncAudioSink.release();
    assertThat(delegate.releaseCount.get()).isEqualTo(1);
  }

  @Test
  public void enableTunnelingV21_flushesStaleQueueAndInvalidatesClockSnapshot() throws Exception {
    delegate.reportedPositionUs.set(1_500_000L);
    CountDownLatch handled = new CountDownLatch(1);
    delegate.onHandleBufferDone = handled;

    asyncAudioSink.configure(PCM_FORMAT, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
    asyncAudioSink.play();
    asyncAudioSink.handleBuffer(createPcmBuffer(512), /* presentationTimeUs= */ 1_500_000L, 1);
    assertThat(handled.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(waitForPosition(asyncAudioSink)).isAtLeast(1_500_000L);

    // Block delegate on next buffer, enqueue a stale pre-tunneling buffer, then enable tunneling.
    CountDownLatch blockNext = new CountDownLatch(1);
    delegate.onHandleBufferDone = null;
    delegate.blockHandleBufferUntil = blockNext;
    asyncAudioSink.handleBuffer(createPcmBuffer(512), /* presentationTimeUs= */ 1_520_000L, 1);
    asyncAudioSink.handleBuffer(createPcmBuffer(512), /* presentationTimeUs= */ 1_540_000L, 1);
    blockNext.countDown();

    asyncAudioSink.setAudioSessionId(42);
    asyncAudioSink.enableTunnelingV21();

    assertThat(delegate.audioSessionId.get()).isEqualTo(42);
    assertThat(delegate.tunnelingEnabled.get()).isTrue();
    assertThat(asyncAudioSink.getCurrentPositionUs(/* sourceEnded= */ false))
        .isEqualTo(AudioSink.CURRENT_POSITION_NOT_SET);
    assertThat(asyncAudioSink.hasPendingData()).isFalse();

    // Disable tunneling should also clear clock snapshot cleanly.
    delegate.reportedPositionUs.set(2_000_000L);
    CountDownLatch tunneledHandled = new CountDownLatch(1);
    delegate.onHandleBufferDone = tunneledHandled;
    asyncAudioSink.handleBuffer(createPcmBuffer(512), /* presentationTimeUs= */ 2_000_000L, 1);
    assertThat(tunneledHandled.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(waitForPosition(asyncAudioSink)).isAtLeast(2_000_000L);

    asyncAudioSink.disableTunneling();
    assertThat(delegate.tunnelingEnabled.get()).isFalse();
    assertThat(asyncAudioSink.getCurrentPositionUs(/* sourceEnded= */ false))
        .isEqualTo(AudioSink.CURRENT_POSITION_NOT_SET);
  }

  @Test
  public void tunneling_preservesPresentationTimestampsAndCompletesEndOfStream() throws Exception {
    asyncAudioSink.setAudioSessionId(7);
    asyncAudioSink.enableTunnelingV21();
    asyncAudioSink.configure(PCM_FORMAT, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
    asyncAudioSink.play();

    assertThat(asyncAudioSink.handleBuffer(createPcmBuffer(512), 100_000L, 1)).isTrue();
    assertThat(asyncAudioSink.handleBuffer(createPcmBuffer(512), 120_000L, 1)).isTrue();
    assertThat(asyncAudioSink.handleBuffer(createPcmBuffer(512), 140_000L, 1)).isTrue();
    asyncAudioSink.playToEndOfStream();

    long deadline = System.currentTimeMillis() + 2_000L;
    while (!asyncAudioSink.isEnded() && System.currentTimeMillis() < deadline) {
      Thread.sleep(5);
    }

    assertThat(asyncAudioSink.isEnded()).isTrue();
    assertThat(delegate.tunnelingEnabled.get()).isTrue();
    assertThat(delegate.observedPresentationTimesUs)
        .containsExactly(100_000L, 120_000L, 140_000L)
        .inOrder();
  }

  @Test
  public void playToEndOfStream_surfacesPendingInitializationException() throws Exception {
    AudioSink.InitializationException expected =
        new AudioSink.InitializationException(
            "init failed near EOS", 0, PCM_FORMAT, /* isRecoverable= */ false, /* cause= */ null);
    CountDownLatch threwLatch = new CountDownLatch(1);
    delegate.initExceptionToThrow.set(expected);
    delegate.onHandleBufferDone = threwLatch;

    asyncAudioSink.configure(PCM_FORMAT, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
    assertThat(asyncAudioSink.handleBuffer(createPcmBuffer(512), 0L, 1)).isTrue();
    assertThat(threwLatch.await(2, TimeUnit.SECONDS)).isTrue();

    // No more handleBuffer() calls arrive before EOS; playToEndOfStream() must surface the error
    // instead of silently hanging with isEnded() == false forever.
    AudioSink.WriteException thrown =
        assertThrows(AudioSink.WriteException.class, () -> asyncAudioSink.playToEndOfStream());
    assertThat(thrown.format).isEqualTo(PCM_FORMAT);
    assertThat(thrown.isRecoverable).isFalse();
  }

  @Test
  public void configure_afterInFlightFlush_isNonBlockingAndPreservesFifoOrder() throws Exception {
    asyncAudioSink.configure(PCM_FORMAT, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
    asyncAudioSink.play();

    CountDownLatch flushStarted = new CountDownLatch(1);
    CountDownLatch releaseFlush = new CountDownLatch(1);
    delegate.onFlushStarted = flushStarted;
    delegate.blockFlushUntil = releaseFlush;

    asyncAudioSink.flush();
    assertThat(flushStarted.await(2, TimeUnit.SECONDS)).isTrue();

    // configure() while MSG_FLUSH is blocked on the audio thread must return immediately instead
    // of blocking the playback thread, and then execute on the audio thread right after flush().
    long startMs = System.currentTimeMillis();
    asyncAudioSink.configure(PCM_FORMAT, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
    long elapsedMs = System.currentTimeMillis() - startMs;
    assertThat(elapsedMs).isLessThan(200L);

    CountDownLatch postFlushBuffer = new CountDownLatch(1);
    delegate.handleBufferLatch = postFlushBuffer;
    assertThat(asyncAudioSink.handleBuffer(createPcmBuffer(512), 42_000L, 1)).isTrue();

    releaseFlush.countDown();
    assertThat(postFlushBuffer.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(delegate.observedEvents).containsAtLeast("flush", "configure", "handleBuffer").inOrder();
    assertThat(delegate.observedPresentationTimesUs).containsExactly(42_000L);
  }

  @Test
  public void steadyStateBatching_drainsQueuedBuffersWithinPeriodicTick() throws Exception {
    delegate.reportedPositionUs.set(1_000_000L);
    asyncAudioSink.configure(PCM_FORMAT, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
    asyncAudioSink.play();
    assertThat(waitForPosition(asyncAudioSink)).isAtLeast(1_000_000L);
    delegate.reportedPositionUs.set(1_030_000L);
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(40));
    Thread.sleep(40);

    // Steady-state advancing clock with 250 ms buffer: single-chunk handleBuffer() may defer wakeup
    // to the 25 ms periodic clock tick and must still drain cleanly without stalling.
    CountDownLatch drained = new CountDownLatch(2);
    delegate.handleBufferLatch = drained;
    assertThat(asyncAudioSink.handleBuffer(createPcmBuffer(512), 1_050_000L, 1)).isTrue();
    assertThat(asyncAudioSink.handleBuffer(createPcmBuffer(512), 1_070_000L, 1)).isTrue();
    long deadlineMs = System.currentTimeMillis() + 2_000L;
    while (!drained.await(10, TimeUnit.MILLISECONDS) && System.currentTimeMillis() < deadlineMs) {
      org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(30));
    }
    assertThat(drained.getCount()).isEqualTo(0);
    assertThat(delegate.observedPresentationTimesUs).containsExactly(1_050_000L, 1_070_000L).inOrder();
  }

  @Test
  public void reset_isNonBlockingAndPreservesFifoOrderForTrackSwitch() throws Exception {
    asyncAudioSink.configure(PCM_FORMAT, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
    asyncAudioSink.play();

    CountDownLatch flushStarted = new CountDownLatch(1);
    CountDownLatch releaseFlush = new CountDownLatch(1);
    delegate.onFlushStarted = flushStarted;
    delegate.blockFlushUntil = releaseFlush;

    asyncAudioSink.flush();
    assertThat(flushStarted.await(2, TimeUnit.SECONDS)).isTrue();

    // Simulate live audio track switch: reset() + no-op disableTunneling() + configure() + play()
    // + handleBuffer() while flush() is still blocked on the audio thread must return immediately.
    long startMs = System.currentTimeMillis();
    asyncAudioSink.reset();
    asyncAudioSink.disableTunneling();
    asyncAudioSink.configure(PCM_FORMAT, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
    asyncAudioSink.play();
    CountDownLatch postSwitchBuffer = new CountDownLatch(1);
    delegate.handleBufferLatch = postSwitchBuffer;
    assertThat(asyncAudioSink.handleBuffer(createPcmBuffer(512), 77_000L, 1)).isTrue();
    long elapsedMs = System.currentTimeMillis() - startMs;
    assertThat(elapsedMs).isLessThan(200L);

    releaseFlush.countDown();
    assertThat(postSwitchBuffer.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(delegate.observedEvents)
        .containsAtLeast("flush", "reset", "configure", "handleBuffer")
        .inOrder();
    assertThat(delegate.observedPresentationTimesUs).containsExactly(77_000L);
  }

  @Test
  public void formatSupportCache_reusesAcrossDifferentTrackIdsAndNoOpDisableTunneling() {
    Format engTrack =
        PCM_FORMAT.buildUpon().setId("1").setLanguage("eng").setLabel("English").build();
    Format turTrack =
        PCM_FORMAT.buildUpon().setId("2").setLanguage("tur").setLabel("Turkish").build();

    assertThat(asyncAudioSink.getFormatSupport(engTrack))
        .isEqualTo(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY);
    asyncAudioSink.disableTunneling();
    assertThat(asyncAudioSink.getFormatSupport(turTrack))
        .isEqualTo(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY);
    assertThat(delegate.formatSupportCalls.get()).isEqualTo(1);
  }

  @Test
  public void bufferPool_evictsUndersizedBuffersWhenPacketSizeGrows() throws Exception {
    asyncAudioSink.configure(PCM_FORMAT, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
    asyncAudioSink.play();

    // Fill the pool with minimum-sized buffers (16 KB) first.
    CountDownLatch smallDone = new CountDownLatch(20);
    delegate.onHandleBufferDone = smallDone;
    for (int i = 0; i < 20; i++) {
      assertThat(asyncAudioSink.handleBuffer(createPcmBuffer(4096), i * 10_000L, 1)).isTrue();
    }
    assertThat(smallDone.await(2, TimeUnit.SECONDS)).isTrue();

    // Now stream larger packets (24 KB -> 32 KB bucket) sequentially and verify the pool replaces
    // the smaller 16 KB slots and reuses a bounded set of DirectByteBuffers instead of allocating
    // a new DirectByteBuffer on every single packet.
    java.util.Set<Integer> observedBufferIdentities =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    delegate.onBufferConsumed = buf -> observedBufferIdentities.add(System.identityHashCode(buf));

    for (int i = 0; i < 60; i++) {
      CountDownLatch oneDone = new CountDownLatch(1);
      delegate.onHandleBufferDone = oneDone;
      assertThat(asyncAudioSink.handleBuffer(createPcmBuffer(24_576), (20 + i) * 10_000L, 1))
          .isTrue();
      assertThat(oneDone.await(2, TimeUnit.SECONDS)).isTrue();
    }

    // Sequential reuse after evicting the 16 KB pool should stabilize at <= 4 distinct buffers
    // (1-2 in practice), never 60.
    assertThat(observedBufferIdentities.size()).isAtMost(4);
  }

  private static ByteBuffer createPcmBuffer(int bytes) {
    ByteBuffer buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < bytes; i++) {
      buffer.put((byte) (i & 0xFF));
    }
    buffer.flip();
    return buffer;
  }

  private static long waitForPosition(AsyncAudioSink sink) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 2_000L;
    while (System.currentTimeMillis() < deadline) {
      long pos = sink.getCurrentPositionUs(/* sourceEnded= */ false);
      if (pos != AudioSink.CURRENT_POSITION_NOT_SET) {
        return pos;
      }
      Thread.sleep(5);
    }
    return AudioSink.CURRENT_POSITION_NOT_SET;
  }

  private static final class FakeDelegateAudioSink implements AudioSink {
    final List<String> observedThreadNames = new CopyOnWriteArrayList<>();
    final List<String> observedEvents = new CopyOnWriteArrayList<>();
    final List<Long> observedPresentationTimesUs = new CopyOnWriteArrayList<>();
    final AtomicLong reportedPositionUs = new AtomicLong(CURRENT_POSITION_NOT_SET);
    final AtomicReference<InitializationException> initExceptionToThrow = new AtomicReference<>();
    final AtomicReference<WriteException> writeExceptionToThrow = new AtomicReference<>();
    final AtomicBoolean triggerDiscontinuityOnNextBuffer = new AtomicBoolean(false);
    final AtomicInteger releaseCount = new AtomicInteger(0);
    final AtomicInteger formatSupportCalls = new AtomicInteger(0);
    final AtomicInteger audioSessionId = new AtomicInteger(C.AUDIO_SESSION_ID_UNSET);
    final AtomicBoolean tunnelingEnabled = new AtomicBoolean(false);

    @Nullable volatile CountDownLatch handleBufferLatch;
    @Nullable volatile CountDownLatch blockHandleBufferUntil;
    @Nullable volatile CountDownLatch onHandleBufferDone;
    @Nullable volatile CountDownLatch onFlushStarted;
    @Nullable volatile CountDownLatch blockFlushUntil;
    @Nullable volatile java.util.function.BooleanSupplier customHandleBuffer;
    @Nullable volatile java.util.function.Consumer<ByteBuffer> onBufferConsumed;

    @Nullable private Listener listener;
    private PlaybackParameters playbackParameters = PlaybackParameters.DEFAULT;
    private boolean skipSilenceEnabled;
    private AudioAttributes audioAttributes = AudioAttributes.DEFAULT;
    private boolean hasPending;
    private boolean ended;

    private void recordThread() {
      observedThreadNames.add(Thread.currentThread().getName());
    }

    @Override
    public void setListener(Listener listener) {
      this.listener = listener;
    }

    @Override
    public boolean supportsFormat(Format format) {
      return getFormatSupport(format) != SINK_FORMAT_UNSUPPORTED;
    }

    @Override
    public int getFormatSupport(Format format) {
      recordThread();
      formatSupportCalls.incrementAndGet();
      return SINK_FORMAT_SUPPORTED_DIRECTLY;
    }

    @Override
    public long getCurrentPositionUs(boolean sourceEnded) {
      recordThread();
      return reportedPositionUs.get();
    }

    @Override
    public void configure(Format inputFormat, int specifiedBufferSize, @Nullable int[] outputChannels) {
      recordThread();
      observedEvents.add("configure");
    }

    @Override
    public void play() {
      recordThread();
    }

    @Override
    public void handleDiscontinuity() {
      recordThread();
    }

    @Override
    public boolean handleBuffer(
        ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount)
        throws InitializationException, WriteException {
      recordThread();
      observedEvents.add("handleBuffer");
      CountDownLatch entered = handleBufferLatch;
      if (entered != null) {
        entered.countDown();
      }
      CountDownLatch blocker = blockHandleBufferUntil;
      if (blocker != null) {
        try {
          blocker.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      try {
        java.util.function.BooleanSupplier custom = customHandleBuffer;
        if (custom != null) {
          boolean ok = custom.getAsBoolean();
          if (ok) {
            buffer.position(buffer.limit());
            hasPending = true;
          }
          return ok;
        }
        InitializationException initEx = initExceptionToThrow.getAndSet(null);
        if (initEx != null) {
          throw initEx;
        }
        WriteException writeEx = writeExceptionToThrow.getAndSet(null);
        if (writeEx != null) {
          throw writeEx;
        }
        if (triggerDiscontinuityOnNextBuffer.getAndSet(false) && listener != null) {
          listener.onPositionDiscontinuity();
        }
        observedPresentationTimesUs.add(presentationTimeUs);
        java.util.function.Consumer<ByteBuffer> consumer = onBufferConsumed;
        if (consumer != null) {
          consumer.accept(buffer);
        }
        buffer.position(buffer.limit());
        hasPending = true;
        return true;
      } finally {
        CountDownLatch done = onHandleBufferDone;
        if (done != null) {
          done.countDown();
        }
      }
    }

    @Override
    public void playToEndOfStream() {
      recordThread();
      ended = true;
      hasPending = false;
    }

    @Override
    public boolean isEnded() {
      return ended;
    }

    @Override
    public boolean hasPendingData() {
      return hasPending;
    }

    @Override
    public void setPlaybackParameters(PlaybackParameters playbackParameters) {
      this.playbackParameters = playbackParameters;
    }

    @Override
    public PlaybackParameters getPlaybackParameters() {
      return playbackParameters;
    }

    @Override
    public void setSkipSilenceEnabled(boolean skipSilenceEnabled) {
      this.skipSilenceEnabled = skipSilenceEnabled;
    }

    @Override
    public boolean getSkipSilenceEnabled() {
      return skipSilenceEnabled;
    }

    @Override
    public void setAudioAttributes(AudioAttributes audioAttributes) {
      this.audioAttributes = audioAttributes;
    }

    @Override
    public AudioAttributes getAudioAttributes() {
      return audioAttributes;
    }

    @Override
    public void setAudioSessionId(int audioSessionId) {
      recordThread();
      this.audioSessionId.set(audioSessionId);
      hasPending = false;
      ended = false;
    }

    @Override
    public void setAuxEffectInfo(AuxEffectInfo auxEffectInfo) {}

    @Override
    public long getAudioTrackBufferSizeUs() {
      return 250_000L;
    }

    @Override
    public void enableTunnelingV21() {
      recordThread();
      if (audioSessionId.get() == C.AUDIO_SESSION_ID_UNSET) {
        throw new IllegalStateException("Audio session ID must be set before enabling tunneling");
      }
      tunnelingEnabled.set(true);
      hasPending = false;
      ended = false;
    }

    @Override
    public void disableTunneling() {
      recordThread();
      tunnelingEnabled.set(false);
      hasPending = false;
      ended = false;
    }

    @Override
    public void setVolume(float volume) {}

    @Override
    public void pause() {
      recordThread();
    }

    @Override
    public void flush() {
      recordThread();
      observedEvents.add("flush");
      CountDownLatch started = onFlushStarted;
      if (started != null) {
        started.countDown();
      }
      CountDownLatch gate = blockFlushUntil;
      if (gate != null) {
        try {
          gate.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      hasPending = false;
      ended = false;
    }

    @Override
    public void reset() {
      recordThread();
      observedEvents.add("reset");
      hasPending = false;
      ended = false;
    }

    @Override
    public void release() {
      releaseCount.incrementAndGet();
    }
  }
}
