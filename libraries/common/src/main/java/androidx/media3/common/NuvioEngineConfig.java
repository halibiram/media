/*
 * Copyright (C) 2024 The Android Open Source Project
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
package androidx.media3.common;

import androidx.media3.common.util.UnstableApi;

/**
 * Global configuration that controls the Nuvio Engine enhancements over stock ExoPlayer.
 *
 * <p>All feature flags must be set <b>before</b> building a player instance. Changing flags during
 * active playback is not supported and may cause undefined behaviour.
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * // Enable all Nuvio enhancements
 * NuvioEngineConfig.set(NuvioEngineConfig.nuvioMode());
 *
 * // Use stock ExoPlayer behaviour
 * NuvioEngineConfig.set(NuvioEngineConfig.stockMode());
 *
 * // Custom configuration
 * NuvioEngineConfig.set(
 *     new NuvioEngineConfig.Builder()
 *         .setAllocationMode(AllocationMode.NATIVE_OFF_HEAP)
 *         .setDataPipelineMode(DataPipelineMode.STOCK)
 *         .setExtractorScratchSize(65536)
 *         .build());
 * }</pre>
 */
@UnstableApi
public final class NuvioEngineConfig {

  /** Controls how sample data memory is allocated. */
  public enum AllocationMode {
    /** Stock ExoPlayer behaviour — on-heap {@code byte[]} allocations. */
    STOCK_HEAP,
    /** Nuvio enhancement — off-heap native memory via {@code posix_memalign} / DirectByteBuffer. */
    NATIVE_OFF_HEAP
  }

  /** Controls the data pipeline between DataSource and SampleQueue. */
  public enum DataPipelineMode {
    /** Stock ExoPlayer behaviour — {@code byte[]} based DataReader. */
    STOCK,
    /** Nuvio enhancement — zero-copy ByteBuffer pipeline through DataSource → Extractor → SampleQueue. */
    ZERO_COPY
  }

  /** Stock ExoPlayer scratch space size (bytes). */
  public static final int STOCK_EXTRACTOR_SCRATCH_SIZE = 4096;

  /** Nuvio enhanced extractor scratch space size (bytes). */
  public static final int NUVIO_EXTRACTOR_SCRATCH_SIZE = 65536;

  /** Default interval in ms between routine SampleQueue back-buffer discards on the playback thread. */
  public static final long DEFAULT_DISCARD_BUFFER_THROTTLE_MS = 250L;

  /**
   * Default per-renderer time slice limit in ms. Kept unset so seek/startup keyframe catch-up runs
   * at full speed without artificial 10 ms sleep slices between decode-only frames.
   */
  public static final long DEFAULT_RENDERER_TIME_LIMIT_MS = C.TIME_UNSET;

  private static volatile NuvioEngineConfig instance = stockMode();

  private final AllocationMode allocationMode;
  private final DataPipelineMode dataPipelineMode;
  private final int extractorScratchSize;
  private final boolean asyncAudioSinkEnabled;
  private final boolean asyncVideoReleaseEnabled;
  private final boolean realtimePlaybackThreadEnabled;
  private final long discardBufferThrottleMs;
  private final long rendererTimeLimitMs;

  private NuvioEngineConfig(Builder builder) {
    this.allocationMode = builder.allocationMode;
    this.dataPipelineMode = builder.dataPipelineMode;
    this.extractorScratchSize = builder.extractorScratchSize;
    this.asyncAudioSinkEnabled = builder.asyncAudioSinkEnabled;
    this.asyncVideoReleaseEnabled = builder.asyncVideoReleaseEnabled;
    this.realtimePlaybackThreadEnabled = builder.realtimePlaybackThreadEnabled;
    this.discardBufferThrottleMs = builder.discardBufferThrottleMs;
    this.rendererTimeLimitMs = builder.rendererTimeLimitMs;
  }

  /** Returns the current global configuration. */
  public static NuvioEngineConfig get() {
    return instance;
  }

  /**
   * Sets the global configuration. Must be called before building any player instance.
   *
   * @param config The configuration to apply globally.
   */
  public static void set(NuvioEngineConfig config) {
    instance = config;
  }

  /**
   * Returns a configuration with all Nuvio enhancements enabled.
   *
   * <ul>
   *   <li>Native off-heap allocation
   *   <li>Zero-copy data pipeline
   *   <li>64 KB extractor scratch buffer
   *   <li>Decoupled async audio sink &amp; lock-free clock extrapolation
   *   <li>Async video output buffer release
   *   <li>Kernel-hardened real-time playback thread
   *   <li>Throttled SampleQueue back-buffer discard (250 ms)
   * </ul>
   */
  public static NuvioEngineConfig nuvioMode() {
    return new Builder()
        .setAllocationMode(AllocationMode.NATIVE_OFF_HEAP)
        .setDataPipelineMode(DataPipelineMode.ZERO_COPY)
        .setExtractorScratchSize(NUVIO_EXTRACTOR_SCRATCH_SIZE)
        .setAsyncAudioSinkEnabled(true)
        .setAsyncVideoReleaseEnabled(true)
        .setRealtimePlaybackThreadEnabled(true)
        .setDiscardBufferThrottleMs(DEFAULT_DISCARD_BUFFER_THROTTLE_MS)
        .setRendererTimeLimitMs(DEFAULT_RENDERER_TIME_LIMIT_MS)
        .build();
  }

  /**
   * Returns a configuration that keeps stock heap allocation and byte[] data pipeline while
   * enabling decoupled A/V playback threading to prevent playback thread lockups and frame drops.
   */
  public static NuvioEngineConfig stockWithDecoupledAvSync() {
    return new Builder()
        .setAllocationMode(AllocationMode.STOCK_HEAP)
        .setDataPipelineMode(DataPipelineMode.STOCK)
        .setExtractorScratchSize(STOCK_EXTRACTOR_SCRATCH_SIZE)
        .setAsyncAudioSinkEnabled(true)
        .setAsyncVideoReleaseEnabled(true)
        .setRealtimePlaybackThreadEnabled(true)
        .setDiscardBufferThrottleMs(DEFAULT_DISCARD_BUFFER_THROTTLE_MS)
        .setRendererTimeLimitMs(DEFAULT_RENDERER_TIME_LIMIT_MS)
        .build();
  }

  /**
   * Returns a configuration that matches stock ExoPlayer behaviour.
   *
   * <ul>
   *   <li>On-heap byte[] allocation
   *   <li>Standard byte[] data pipeline
   *   <li>4 KB extractor scratch buffer
   * </ul>
   */
  public static NuvioEngineConfig stockMode() {
    return new Builder()
        .setAllocationMode(AllocationMode.STOCK_HEAP)
        .setDataPipelineMode(DataPipelineMode.STOCK)
        .setExtractorScratchSize(STOCK_EXTRACTOR_SCRATCH_SIZE)
        .build();
  }

  /** Returns a {@link Builder} initialized with this configuration's values. */
  public Builder buildUpon() {
    return new Builder()
        .setAllocationMode(allocationMode)
        .setDataPipelineMode(dataPipelineMode)
        .setExtractorScratchSize(extractorScratchSize)
        .setAsyncAudioSinkEnabled(asyncAudioSinkEnabled)
        .setAsyncVideoReleaseEnabled(asyncVideoReleaseEnabled)
        .setRealtimePlaybackThreadEnabled(realtimePlaybackThreadEnabled)
        .setDiscardBufferThrottleMs(discardBufferThrottleMs)
        .setRendererTimeLimitMs(rendererTimeLimitMs);
  }

  /** Returns the allocation mode. */
  public AllocationMode getAllocationMode() {
    return allocationMode;
  }

  /** Returns the data pipeline mode. */
  public DataPipelineMode getDataPipelineMode() {
    return dataPipelineMode;
  }

  /** Returns the extractor scratch buffer size in bytes. */
  public int getExtractorScratchSize() {
    return extractorScratchSize;
  }

  /** Returns {@code true} if native off-heap allocation is enabled. */
  public boolean isNativeAllocationEnabled() {
    return allocationMode == AllocationMode.NATIVE_OFF_HEAP;
  }

  /** Returns {@code true} if zero-copy data pipeline is enabled. */
  public boolean isZeroCopyEnabled() {
    return dataPipelineMode == DataPipelineMode.ZERO_COPY;
  }

  /** Returns {@code true} if the decoupled asynchronous audio sink thread is enabled. */
  public boolean isAsyncAudioSinkEnabled() {
    return asyncAudioSinkEnabled;
  }

  /** Returns {@code true} if asynchronous video {@code MediaCodec.releaseOutputBuffer} is enabled. */
  public boolean isAsyncVideoReleaseEnabled() {
    return asyncVideoReleaseEnabled;
  }

  /**
   * Returns {@code true} if the hardened real-time playback thread engine (1 ns timer slack and
   * urgent audio/media thread priorities) is enabled.
   */
  public boolean isRealtimePlaybackThreadEnabled() {
    return realtimePlaybackThreadEnabled;
  }

  /**
   * Returns the minimum interval in milliseconds between routine {@code discardBuffer} calls on
   * the playback thread, or {@code 0} for stock per-tick behaviour.
   */
  public long getDiscardBufferThrottleMs() {
    return discardBufferThrottleMs;
  }

  /**
   * Returns the per-renderer render time limit in milliseconds for {@code MediaCodecRenderer}, or
   * {@link C#TIME_UNSET} for unlimited.
   */
  public long getRendererTimeLimitMs() {
    return rendererTimeLimitMs;
  }

  /** Builder for {@link NuvioEngineConfig}. */
  public static final class Builder {
    private AllocationMode allocationMode = AllocationMode.STOCK_HEAP;
    private DataPipelineMode dataPipelineMode = DataPipelineMode.STOCK;
    private int extractorScratchSize = STOCK_EXTRACTOR_SCRATCH_SIZE;
    private boolean asyncAudioSinkEnabled = false;
    private boolean asyncVideoReleaseEnabled = false;
    private boolean realtimePlaybackThreadEnabled = false;
    private long discardBufferThrottleMs = 0L;
    private long rendererTimeLimitMs = C.TIME_UNSET;

    public Builder() {}

    /** Sets the allocation mode. Default is {@link AllocationMode#STOCK_HEAP}. */
    public Builder setAllocationMode(AllocationMode allocationMode) {
      this.allocationMode = allocationMode;
      return this;
    }

    /** Sets the data pipeline mode. Default is {@link DataPipelineMode#STOCK}. */
    public Builder setDataPipelineMode(DataPipelineMode dataPipelineMode) {
      this.dataPipelineMode = dataPipelineMode;
      return this;
    }

    /** Sets the extractor input scratch buffer size in bytes. Default is 4096. */
    public Builder setExtractorScratchSize(int extractorScratchSize) {
      this.extractorScratchSize = extractorScratchSize;
      return this;
    }

    /** Sets whether to run AudioTrack writes and clock polling on a dedicated async audio thread. */
    public Builder setAsyncAudioSinkEnabled(boolean asyncAudioSinkEnabled) {
      this.asyncAudioSinkEnabled = asyncAudioSinkEnabled;
      return this;
    }

    /** Sets whether to offload video {@code MediaCodec.releaseOutputBuffer} to a dedicated thread. */
    public Builder setAsyncVideoReleaseEnabled(boolean asyncVideoReleaseEnabled) {
      this.asyncVideoReleaseEnabled = asyncVideoReleaseEnabled;
      return this;
    }

    /**
     * Sets whether to enable the hardened real-time playback thread engine (kernel timer slack
     * elimination and urgent audio/media thread priorities).
     */
    public Builder setRealtimePlaybackThreadEnabled(boolean realtimePlaybackThreadEnabled) {
      this.realtimePlaybackThreadEnabled = realtimePlaybackThreadEnabled;
      return this;
    }

    /** Sets the throttle interval in ms for routine SampleQueue back-buffer discarding. */
    public Builder setDiscardBufferThrottleMs(long discardBufferThrottleMs) {
      this.discardBufferThrottleMs = Math.max(0L, discardBufferThrottleMs);
      return this;
    }

    /** Sets the per-renderer render time slice limit in ms, or {@link C#TIME_UNSET} for unlimited. */
    public Builder setRendererTimeLimitMs(long rendererTimeLimitMs) {
      this.rendererTimeLimitMs = rendererTimeLimitMs;
      return this;
    }

    /** Builds the {@link NuvioEngineConfig}. */
    public NuvioEngineConfig build() {
      return new NuvioEngineConfig(this);
    }
  }
}
