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

import static androidx.media3.common.util.Assertions.checkNotNull;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.media.AudioDeviceInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.AuxEffectInfo;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.NuvioEngineConfig;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.PlaybackRealtimeThread;
import androidx.media3.exoplayer.analytics.PlayerId;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * An {@link AudioSink} wrapper that decouples all blocking audio operations (AudioTrack
 * initialization, audio processor pipelines, {@code AudioTrack.write}, and AudioTrack
 * timestamp/playhead Binder polling) onto a dedicated high-priority audio thread.
 *
 * <p>On the ExoPlayer playback thread:
 *
 * <ul>
 *   <li>{@link #handleBuffer} copies audio samples into a bounded pool of reusable buffers and
 *       returns immediately without blocking on AudioFlinger/HAL stalls.
 *   <li>{@link #getCurrentPositionUs} performs lock-free, zero-Binder {@code nanoTime}
 *       extrapolation from the most recent clock snapshot published by the audio thread.
 *   <li>All calls to the wrapped {@code delegate} execute exclusively on the dedicated audio
 *       thread, preserving {@link DefaultAudioSink}'s single-looper affinity.
 * </ul>
 */
@UnstableApi
public final class AsyncAudioSink implements AudioSink {

  private static final String THREAD_NAME = "ExoPlayer:AudioSink";

  private static final int MSG_DRAIN = 1;
  private static final int MSG_POLL_CLOCK = 2;
  private static final int MSG_PLAY = 3;
  private static final int MSG_PAUSE = 4;
  private static final int MSG_FLUSH = 5;
  private static final int MSG_RESET = 6;
  private static final int MSG_RELEASE = 7;
  private static final int MSG_RUNNABLE = 8;
  private static final int MSG_PREWARM = 9;

  /** Maximum number of audio chunks buffered ahead on the async queue. */
  private static final int MAX_QUEUED_CHUNKS = 16;

  /** Maximum total bytes buffered ahead on the async queue (unless queue is empty). */
  private static final int MAX_QUEUED_BYTES = 256 * 1024;

  /** Minimum capacity of a single buffer allocated for the reuse pool. */
  private static final int MIN_POOLED_BUFFER_CAPACITY = 16 * 1024;

  /** Maximum capacity of a single buffer kept in the reuse pool. */
  private static final int MAX_POOLED_BUFFER_CAPACITY = 256 * 1024;

  /** Fast drain retry delay during startup/seek lock-in or with shallow AudioTrack buffers. */
  private static final long DRAIN_RETRY_DELAY_FAST_MS = 5L;

  /** Bounds for adaptive steady-state drain retry when the AudioTrack hardware buffer is full. */
  private static final long DRAIN_RETRY_DELAY_STEADY_MIN_MS = 14L;
  private static final long DRAIN_RETRY_DELAY_STEADY_MAX_MS = 18L;

  /** Delay between retries while waiting for AudioTrack initialization in paused state. */
  private static final long DRAIN_RETRY_DELAY_PAUSED_INIT_MS = 20L;

  /**
   * Steady-state interval for polling AudioTrack clock/playhead on the dedicated audio thread
   * (25 ms, coalesced with drain wakeups to avoid redundant timer triggers).
   */
  private static final long CLOCK_POLL_INTERVAL_MS = 25L;

  /**
   * High-frequency clock poll interval (8 ms) used during startup/seek lock-in so passthrough
   * playhead bursts are smoothed cleanly with half the wakeups of 4 ms polling.
   */
  private static final long CLOCK_POLL_INTERVAL_STARTUP_MS = 8L;

  /** Number of consecutive advancing clock samples required to exit fast PCM startup polling. */
  private static final int STARTUP_ADVANCING_POLLS_TO_LOCK = 3;

  /** Duration after play/flush during which passthrough fast startup clock polling stays active. */
  private static final long CLOCK_STARTUP_FAST_POLL_WINDOW_MS = 320L;

  /** Minimum hardware AudioTrack buffer duration (150 ms) required to batch steady-state drains. */
  private static final long MIN_BATCHING_AUDIO_BUFFER_US = 150_000L;

  /**
   * Queued chunk threshold at which steady-state batching immediately wakes the audio thread so
   * playback bursts never wait for the periodic 25 ms tick.
   */
  private static final int STEADY_BATCH_WAKE_CHUNKS = 3;

  /**
   * Maximum duration over which {@link #getCurrentPositionUs} extrapolates between polls (250 ms),
   * covering transient HAL/Binder write stalls without freezing and then jumping the clock.
   */
  private static final long MAX_CLOCK_EXTRAPOLATION_US = 250_000L;

  /** Timeout for synchronous teardown operations on release/reset. */
  private static final long SYNC_TEARDOWN_TIMEOUT_MS = 2_000L;

  /** Power-of-2 capacity of the zero-allocation lock-free ring queue. */
  private static final int PENDING_RING_CAPACITY = 256;

  /** Fixed capacity of the zero-allocation QueueItem pool. */
  private static final int ITEM_POOL_CAPACITY = 64;

  /**
   * Bounded lock-free MPMC ring buffer (Vyukov algorithm) with zero heap allocations on {@link
   * #offer} and {@link #poll}, preventing ART {@code WaitForGcToComplete} stalls on the playback
   * and audio threads under high-bitrate 4K heap pressure.
   */
  private static final class LockFreeRing<T> {
    private final int mask;
    private final Object[] slots;
    private final AtomicLongArray seq;
    private final AtomicLong enqueuePos = new AtomicLong(0);
    private final AtomicLong dequeuePos = new AtomicLong(0);

    LockFreeRing(int powerOfTwoCapacity) {
      this.mask = powerOfTwoCapacity - 1;
      this.slots = new Object[powerOfTwoCapacity];
      this.seq = new AtomicLongArray(powerOfTwoCapacity);
      for (int i = 0; i < powerOfTwoCapacity; i++) {
        seq.set(i, i);
      }
    }

    boolean offer(T item) {
      while (true) {
        long pos = enqueuePos.get();
        int idx = (int) (pos & mask);
        long dif = seq.get(idx) - pos;
        if (dif == 0L) {
          if (enqueuePos.compareAndSet(pos, pos + 1L)) {
            slots[idx] = item;
            seq.set(idx, pos + 1L);
            return true;
          }
        } else if (dif < 0L) {
          return false;
        }
      }
    }

    @SuppressWarnings("unchecked")
    @Nullable
    T poll() {
      while (true) {
        long pos = dequeuePos.get();
        int idx = (int) (pos & mask);
        long dif = seq.get(idx) - (pos + 1L);
        if (dif == 0L) {
          if (dequeuePos.compareAndSet(pos, pos + 1L)) {
            T item = (T) slots[idx];
            slots[idx] = null;
            seq.set(idx, pos + mask + 1L);
            return item;
          }
        } else if (dif < 0L) {
          return null;
        }
      }
    }

    boolean isEmpty() {
      long pos = dequeuePos.get();
      int idx = (int) (pos & mask);
      return (seq.get(idx) - (pos + 1L)) < 0L;
    }
  }

  private static final class QueueItem {
    static final int TYPE_DATA = 1;
    static final int TYPE_DISCONTINUITY = 2;
    static final int TYPE_OUTPUT_STREAM_OFFSET = 3;
    static final int TYPE_CONFIGURE = 4;
    static final int TYPE_END_OF_STREAM = 5;

    int type;
    int generation;
    @Nullable ByteBuffer buffer;
    int byteCount;
    long timeUs;
    int encodedAccessUnitCount;
    @Nullable Format format;
    int specifiedBufferSize;
    @Nullable int[] outputChannels;

    void set(
        int type,
        int generation,
        @Nullable ByteBuffer buffer,
        int byteCount,
        long timeUs,
        int encodedAccessUnitCount,
        @Nullable Format format,
        int specifiedBufferSize,
        @Nullable int[] outputChannels) {
      this.type = type;
      this.generation = generation;
      this.buffer = buffer;
      this.byteCount = byteCount;
      this.timeUs = timeUs;
      this.encodedAccessUnitCount = encodedAccessUnitCount;
      this.format = format;
      this.specifiedBufferSize = specifiedBufferSize;
      this.outputChannels = outputChannels;
    }

    void clearReferences() {
      buffer = null;
      format = null;
      outputChannels = null;
    }
  }

  private final AudioSink delegate;
  private final Object threadLock = new Object();

  @GuardedBy("threadLock")
  @Nullable
  private HandlerThread audioThread;

  @GuardedBy("threadLock")
  @Nullable
  private Handler audioHandler;

  // Lock-free snapshots of audioHandler / audioLooper for 5ms drain & 25ms clock poll hot paths.
  @Nullable private volatile Handler audioHandlerVolatile;
  @Nullable private volatile Looper audioLooperVolatile;

  private final AtomicBoolean released = new AtomicBoolean(false);
  private final AtomicInteger generation = new AtomicInteger(0);

  // 100% lock-free, zero-allocation queue and reuse pools between ExoPlayer:Playback and
  // ExoPlayer:AudioSink. Avoids both Java monitor contention and per-buffer heap allocations
  // that can stall on ART WaitForGcToComplete under high-bitrate 4K heap pressure.
  private final LockFreeRing<QueueItem> pendingItems = new LockFreeRing<>(PENDING_RING_CAPACITY);
  private final ConcurrentLinkedQueue<QueueItem> overflowItems = new ConcurrentLinkedQueue<>();
  private final AtomicInteger overflowItemCount = new AtomicInteger(0);
  private final AtomicReferenceArray<@NullableType QueueItem> itemPool =
      new AtomicReferenceArray<>(ITEM_POOL_CAPACITY);
  private final AtomicReferenceArray<@NullableType ByteBuffer> bufferPool =
      new AtomicReferenceArray<>(MAX_QUEUED_CHUNKS);
  private final AtomicInteger queuedDataChunks = new AtomicInteger(0);
  private final AtomicInteger queuedDataBytes = new AtomicInteger(0);

  // Accessed only on the dedicated audio thread (delegateGeneration is read lock-free on playback).
  @Nullable private QueueItem activeDataItem;
  private boolean delegatePlaying;
  private boolean delegateEndOfStreamRequested;
  private volatile int delegateGeneration;
  private boolean delayedDrainScheduledOnAudioThread;
  private long lastPolledPositionUs = CURRENT_POSITION_NOT_SET;
  private long lastClockPollRealtimeMs = C.TIME_UNSET;
  private long playStartRealtimeMs = C.TIME_UNSET;
  private int advancingClockPolls;
  private boolean lastConfiguredPassthrough;
  private boolean hasPendingErrorOnAudioThread;

  // Seqlock-protected zero-allocation clock snapshot (single writer: ExoPlayer:AudioSink;
  // lock-free reader: ExoPlayer:Playback).
  private final AtomicInteger clockSeq = new AtomicInteger(0);
  private long snapPositionUs = CURRENT_POSITION_NOT_SET;
  private long snapSystemTimeNano;
  private float snapPlaybackSpeed = 1f;
  private boolean snapAdvancing;
  private int snapGeneration = -1;

  // Playback-thread-only frozen pause snapshot (avoids mutating seqlock from playback thread).
  private long pausedFrozenPositionUs = CURRENT_POSITION_NOT_SET;
  private int pausedFrozenGeneration = -1;

  // Shared volatile state read lock-free from the ExoPlayer playback thread.
  private final AtomicBoolean drainScheduled = new AtomicBoolean(false);
  private volatile boolean steadyClockPollActive;
  private volatile boolean hasActiveDataItem;
  private volatile Clock clock = Clock.DEFAULT;
  @Nullable private volatile Listener externalListener;
  private volatile boolean clientPlaying;
  private volatile boolean clientEndOfStreamRequested;
  private volatile boolean delegateHasPendingData;
  private volatile boolean delegateIsEnded;
  private volatile PlaybackParameters cachedPlaybackParameters = PlaybackParameters.DEFAULT;
  private volatile boolean cachedSkipSilenceEnabled;
  @Nullable private volatile AudioAttributes cachedAudioAttributes;
  private volatile long cachedAudioTrackBufferSizeUs = C.TIME_UNSET;

  private final AtomicReference<@NullableType InitializationException> pendingInitException =
      new AtomicReference<>();
  private final AtomicReference<@NullableType WriteException> pendingWriteException =
      new AtomicReference<>();
  private final AtomicReference<@NullableType ConfigurationException> pendingConfigException =
      new AtomicReference<>();
  private final AtomicReference<@NullableType RuntimeException> pendingRuntimeException =
      new AtomicReference<>();

  private final AtomicBoolean pendingPositionDiscontinuity = new AtomicBoolean(false);
  private final AtomicBoolean pendingSilenceSkipped = new AtomicBoolean(false);

  private static final class FormatSupportKey {
    @Nullable final String sampleMimeType;
    @Nullable final String codecs;
    final int pcmEncoding;
    final int channelCount;
    final int sampleRate;
    private final int hashCode;

    FormatSupportKey(Format format) {
      this.sampleMimeType = format.sampleMimeType;
      this.codecs = format.codecs;
      this.pcmEncoding = format.pcmEncoding;
      this.channelCount = format.channelCount;
      this.sampleRate = format.sampleRate;
      int h = Objects.hashCode(sampleMimeType);
      h = 31 * h + Objects.hashCode(codecs);
      h = 31 * h + pcmEncoding;
      h = 31 * h + channelCount;
      h = 31 * h + sampleRate;
      this.hashCode = h;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof FormatSupportKey)) {
        return false;
      }
      FormatSupportKey other = (FormatSupportKey) o;
      return pcmEncoding == other.pcmEncoding
          && channelCount == other.channelCount
          && sampleRate == other.sampleRate
          && Objects.equals(sampleMimeType, other.sampleMimeType)
          && Objects.equals(codecs, other.codecs);
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }

  private final ConcurrentHashMap<FormatSupportKey, Integer> formatSupportCache =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<FormatSupportKey, AudioOffloadSupport> offloadSupportCache =
      new ConcurrentHashMap<>();
  private volatile boolean cachedTunnelingEnabled;
  private volatile int cachedOffloadMode = OFFLOAD_MODE_DISABLED;

  /**
   * Wraps the given {@link AudioSink} so all blocking audio operations run on a dedicated
   * high-priority thread.
   */
  public AsyncAudioSink(AudioSink delegate) {
    this.delegate = checkNotNull(delegate);
    this.cachedAudioAttributes = delegate.getAudioAttributes();
    this.cachedPlaybackParameters = delegate.getPlaybackParameters();
    this.cachedSkipSilenceEnabled = delegate.getSkipSilenceEnabled();
  }

  /**
   * Returns an {@link AsyncAudioSink} wrapping {@code sink}, or {@code sink} directly if it is
   * already an {@link AsyncAudioSink}.
   */
  public static AudioSink wrap(AudioSink sink) {
    if (sink instanceof AsyncAudioSink) {
      return sink;
    }
    return new AsyncAudioSink(sink);
  }

  private @Nullable Handler ensureAudioHandler() {
    if (released.get()) {
      return null;
    }
    @Nullable Handler existing = audioHandlerVolatile;
    if (existing != null) {
      return existing;
    }
    synchronized (threadLock) {
      if (released.get()) {
        return null;
      }
      if (audioHandler == null) {
        HandlerThread thread =
            NuvioEngineConfig.get().isRealtimePlaybackThreadEnabled()
                ? new PlaybackRealtimeThread(
                    THREAD_NAME, PlaybackRealtimeThread.PRIORITY_PLAYBACK_LOOP)
                : new HandlerThread(THREAD_NAME, Process.THREAD_PRIORITY_AUDIO);
        thread.start();
        Looper looper = thread.getLooper();
        Handler handler =
            new Handler(looper) {
              @Override
              public void handleMessage(Message msg) {
                handleAudioThreadMessage(msg);
              }
            };
        audioThread = thread;
        audioHandler = handler;
        audioLooperVolatile = looper;
        audioHandlerVolatile = handler;
      }
      return audioHandler;
    }
  }

  private boolean isOnAudioThread() {
    @Nullable Looper looper = audioLooperVolatile;
    return looper != null && Looper.myLooper() == looper;
  }

  private boolean runSync(Runnable action, long timeoutMs) {
    if (isOnAudioThread()) {
      action.run();
      return true;
    }
    Handler handler = ensureAudioHandler();
    if (handler == null) {
      return false;
    }
    ConditionVariable done = new ConditionVariable();
    AtomicReference<@NullableType RuntimeException> uncaught = new AtomicReference<>();
    boolean posted =
        handler.post(
            () -> {
              try {
                action.run();
              } catch (RuntimeException e) {
                uncaught.set(e);
              } finally {
                done.open();
              }
            });
    if (!posted) {
      return false;
    }
    try {
      if (timeoutMs > 0) {
        done.block(timeoutMs);
      } else {
        done.block();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
    RuntimeException exception = uncaught.get();
    if (exception != null) {
      throw exception;
    }
    return true;
  }

  private void postToAudioThread(Runnable action) {
    if (isOnAudioThread()) {
      action.run();
      return;
    }
    Handler handler = ensureAudioHandler();
    if (handler != null) {
      handler.obtainMessage(MSG_RUNNABLE, action).sendToTarget();
    }
  }

  // AudioSink implementation.

  @Override
  public void setListener(Listener listener) {
    this.externalListener = listener;
    postToAudioThread(() -> delegate.setListener(new InternalListener()));
  }

  @Override
  public void setPlayerId(@Nullable PlayerId playerId) {
    postToAudioThread(() -> delegate.setPlayerId(playerId));
  }

  @Override
  public void setClock(Clock clock) {
    this.clock = clock;
    postToAudioThread(() -> delegate.setClock(clock));
  }

  @Override
  public boolean supportsFormat(Format format) {
    return getFormatSupport(format) != SINK_FORMAT_UNSUPPORTED;
  }

  @Override
  public @SinkFormatSupport int getFormatSupport(Format format) {
    FormatSupportKey key = new FormatSupportKey(format);
    @Nullable Integer cached = formatSupportCache.get(key);
    if (cached != null) {
      return cached;
    }
    AtomicInteger result = new AtomicInteger(SINK_FORMAT_UNSUPPORTED);
    if (runSync(() -> result.set(delegate.getFormatSupport(format)), /* timeoutMs= */ 0)) {
      int support = result.get();
      formatSupportCache.put(key, support);
      return support;
    }
    return SINK_FORMAT_UNSUPPORTED;
  }

  @Override
  public AudioOffloadSupport getFormatOffloadSupport(Format format) {
    FormatSupportKey key = new FormatSupportKey(format);
    @Nullable AudioOffloadSupport cached = offloadSupportCache.get(key);
    if (cached != null) {
      return cached;
    }
    AtomicReference<AudioOffloadSupport> result =
        new AtomicReference<>(AudioOffloadSupport.DEFAULT_UNSUPPORTED);
    if (runSync(() -> result.set(delegate.getFormatOffloadSupport(format)), /* timeoutMs= */ 0)) {
      AudioOffloadSupport support = result.get();
      offloadSupportCache.put(key, support);
      return support;
    }
    return AudioOffloadSupport.DEFAULT_UNSUPPORTED;
  }

  @Override
  public long getCurrentPositionUs(boolean sourceEnded) {
    drainPlaybackThreadListenerFlags();
    int currentGen = generation.get();
    if (!clientPlaying
        && pausedFrozenGeneration == currentGen
        && pausedFrozenPositionUs != CURRENT_POSITION_NOT_SET) {
      return pausedFrozenPositionUs;
    }
    for (int attempt = 0; attempt < 8; attempt++) {
      int s1 = clockSeq.get();
      if ((s1 & 1) != 0) {
        Thread.yield();
        continue;
      }
      long posUs = snapPositionUs;
      long sysNs = snapSystemTimeNano;
      float speed = snapPlaybackSpeed;
      boolean advancing = snapAdvancing;
      int snapGen = snapGeneration;
      if (clockSeq.get() == s1) {
        if (snapGen != currentGen || posUs == CURRENT_POSITION_NOT_SET) {
          return CURRENT_POSITION_NOT_SET;
        }
        if (!clientPlaying || !advancing) {
          return posUs;
        }
        long elapsedUs = (clock.nanoTime() - sysNs) / 1000L;
        if (elapsedUs <= 0L) {
          return posUs;
        }
        long cappedElapsedUs = min(elapsedUs, MAX_CLOCK_EXTRAPOLATION_US);
        return posUs + Util.getMediaDurationForPlayoutDuration(cappedElapsedUs, speed);
      }
    }
    return CURRENT_POSITION_NOT_SET;
  }

  private boolean hasAdvancingClockSnapshotForGen(int currentGen) {
    for (int attempt = 0; attempt < 4; attempt++) {
      int s1 = clockSeq.get();
      if ((s1 & 1) != 0) {
        continue;
      }
      boolean advancing = snapAdvancing;
      int snapGen = snapGeneration;
      long posUs = snapPositionUs;
      if (clockSeq.get() == s1) {
        return snapGen == currentGen && posUs != CURRENT_POSITION_NOT_SET && advancing;
      }
    }
    return false;
  }

  @Override
  public void configure(Format inputFormat, int specifiedBufferSize, @Nullable int[] outputChannels)
      throws ConfigurationException {
    maybeThrowConfigurationException();
    int currentGen = generation.get();
    boolean hasPendingForCurrentGen =
        delegateGeneration != currentGen
            || !isPendingItemsEmpty()
            || queuedDataChunks.get() > 0
            || hasActiveDataItem;
    if (!hasPendingForCurrentGen) {
      // Initial startup / idle configure path: configure synchronously so any
      // ConfigurationException surfaces immediately.
      boolean isEncodedFormat = !MimeTypes.AUDIO_RAW.equals(inputFormat.sampleMimeType);
      AtomicReference<@NullableType ConfigurationException> configError = new AtomicReference<>();
      runSync(
          () -> {
            try {
              delegate.configure(inputFormat, specifiedBufferSize, outputChannels);
              lastConfiguredPassthrough = isEncodedFormat;
              cachedAudioTrackBufferSizeUs = delegate.getAudioTrackBufferSizeUs();
              cachedPlaybackParameters = delegate.getPlaybackParameters();
            } catch (ConfigurationException e) {
              configError.set(e);
            }
          },
          /* timeoutMs= */ 0);
      @Nullable ConfigurationException error = configError.get();
      if (error != null) {
        throw error;
      }
      if (isEncodedFormat) {
        // Eagerly pre-create the passthrough AudioTrack asynchronously on ExoPlayer:AudioSink so
        // HAL opening overlaps in parallel with video codec init & first-frame decode.
        schedulePrewarm(currentGen);
      }
      return;
    }

    // Post-flush or seamless mid-stream format change: enqueue configure non-blockingly in FIFO
    // order so ExoPlayer:Playback never stalls behind an in-flight flush/AudioTrack Binder call.
    @Nullable
    int[] channelsCopy =
        outputChannels != null ? Arrays.copyOf(outputChannels, outputChannels.length) : null;
    QueueItem item = obtainQueueItem();
    item.set(
        QueueItem.TYPE_CONFIGURE,
        currentGen,
        null,
        0,
        0L,
        0,
        inputFormat,
        specifiedBufferSize,
        channelsCopy);
    offerPendingItem(item);
    scheduleDrain();
  }

  @Override
  public void play() {
    pausedFrozenPositionUs = CURRENT_POSITION_NOT_SET;
    pausedFrozenGeneration = -1;
    clientPlaying = true;
    Handler handler = ensureAudioHandler();
    if (handler != null) {
      handler.obtainMessage(MSG_PLAY, generation.get(), 0).sendToTarget();
    }
  }

  @Override
  public void handleDiscontinuity() {
    int currentGen = generation.get();
    QueueItem item = obtainQueueItem();
    item.set(QueueItem.TYPE_DISCONTINUITY, currentGen, null, 0, 0L, 0, null, 0, null);
    offerPendingItem(item);
    scheduleDrain();
  }

  @Override
  public boolean handleBuffer(
      ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount)
      throws InitializationException, WriteException {
    drainPlaybackThreadListenerFlags();
    maybeThrowPendingPlaybackExceptions();

    int remaining = buffer.remaining();
    if (remaining == 0) {
      return true;
    }

    int chunks = queuedDataChunks.get();
    int bytes = queuedDataBytes.get();
    if (chunks >= MAX_QUEUED_CHUNKS || (chunks > 0 && bytes + remaining > MAX_QUEUED_BYTES)) {
      // Queue is full: apply non-blocking backpressure to the playback thread.
      return false;
    }

    int currentGen = generation.get();
    ByteBuffer copy = obtainPooledBuffer(remaining);
    copy.put(buffer);
    copy.flip();

    // Increment counters before publishing to pendingItems so concurrent polling by
    // ExoPlayer:AudioSink never drives counters negative.
    int newChunks = queuedDataChunks.incrementAndGet();
    queuedDataBytes.addAndGet(remaining);
    QueueItem item = obtainQueueItem();
    item.set(
        QueueItem.TYPE_DATA,
        currentGen,
        copy,
        remaining,
        presentationTimeUs,
        encodedAccessUnitCount,
        null,
        0,
        null);
    offerPendingItem(item);
    if (shouldWakeAudioThreadImmediately(newChunks, currentGen)) {
      scheduleDrain();
    }
    return true;
  }

  private boolean shouldWakeAudioThreadImmediately(int queuedChunks, int currentGen) {
    if (queuedChunks >= STEADY_BATCH_WAKE_CHUNKS
        || !clientPlaying
        || clientEndOfStreamRequested
        || cachedAudioTrackBufferSizeUs < MIN_BATCHING_AUDIO_BUFFER_US
        || delegateGeneration != currentGen
        || !steadyClockPollActive) {
      return true;
    }
    return !hasAdvancingClockSnapshotForGen(currentGen);
  }

  @Override
  public void playToEndOfStream() throws WriteException {
    drainPlaybackThreadListenerFlags();
    maybeThrowWriteOrRuntimeException();
    if (clientEndOfStreamRequested) {
      return;
    }
    clientEndOfStreamRequested = true;
    int currentGen = generation.get();
    QueueItem item = obtainQueueItem();
    item.set(QueueItem.TYPE_END_OF_STREAM, currentGen, null, 0, 0L, 0, null, 0, null);
    offerPendingItem(item);
    scheduleDrain();
  }

  @Override
  public boolean isEnded() {
    if (!clientEndOfStreamRequested) {
      return false;
    }
    if (!isPendingItemsEmpty() || queuedDataChunks.get() > 0 || hasActiveDataItem) {
      return false;
    }
    return delegateIsEnded;
  }

  @Override
  public boolean hasPendingData() {
    if (queuedDataChunks.get() > 0 || hasActiveDataItem) {
      return true;
    }
    return delegateHasPendingData;
  }

  @Override
  public void setPlaybackParameters(PlaybackParameters playbackParameters) {
    cachedPlaybackParameters = playbackParameters;
    postToAudioThread(
        () -> {
          delegate.setPlaybackParameters(playbackParameters);
          cachedPlaybackParameters = delegate.getPlaybackParameters();
          refreshClockSnapshotOnAudioThread(/* force= */ true);
        });
  }

  @Override
  public PlaybackParameters getPlaybackParameters() {
    return cachedPlaybackParameters;
  }

  @Override
  public void setSkipSilenceEnabled(boolean skipSilenceEnabled) {
    cachedSkipSilenceEnabled = skipSilenceEnabled;
    postToAudioThread(
        () -> {
          delegate.setSkipSilenceEnabled(skipSilenceEnabled);
          cachedSkipSilenceEnabled = delegate.getSkipSilenceEnabled();
        });
  }

  @Override
  public boolean getSkipSilenceEnabled() {
    return cachedSkipSilenceEnabled;
  }

  @Override
  public void setAudioAttributes(AudioAttributes audioAttributes) {
    if (Objects.equals(cachedAudioAttributes, audioAttributes)) {
      return;
    }
    cachedAudioAttributes = audioAttributes;
    invalidateCapabilitiesCache();
    postToAudioThread(
        () -> {
          delegate.setAudioAttributes(audioAttributes);
          cachedAudioAttributes = delegate.getAudioAttributes();
        });
  }

  @Override
  @Nullable
  public AudioAttributes getAudioAttributes() {
    return cachedAudioAttributes;
  }

  @Override
  public void setAudioSessionId(int audioSessionId) {
    postToAudioThread(() -> delegate.setAudioSessionId(audioSessionId));
  }

  @Override
  public void setAuxEffectInfo(AuxEffectInfo auxEffectInfo) {
    postToAudioThread(() -> delegate.setAuxEffectInfo(auxEffectInfo));
  }

  @RequiresApi(23)
  @Override
  public void setPreferredDevice(@Nullable AudioDeviceInfo audioDeviceInfo) {
    invalidateCapabilitiesCache();
    postToAudioThread(() -> delegate.setPreferredDevice(audioDeviceInfo));
  }

  @Override
  public void setOutputStreamOffsetUs(long outputStreamOffsetUs) {
    int currentGen = generation.get();
    QueueItem item = obtainQueueItem();
    item.set(
        QueueItem.TYPE_OUTPUT_STREAM_OFFSET,
        currentGen,
        null,
        0,
        outputStreamOffsetUs,
        0,
        null,
        0,
        null);
    offerPendingItem(item);
    scheduleDrain();
  }

  @Override
  public long getAudioTrackBufferSizeUs() {
    return cachedAudioTrackBufferSizeUs;
  }

  @Override
  public void enableTunnelingV21() {
    if (cachedTunnelingEnabled) {
      return;
    }
    invalidateCapabilitiesCache();
    toggleTunnelingOnAudioThread(/* enable= */ true);
  }

  @Override
  public void disableTunneling() {
    if (!cachedTunnelingEnabled) {
      return;
    }
    invalidateCapabilitiesCache();
    toggleTunnelingOnAudioThread(/* enable= */ false);
  }

  private void toggleTunnelingOnAudioThread(boolean enable) {
    int newGen = advanceGenerationAndClearClientState();
    Handler handler = ensureAudioHandler();
    if (handler != null) {
      handler.removeMessages(MSG_DRAIN);
      handler.removeMessages(MSG_POLL_CLOCK);
      drainScheduled.set(false);
    }
    runSync(
        () -> {
          recycleActiveDataItemOnAudioThread();
          delegateEndOfStreamRequested = false;
          hasPendingErrorOnAudioThread = false;
          delayedDrainScheduledOnAudioThread = false;
          delegateGeneration = newGen;
          lastPolledPositionUs = CURRENT_POSITION_NOT_SET;
          lastClockPollRealtimeMs = C.TIME_UNSET;
          advancingClockPolls = 0;
          Handler audioThreadHandler = getAudioHandlerUnchecked();
          if (audioThreadHandler != null) {
            audioThreadHandler.removeMessages(MSG_PREWARM);
          }
          if (enable) {
            delegate.enableTunnelingV21();
          } else {
            delegate.disableTunneling();
          }
          cachedTunnelingEnabled = enable;
          delegateHasPendingData = false;
          delegateIsEnded = false;
          cachedAudioTrackBufferSizeUs = delegate.getAudioTrackBufferSizeUs();
          if (generation.get() == newGen) {
            writeClockSnapshotOnAudioThread(CURRENT_POSITION_NOT_SET, 0L, 1f, false, newGen);
          }
        },
        /* timeoutMs= */ 0);
  }

  @RequiresApi(29)
  @Override
  public void setOffloadMode(@OffloadMode int offloadMode) {
    if (cachedOffloadMode == offloadMode) {
      return;
    }
    cachedOffloadMode = offloadMode;
    invalidateCapabilitiesCache();
    postToAudioThread(() -> delegate.setOffloadMode(offloadMode));
  }

  @RequiresApi(29)
  @Override
  public void setOffloadDelayPadding(int delayInFrames, int paddingInFrames) {
    postToAudioThread(() -> delegate.setOffloadDelayPadding(delayInFrames, paddingInFrames));
  }

  @Override
  public void setVolume(float volume) {
    postToAudioThread(() -> delegate.setVolume(volume));
  }

  @Override
  public void pause() {
    // Freeze extrapolated clock immediately on playback thread without allocating.
    long frozenPosUs = getCurrentPositionUs(/* sourceEnded= */ false);
    clientPlaying = false;
    if (frozenPosUs != CURRENT_POSITION_NOT_SET) {
      pausedFrozenPositionUs = frozenPosUs;
      pausedFrozenGeneration = generation.get();
    }
    Handler handler = ensureAudioHandler();
    if (handler != null) {
      handler.obtainMessage(MSG_PAUSE, generation.get(), 0).sendToTarget();
    }
  }

  @Override
  public void flush() {
    int newGen = advanceGenerationAndClearClientState();
    Handler handler = ensureAudioHandler();
    if (handler != null) {
      handler.removeMessages(MSG_DRAIN);
      handler.removeMessages(MSG_POLL_CLOCK);
      drainScheduled.set(false);
      handler.obtainMessage(MSG_FLUSH, newGen, 0).sendToTarget();
    }
  }

  @Override
  public void reset() {
    clientPlaying = false;
    int newGen = advanceGenerationAndClearClientState();
    @Nullable Handler handler = audioHandlerVolatile;
    if (handler == null || released.get()) {
      return;
    }
    // Execute reset asynchronously on ExoPlayer:AudioSink so live audio track switches never
    // stall ExoPlayer:Playback (and active 4K60 video rendering) behind AudioTrack teardown.
    // Subsequent configure/play/handleBuffer calls carry newGen and wait in FIFO order until
    // handleResetOnAudioThread advances delegateGeneration and drains them.
    handler.removeMessages(MSG_DRAIN);
    handler.removeMessages(MSG_POLL_CLOCK);
    drainScheduled.set(false);
    handler.obtainMessage(MSG_RESET, newGen, 0, /* obj= */ null).sendToTarget();
  }

  @Override
  public void release() {
    if (!released.compareAndSet(false, true)) {
      return;
    }
    clientPlaying = false;
    advanceGenerationAndClearClientState();
    HandlerThread threadToQuit;
    Handler handlerToRelease;
    synchronized (threadLock) {
      threadToQuit = audioThread;
      handlerToRelease = audioHandler;
      audioThread = null;
      audioHandler = null;
      audioHandlerVolatile = null;
      audioLooperVolatile = null;
    }
    if (handlerToRelease != null && threadToQuit != null) {
      if (Looper.myLooper() == threadToQuit.getLooper()) {
        delegate.release();
        threadToQuit.quitSafely();
      } else {
        ConditionVariable done = new ConditionVariable();
        handlerToRelease.removeCallbacksAndMessages(null);
        if (handlerToRelease.sendMessage(handlerToRelease.obtainMessage(MSG_RELEASE, done))) {
          try {
            done.block(SYNC_TEARDOWN_TIMEOUT_MS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
        threadToQuit.quitSafely();
      }
    } else {
      // Never started the audio thread; release delegate directly.
      delegate.release();
    }
    clearPoolsAndCaches();
  }

  // Internal queue & error helpers (playback thread).

  private int advanceGenerationAndClearClientState() {
    int newGen = generation.incrementAndGet();
    steadyClockPollActive = false;
    pausedFrozenPositionUs = CURRENT_POSITION_NOT_SET;
    pausedFrozenGeneration = -1;
    clientEndOfStreamRequested = false;
    delegateHasPendingData = false;
    delegateIsEnded = false;
    pendingInitException.set(null);
    pendingWriteException.set(null);
    pendingConfigException.set(null);
    pendingRuntimeException.set(null);
    pendingPositionDiscontinuity.set(false);
    pendingSilenceSkipped.set(false);
    QueueItem item;
    while ((item = pollPendingItem()) != null) {
      if (item.type == QueueItem.TYPE_DATA) {
        decrementQueuedDataCounters(item.byteCount);
      }
      if (item.buffer != null) {
        recycleBuffer(item.buffer);
      }
      recycleQueueItem(item);
    }
    return newGen;
  }

  private void offerPendingItem(QueueItem item) {
    if (!pendingItems.offer(item)) {
      overflowItems.offer(item);
      overflowItemCount.incrementAndGet();
    }
  }

  @Nullable
  private QueueItem pollPendingItem() {
    @Nullable QueueItem item = pendingItems.poll();
    if (item != null) {
      return item;
    }
    if (overflowItemCount.get() > 0) {
      item = overflowItems.poll();
      if (item != null) {
        overflowItemCount.decrementAndGet();
      }
    }
    return item;
  }

  private boolean isPendingItemsEmpty() {
    return pendingItems.isEmpty() && overflowItemCount.get() == 0;
  }

  private QueueItem obtainQueueItem() {
    for (int i = 0; i < ITEM_POOL_CAPACITY; i++) {
      @Nullable QueueItem item = itemPool.get(i);
      if (item != null && itemPool.compareAndSet(i, item, null)) {
        return item;
      }
    }
    return new QueueItem();
  }

  private void recycleQueueItem(QueueItem item) {
    item.clearReferences();
    if (released.get()) {
      return;
    }
    for (int i = 0; i < ITEM_POOL_CAPACITY; i++) {
      if (itemPool.get(i) == null && itemPool.compareAndSet(i, null, item)) {
        return;
      }
    }
  }

  private void clearPoolsAndCaches() {
    externalListener = null;
    for (int i = 0; i < MAX_QUEUED_CHUNKS; i++) {
      bufferPool.set(i, null);
    }
    for (int i = 0; i < ITEM_POOL_CAPACITY; i++) {
      itemPool.set(i, null);
    }
    formatSupportCache.clear();
    offloadSupportCache.clear();
  }

  private void decrementQueuedDataCounters(int byteCount) {
    queuedDataChunks.decrementAndGet();
    queuedDataBytes.addAndGet(-byteCount);
  }

  private void scheduleDrain() {
    if (drainScheduled.compareAndSet(false, true)) {
      Handler handler = ensureAudioHandler();
      if (handler != null) {
        handler.sendEmptyMessage(MSG_DRAIN);
      } else {
        drainScheduled.set(false);
      }
    }
  }

  private void schedulePrewarm(int expectedGen) {
    Handler handler = ensureAudioHandler();
    if (handler != null) {
      handler.removeMessages(MSG_PREWARM);
      handler.obtainMessage(MSG_PREWARM, expectedGen, 0).sendToTarget();
    }
  }

  private void invalidateCapabilitiesCache() {
    formatSupportCache.clear();
    offloadSupportCache.clear();
  }

  private void drainPlaybackThreadListenerFlags() {
    @Nullable Listener listener = externalListener;
    if (listener == null) {
      return;
    }
    if (pendingPositionDiscontinuity.getAndSet(false)) {
      listener.onPositionDiscontinuity();
    }
    if (pendingSilenceSkipped.getAndSet(false)) {
      listener.onSilenceSkipped();
    }
  }

  private void maybeThrowConfigurationException() throws ConfigurationException {
    @Nullable ConfigurationException configException = pendingConfigException.getAndSet(null);
    if (configException != null) {
      throw configException;
    }
  }

  private void maybeThrowPendingPlaybackExceptions() throws InitializationException, WriteException {
    @Nullable InitializationException initException = pendingInitException.getAndSet(null);
    if (initException != null) {
      throw initException;
    }
    maybeThrowWriteOrRuntimeException();
  }

  private void maybeThrowWriteOrRuntimeException() throws WriteException {
    @Nullable WriteException writeException = pendingWriteException.getAndSet(null);
    if (writeException != null) {
      throw writeException;
    }
    @Nullable RuntimeException runtimeException = pendingRuntimeException.getAndSet(null);
    if (runtimeException != null) {
      throw runtimeException;
    }
    @Nullable InitializationException initException = pendingInitException.getAndSet(null);
    if (initException != null) {
      int errorCode =
          initException.audioTrackState != 0
              ? initException.audioTrackState
              : android.media.AudioTrack.ERROR;
      throw new WriteException(errorCode, initException.format, initException.isRecoverable);
    }
    @Nullable ConfigurationException configException = pendingConfigException.getAndSet(null);
    if (configException != null) {
      throw new WriteException(
          android.media.AudioTrack.ERROR, configException.format, /* isRecoverable= */ false);
    }
  }

  private static int computePooledAllocationSize(int requiredCapacity) {
    if (requiredCapacity <= MIN_POOLED_BUFFER_CAPACITY) {
      return MIN_POOLED_BUFFER_CAPACITY;
    }
    if (requiredCapacity > MAX_POOLED_BUFFER_CAPACITY) {
      return requiredCapacity;
    }
    int nextPowerOfTwo = Integer.highestOneBit(requiredCapacity - 1) << 1;
    return min(nextPowerOfTwo, MAX_POOLED_BUFFER_CAPACITY);
  }

  private ByteBuffer obtainPooledBuffer(int requiredCapacity) {
    ByteBuffer candidate = null;
    int smallestSlot = -1;
    @Nullable ByteBuffer smallestBuf = null;
    for (int i = 0; i < MAX_QUEUED_CHUNKS; i++) {
      @Nullable ByteBuffer buf = bufferPool.get(i);
      if (buf == null) {
        continue;
      }
      int cap = buf.capacity();
      if (cap >= requiredCapacity && bufferPool.compareAndSet(i, buf, null)) {
        candidate = buf;
        break;
      }
      if (smallestBuf == null || cap < smallestBuf.capacity()) {
        smallestSlot = i;
        smallestBuf = buf;
      }
    }
    if (candidate == null) {
      // Evict the smallest undersized slot so growing packet sizes (e.g. VBR E-AC3/TrueHD or
      // stereo -> 5.1/7.1 PCM switches) converge the pool instead of allocating a new
      // DirectByteBuffer on every audio frame forever.
      if (smallestSlot >= 0 && smallestBuf != null) {
        bufferPool.compareAndSet(smallestSlot, smallestBuf, null);
      }
      int allocSize = computePooledAllocationSize(requiredCapacity);
      candidate = ByteBuffer.allocateDirect(allocSize).order(ByteOrder.LITTLE_ENDIAN);
    } else {
      candidate.clear();
      candidate.order(ByteOrder.LITTLE_ENDIAN);
    }
    return candidate;
  }

  private void recycleBuffer(ByteBuffer buffer) {
    if (released.get() || buffer.capacity() > MAX_POOLED_BUFFER_CAPACITY) {
      return;
    }
    buffer.clear();
    int cap = buffer.capacity();
    int smallestSlot = -1;
    @Nullable ByteBuffer smallestBuf = null;
    for (int i = 0; i < MAX_QUEUED_CHUNKS; i++) {
      @Nullable ByteBuffer existing = bufferPool.get(i);
      if (existing == null) {
        if (bufferPool.compareAndSet(i, null, buffer)) {
          return;
        }
        continue;
      }
      int existingCap = existing.capacity();
      if (existingCap < cap && (smallestBuf == null || existingCap < smallestBuf.capacity())) {
        smallestSlot = i;
        smallestBuf = existing;
      }
    }
    // Pool is full: replace the smallest undersized buffer so larger reusable buffers are kept.
    if (smallestSlot >= 0 && smallestBuf != null) {
      bufferPool.compareAndSet(smallestSlot, smallestBuf, buffer);
    }
  }

  // Dedicated audio thread handlers.

  private void handleAudioThreadMessage(Message msg) {
    switch (msg.what) {
      case MSG_DRAIN:
        drainQueueOnAudioThread();
        break;
      case MSG_POLL_CLOCK:
        pollClockOnAudioThread();
        break;
      case MSG_PLAY:
        handlePlayOnAudioThread();
        break;
      case MSG_PAUSE:
        handlePauseOnAudioThread();
        break;
      case MSG_FLUSH:
        handleFlushOnAudioThread(msg.arg1);
        break;
      case MSG_RESET:
        handleResetOnAudioThread(msg.arg1, (ConditionVariable) msg.obj);
        break;
      case MSG_RELEASE:
        handleReleaseOnAudioThread((ConditionVariable) msg.obj);
        break;
      case MSG_RUNNABLE:
        ((Runnable) msg.obj).run();
        break;
      case MSG_PREWARM:
        handlePrewarmOnAudioThread(msg.arg1);
        break;
      default:
        break;
    }
  }

  private void handlePrewarmOnAudioThread(int expectedGen) {
    if (released.get()
        || hasPendingErrorOnAudioThread
        || !lastConfiguredPassthrough
        || generation.get() != expectedGen) {
      return;
    }
    if (delegate instanceof DefaultAudioSink) {
      if (((DefaultAudioSink) delegate).prewarmPassthroughAudioTrack()) {
        long bufferSizeUs = delegate.getAudioTrackBufferSizeUs();
        if (bufferSizeUs != C.TIME_UNSET) {
          cachedAudioTrackBufferSizeUs = bufferSizeUs;
        }
      }
    }
  }

  private void handlePlayOnAudioThread() {
    if (!clientPlaying || released.get()) {
      return;
    }
    delegatePlaying = true;
    playStartRealtimeMs = clock.elapsedRealtime();
    advancingClockPolls = 0;
    // Drain any already-queued initial buffer first so startMediaTimeUs initializes on the
    // (pre-warmed) AudioTrack before play(), then force-seed AudioTrackPositionTracker at t=0.
    drainQueueOnAudioThread();
    delegate.play();
    refreshClockSnapshotOnAudioThread(/* force= */ true);
    scheduleClockPollIfPlaying();
  }

  private void handlePauseOnAudioThread() {
    delegatePlaying = false;
    steadyClockPollActive = false;
    delayedDrainScheduledOnAudioThread = false;
    playStartRealtimeMs = C.TIME_UNSET;
    advancingClockPolls = 0;
    Handler handler = getAudioHandlerUnchecked();
    if (handler != null) {
      handler.removeMessages(MSG_POLL_CLOCK);
      handler.removeMessages(MSG_DRAIN);
    }
    drainScheduled.set(false);
    delegate.pause();
    lastPolledPositionUs = CURRENT_POSITION_NOT_SET;
    refreshClockSnapshotOnAudioThread(/* force= */ true);
  }

  private void handleFlushOnAudioThread(int newGen) {
    recycleActiveDataItemOnAudioThread();
    delegateEndOfStreamRequested = false;
    hasPendingErrorOnAudioThread = false;
    delayedDrainScheduledOnAudioThread = false;
    lastPolledPositionUs = CURRENT_POSITION_NOT_SET;
    lastClockPollRealtimeMs = C.TIME_UNSET;
    advancingClockPolls = 0;
    playStartRealtimeMs = delegatePlaying ? clock.elapsedRealtime() : C.TIME_UNSET;
    Handler handler = getAudioHandlerUnchecked();
    if (handler != null) {
      handler.removeMessages(MSG_PREWARM);
    }
    delegate.flush();
    // Advance delegateGeneration only after delegate.flush() finishes so configure() on the
    // playback thread stays non-blocking while audioserver/HAL teardown is still in flight.
    delegateGeneration = newGen;
    delegateHasPendingData = false;
    delegateIsEnded = false;
    cachedAudioTrackBufferSizeUs = delegate.getAudioTrackBufferSizeUs();
    if (generation.get() == newGen) {
      writeClockSnapshotOnAudioThread(CURRENT_POSITION_NOT_SET, 0L, 1f, false, newGen);
    }
    if (delegatePlaying) {
      scheduleClockPollIfPlaying();
    }
    drainQueueOnAudioThread();
  }

  private void handleResetOnAudioThread(int newGen, @Nullable ConditionVariable done) {
    try {
      delegatePlaying = false;
      lastConfiguredPassthrough = false;
      playStartRealtimeMs = C.TIME_UNSET;
      advancingClockPolls = 0;
      recycleActiveDataItemOnAudioThread();
      delegateEndOfStreamRequested = false;
      hasPendingErrorOnAudioThread = false;
      delayedDrainScheduledOnAudioThread = false;
      lastPolledPositionUs = CURRENT_POSITION_NOT_SET;
      lastClockPollRealtimeMs = C.TIME_UNSET;
      Handler handler = getAudioHandlerUnchecked();
      if (handler != null) {
        handler.removeMessages(MSG_DRAIN);
        handler.removeMessages(MSG_POLL_CLOCK);
        handler.removeMessages(MSG_PREWARM);
      }
      drainScheduled.set(false);
      delegate.reset();
      delegateGeneration = newGen;
      delegateHasPendingData = false;
      delegateIsEnded = false;
      cachedAudioTrackBufferSizeUs = C.TIME_UNSET;
      if (generation.get() == newGen) {
        writeClockSnapshotOnAudioThread(CURRENT_POSITION_NOT_SET, 0L, 1f, false, newGen);
      }
      // Drain any post-reset configure/handleBuffer items enqueued for newGen while reset was
      // running (e.g. during a seamless live audio track switch).
      drainQueueOnAudioThread();
    } finally {
      if (done != null) {
        done.open();
      }
    }
  }

  private void handleReleaseOnAudioThread(@Nullable ConditionVariable done) {
    try {
      delegatePlaying = false;
      recycleActiveDataItemOnAudioThread();
      delegate.release();
    } finally {
      if (done != null) {
        done.open();
      }
    }
  }

  private void drainQueueOnAudioThread() {
    if (released.get() || hasPendingErrorOnAudioThread) {
      drainScheduled.set(false);
      return;
    }
    if (delayedDrainScheduledOnAudioThread) {
      delayedDrainScheduledOnAudioThread = false;
      Handler handler = getAudioHandlerUnchecked();
      if (handler != null) {
        handler.removeMessages(MSG_DRAIN);
      }
    }

    int currentGen = generation.get();
    if (delegateGeneration != currentGen) {
      // Wait for the queued MSG_FLUSH / MSG_RESET to advance delegateGeneration.
      drainScheduled.set(false);
      return;
    }

    // Keep drainScheduled=true while actively draining so ExoPlayer:Playback does not enqueue
    // redundant MSG_DRAIN messages (and contend on MessageQueue) for every audio buffer.
    drainScheduled.set(true);

    while (!released.get() && generation.get() == currentGen && !hasPendingErrorOnAudioThread) {
      if (activeDataItem != null) {
        if (activeDataItem.generation != currentGen) {
          recycleActiveDataItemOnAudioThread();
          continue;
        }
        if (!feedDataItemToDelegate(activeDataItem)) {
          // AudioTrack buffer is full; schedule a single delayed retry and keep drainScheduled=true
          // while the timer is armed so ExoPlayer:Playback does not spam redundant MSG_DRAIN
          // messages (and contend on MessageQueue) while the hardware buffer is already full.
          if (!scheduleDelayedDrain()) {
            drainScheduled.set(false);
          }
          break;
        }
        recycleActiveDataItemOnAudioThread();
        continue;
      }

      @Nullable QueueItem next = pollPendingItem();

      if (next == null) {
        if (delegateEndOfStreamRequested && !delegate.isEnded()) {
          try {
            delegate.playToEndOfStream();
          } catch (WriteException e) {
            pendingWriteException.compareAndSet(null, e);
            hasPendingErrorOnAudioThread = true;
            drainScheduled.set(false);
            break;
          } catch (RuntimeException e) {
            pendingRuntimeException.compareAndSet(null, e);
            hasPendingErrorOnAudioThread = true;
            drainScheduled.set(false);
            break;
          }
          if (!delegate.isEnded()) {
            if (!scheduleDelayedDrain()) {
              drainScheduled.set(false);
            }
            break;
          }
        }
        // Wait-free consumer recheck: release drainScheduled and verify no producer item arrived
        // in the window between poll()==null and drainScheduled.set(false).
        drainScheduled.set(false);
        if (!isPendingItemsEmpty()
            && !released.get()
            && !hasPendingErrorOnAudioThread
            && generation.get() == currentGen
            && drainScheduled.compareAndSet(false, true)) {
          continue;
        }
        break;
      }

      if (next.generation != currentGen) {
        if (next.type == QueueItem.TYPE_DATA) {
          decrementQueuedDataCounters(next.byteCount);
        }
        if (next.buffer != null) {
          recycleBuffer(next.buffer);
        }
        recycleQueueItem(next);
        continue;
      }

      boolean retainItem = false;
      switch (next.type) {
        case QueueItem.TYPE_DISCONTINUITY:
          delegate.handleDiscontinuity();
          lastPolledPositionUs = CURRENT_POSITION_NOT_SET;
          advancingClockPolls = 0;
          break;
        case QueueItem.TYPE_OUTPUT_STREAM_OFFSET:
          delegate.setOutputStreamOffsetUs(next.timeUs);
          break;
        case QueueItem.TYPE_CONFIGURE:
          try {
            Format nextFormat = checkNotNull(next.format);
            delegate.configure(nextFormat, next.specifiedBufferSize, next.outputChannels);
            lastConfiguredPassthrough = !MimeTypes.AUDIO_RAW.equals(nextFormat.sampleMimeType);
            cachedAudioTrackBufferSizeUs = delegate.getAudioTrackBufferSizeUs();
            cachedPlaybackParameters = delegate.getPlaybackParameters();
            if (lastConfiguredPassthrough) {
              handlePrewarmOnAudioThread(currentGen);
            }
          } catch (ConfigurationException e) {
            pendingConfigException.compareAndSet(null, e);
            hasPendingErrorOnAudioThread = true;
          } catch (RuntimeException e) {
            pendingRuntimeException.compareAndSet(null, e);
            hasPendingErrorOnAudioThread = true;
          }
          break;
        case QueueItem.TYPE_END_OF_STREAM:
          delegateEndOfStreamRequested = true;
          try {
            delegate.playToEndOfStream();
          } catch (WriteException e) {
            pendingWriteException.compareAndSet(null, e);
            hasPendingErrorOnAudioThread = true;
          } catch (RuntimeException e) {
            pendingRuntimeException.compareAndSet(null, e);
            hasPendingErrorOnAudioThread = true;
          }
          if (!hasPendingErrorOnAudioThread && !delegate.isEnded()) {
            recycleQueueItem(next);
            if (!scheduleDelayedDrain()) {
              drainScheduled.set(false);
            }
            refreshClockSnapshotOnAudioThread(/* force= */ true);
            return;
          }
          break;
        case QueueItem.TYPE_DATA:
          if (feedDataItemToDelegate(next)) {
            decrementQueuedDataCounters(next.byteCount);
            if (next.buffer != null) {
              recycleBuffer(next.buffer);
            }
          } else {
            // Retain exact ByteBuffer reference for DefaultAudioSink's retry invariant.
            activeDataItem = next;
            hasActiveDataItem = true;
            retainItem = true;
            decrementQueuedDataCounters(next.byteCount);
            if (!scheduleDelayedDrain()) {
              drainScheduled.set(false);
            }
            refreshClockSnapshotOnAudioThread(/* force= */ false);
            return;
          }
          break;
        default:
          break;
      }
      if (!retainItem) {
        recycleQueueItem(next);
      }
    }

    if (released.get() || hasPendingErrorOnAudioThread || generation.get() != currentGen) {
      drainScheduled.set(false);
    }

    boolean needsImmediateClockSeed =
        delegatePlaying
            && (snapPositionUs == CURRENT_POSITION_NOT_SET
                || snapGeneration != currentGen
                || !snapAdvancing);
    refreshClockSnapshotOnAudioThread(
        /* force= */ delegateEndOfStreamRequested || needsImmediateClockSeed);
    if (needsImmediateClockSeed) {
      scheduleClockPollIfPlaying();
    }
  }

  private boolean feedDataItemToDelegate(QueueItem item) {
    try {
      boolean consumed =
          delegate.handleBuffer(
              checkNotNull(item.buffer), item.timeUs, item.encodedAccessUnitCount);
      if (cachedAudioTrackBufferSizeUs == C.TIME_UNSET) {
        cachedAudioTrackBufferSizeUs = delegate.getAudioTrackBufferSizeUs();
      }
      return consumed;
    } catch (InitializationException e) {
      pendingInitException.compareAndSet(null, e);
      hasPendingErrorOnAudioThread = true;
      return false;
    } catch (WriteException e) {
      pendingWriteException.compareAndSet(null, e);
      hasPendingErrorOnAudioThread = true;
      return false;
    } catch (RuntimeException e) {
      pendingRuntimeException.compareAndSet(null, e);
      hasPendingErrorOnAudioThread = true;
      return false;
    }
  }

  private void recycleActiveDataItemOnAudioThread() {
    if (activeDataItem != null) {
      QueueItem item = activeDataItem;
      @Nullable ByteBuffer buf = item.buffer;
      activeDataItem = null;
      hasActiveDataItem = false;
      if (buf != null) {
        recycleBuffer(buf);
      }
      recycleQueueItem(item);
    }
  }

  private boolean isInStartupFastPollWindow(long nowMs) {
    if (playStartRealtimeMs == C.TIME_UNSET
        || (nowMs - playStartRealtimeMs) >= CLOCK_STARTUP_FAST_POLL_WINDOW_MS) {
      return false;
    }
    // Passthrough AudioTracks can exhibit delayed DMA playhead jumps up to ~350 ms after
    // start/flush, so keep fast lock-in polling active for the full window on passthrough while
    // allowing PCM tracks to settle as soon as 3 advancing samples are observed.
    return lastConfiguredPassthrough || advancingClockPolls < STARTUP_ADVANCING_POLLS_TO_LOCK;
  }

  private long getDrainRetryDelayMs() {
    if (!delegatePlaying) {
      return DRAIN_RETRY_DELAY_PAUSED_INIT_MS;
    }
    long bufferUs = cachedAudioTrackBufferSizeUs;
    if (isInStartupFastPollWindow(clock.elapsedRealtime())
        || bufferUs == C.TIME_UNSET
        || bufferUs < 80_000L) {
      return DRAIN_RETRY_DELAY_FAST_MS;
    }
    long bufferMs = bufferUs / 1_000L;
    long adaptiveMs = bufferMs / 16L;
    return max(DRAIN_RETRY_DELAY_STEADY_MIN_MS, min(DRAIN_RETRY_DELAY_STEADY_MAX_MS, adaptiveMs));
  }

  private boolean scheduleDelayedDrain() {
    if (!delegatePlaying
        && cachedAudioTrackBufferSizeUs != C.TIME_UNSET
        && !delegateEndOfStreamRequested) {
      // AudioTrack is already initialized and its pre-roll buffer is full while paused/buffering.
      // Nothing is consuming from the paused track, so stay completely idle until play() or flush().
      delegateHasPendingData = true;
      return false;
    }
    Handler handler = getAudioHandlerUnchecked();
    if (handler == null) {
      return false;
    }
    if (delayedDrainScheduledOnAudioThread) {
      return true;
    }
    if (delegatePlaying
        && !delegateEndOfStreamRequested
        && !hasPendingErrorOnAudioThread
        && cachedAudioTrackBufferSizeUs >= MIN_BATCHING_AUDIO_BUFFER_US
        && !isInStartupFastPollWindow(clock.elapsedRealtime())) {
      if (!handler.hasMessages(MSG_POLL_CLOCK)) {
        scheduleClockPollIfPlaying();
      }
      if (steadyClockPollActive && handler.hasMessages(MSG_POLL_CLOCK)) {
        // Steady-state playback with a full >=150 ms AudioTrack buffer: piggyback drain on the
        // already-armed MSG_POLL_CLOCK wakeup (<=25 ms) so drain + clock refresh happen in a
        // single 40 Hz wakeup instead of two competing timers.
        return true;
      }
    }
    long delayMs = getDrainRetryDelayMs();
    boolean enqueued = handler.sendEmptyMessageDelayed(MSG_DRAIN, delayMs);
    delayedDrainScheduledOnAudioThread = enqueued;
    return enqueued;
  }

  private void pollClockOnAudioThread() {
    if (released.get() || !delegatePlaying) {
      steadyClockPollActive = false;
      return;
    }
    if (!isPendingItemsEmpty() || activeDataItem != null || delegateEndOfStreamRequested) {
      // Drain any batched steady-state audio chunks and refresh the clock in a single wakeup.
      long prevPollMs = lastClockPollRealtimeMs;
      drainQueueOnAudioThread();
      if (lastClockPollRealtimeMs == prevPollMs) {
        refreshClockSnapshotOnAudioThread(/* force= */ true);
      }
      return;
    }
    refreshClockSnapshotOnAudioThread(/* force= */ true);
  }

  private long getActiveClockPollIntervalMs(long nowMs) {
    if (snapPositionUs == CURRENT_POSITION_NOT_SET
        || !snapAdvancing
        || isInStartupFastPollWindow(nowMs)) {
      return CLOCK_POLL_INTERVAL_STARTUP_MS;
    }
    return CLOCK_POLL_INTERVAL_MS;
  }

  private void scheduleClockPollIfPlaying() {
    Handler handler = getAudioHandlerUnchecked();
    if (handler != null && delegatePlaying && !delegateIsEnded && !released.get()) {
      long pollIntervalMs = getActiveClockPollIntervalMs(clock.elapsedRealtime());
      handler.removeMessages(MSG_POLL_CLOCK);
      steadyClockPollActive = handler.sendEmptyMessageDelayed(MSG_POLL_CLOCK, pollIntervalMs);
    } else {
      steadyClockPollActive = false;
    }
  }

  private void writeClockSnapshotOnAudioThread(
      long posUs, long sysTimeNano, float speed, boolean advancing, int gen) {
    int seq = clockSeq.get();
    clockSeq.set(seq + 1); // odd: write in progress (volatile store)
    snapPositionUs = posUs;
    snapSystemTimeNano = sysTimeNano;
    snapPlaybackSpeed = speed;
    snapAdvancing = advancing;
    snapGeneration = gen;
    clockSeq.set(seq + 2); // even: consistent snapshot published (volatile store)
  }

  private void refreshClockSnapshotOnAudioThread(boolean force) {
    int currentGen = generation.get();
    if (delegateGeneration != currentGen || released.get()) {
      return;
    }
    long nowMs = clock.elapsedRealtime();
    long pollIntervalMs = getActiveClockPollIntervalMs(nowMs);
    if (!force
        && lastClockPollRealtimeMs != C.TIME_UNSET
        && (nowMs - lastClockPollRealtimeMs) < pollIntervalMs
        && snapPositionUs != CURRENT_POSITION_NOT_SET
        && snapGeneration == currentGen) {
      return;
    }
    lastClockPollRealtimeMs = nowMs;

    long posUs = delegate.getCurrentPositionUs(delegateEndOfStreamRequested);
    long nowNs = clock.nanoTime();
    float speed = delegate.getPlaybackParameters().speed;
    boolean ended = delegate.isEnded();
    boolean queueHasData = queuedDataChunks.get() > 0;
    // Avoid calling delegate.hasPendingData() (which queries AudioTrack position a second time)
    // whenever we already know buffers are queued or the track is actively playing non-EOS audio.
    boolean pendingData =
        activeDataItem != null
            || queueHasData
            || (delegatePlaying
                && !ended
                && !delegateEndOfStreamRequested
                && posUs != CURRENT_POSITION_NOT_SET)
            || delegate.hasPendingData();

    boolean advancing =
        delegatePlaying
            && !ended
            && posUs != CURRENT_POSITION_NOT_SET
            && lastPolledPositionUs != CURRENT_POSITION_NOT_SET
            && posUs > lastPolledPositionUs;

    if (advancing) {
      if (advancingClockPolls < STARTUP_ADVANCING_POLLS_TO_LOCK) {
        advancingClockPolls++;
      }
    } else {
      advancingClockPolls = 0;
    }

    if (posUs != CURRENT_POSITION_NOT_SET) {
      lastPolledPositionUs = posUs;
    }

    if (generation.get() == currentGen) {
      delegateHasPendingData = pendingData;
      delegateIsEnded = ended;
      if (posUs != CURRENT_POSITION_NOT_SET) {
        writeClockSnapshotOnAudioThread(posUs, nowNs, speed, advancing, currentGen);
      }
    }

    // Coalesce the standalone clock poll timer forward so MSG_POLL_CLOCK does not wake
    // ExoPlayer:AudioSink redundantly right after a drain already refreshed the clock.
    if (delegatePlaying && !ended && !released.get()) {
      scheduleClockPollIfPlaying();
    }
  }

  @Nullable
  private Handler getAudioHandlerUnchecked() {
    return audioHandlerVolatile;
  }

  private final class InternalListener implements Listener {

    @Override
    public void onPositionDiscontinuity() {
      lastPolledPositionUs = CURRENT_POSITION_NOT_SET;
      pendingPositionDiscontinuity.set(true);
    }

    @Override
    public void onPositionAdvancing(long playoutStartSystemTimeMs) {
      @Nullable Listener listener = externalListener;
      if (listener != null) {
        listener.onPositionAdvancing(playoutStartSystemTimeMs);
      }
    }

    @Override
    public void onUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
      @Nullable Listener listener = externalListener;
      if (listener != null) {
        listener.onUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs);
      }
    }

    @Override
    public void onSkipSilenceEnabledChanged(boolean skipSilenceEnabled) {
      cachedSkipSilenceEnabled = skipSilenceEnabled;
      @Nullable Listener listener = externalListener;
      if (listener != null) {
        listener.onSkipSilenceEnabledChanged(skipSilenceEnabled);
      }
    }

    @Override
    public void onOffloadBufferEmptying() {
      scheduleDrain();
      @Nullable Listener listener = externalListener;
      if (listener != null) {
        listener.onOffloadBufferEmptying();
      }
    }

    @Override
    public void onOffloadBufferFull() {
      @Nullable Listener listener = externalListener;
      if (listener != null) {
        listener.onOffloadBufferFull();
      }
    }

    @Override
    public void onAudioSinkError(Exception audioSinkError) {
      @Nullable Listener listener = externalListener;
      if (listener != null) {
        listener.onAudioSinkError(audioSinkError);
      }
    }

    @Override
    public void onAudioCapabilitiesChanged() {
      invalidateCapabilitiesCache();
      @Nullable Listener listener = externalListener;
      if (listener != null) {
        listener.onAudioCapabilitiesChanged();
      }
    }

    @Override
    public void onAudioTrackInitialized(AudioTrackConfig audioTrackConfig) {
      @Nullable Listener listener = externalListener;
      if (listener != null) {
        listener.onAudioTrackInitialized(audioTrackConfig);
      }
    }

    @Override
    public void onAudioTrackReleased(AudioTrackConfig audioTrackConfig) {
      @Nullable Listener listener = externalListener;
      if (listener != null) {
        listener.onAudioTrackReleased(audioTrackConfig);
      }
    }

    @Override
    public void onSilenceSkipped() {
      pendingSilenceSkipped.set(true);
    }

    @Override
    public void onAudioSessionIdChanged(int audioSessionId) {
      @Nullable Listener listener = externalListener;
      if (listener != null) {
        listener.onAudioSessionIdChanged(audioSessionId);
      }
    }
  }
}
