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
package androidx.media3.exoplayer.mediacodec;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Util.castNonNull;

import android.media.MediaCodec;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.NullableType;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Performs {@link MediaCodec#releaseOutputBuffer} calls on a dedicated background thread so that
 * Surface/BufferQueue fence stalls on Android TV devices never block the {@code ExoPlayer:Playback}
 * thread.
 */
@RequiresApi(23)
/* package */ final class AsynchronousMediaCodecBufferReleaser {

  private static final int MSG_RELEASE_BUFFER = 1;
  private static final int MSG_RELEASE_BUFFER_WITH_TIMESTAMP = 2;
  private static final int MSG_OPEN_CV = 3;
  private static final int MAX_POOLED_PARAMS = 32;

  // Fixed-size lock-free, zero-allocation pool (avoids ConcurrentLinkedQueue$Node allocations on
  // ExoPlayer:Playback that can stall on ART WaitForGcToComplete during background GC).
  private static final AtomicReferenceArray<@NullableType MessageParams>
      MESSAGE_PARAMS_INSTANCE_POOL = new AtomicReferenceArray<>(MAX_POOLED_PARAMS);

  private final MediaCodec codec;
  private final HandlerThread handlerThread;
  private @MonotonicNonNull Handler handler;
  private final AtomicReference<@NullableType RuntimeException> pendingRuntimeException;
  private final ConditionVariable conditionVariable;
  private boolean started;

  public AsynchronousMediaCodecBufferReleaser(MediaCodec codec, HandlerThread releaserThread) {
    this(codec, releaserThread, new ConditionVariable());
  }

  @VisibleForTesting
  /* package */ AsynchronousMediaCodecBufferReleaser(
      MediaCodec codec, HandlerThread handlerThread, ConditionVariable conditionVariable) {
    this.codec = codec;
    this.handlerThread = handlerThread;
    this.conditionVariable = conditionVariable;
    this.pendingRuntimeException = new AtomicReference<>();
  }

  public void start() {
    if (!started) {
      handlerThread.start();
      handler =
          new Handler(handlerThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
              doHandleMessage(msg);
            }
          };
      started = true;
    }
  }

  public void releaseOutputBuffer(int index, boolean render) {
    maybeThrowException();
    castNonNull(handler).obtainMessage(MSG_RELEASE_BUFFER, index, render ? 1 : 0).sendToTarget();
  }

  public void releaseOutputBuffer(int index, long renderTimeStampNs) {
    maybeThrowException();
    MessageParams params = getMessageParams();
    params.set(index, /* render= */ true, renderTimeStampNs);
    castNonNull(handler).obtainMessage(MSG_RELEASE_BUFFER_WITH_TIMESTAMP, params).sendToTarget();
  }

  /**
   * Discards queued releases not yet started and blocks until any in-flight {@code
   * releaseOutputBuffer} call finishes. Must be called before {@link MediaCodec#flush()} or {@link
   * MediaCodec#release()}.
   */
  public void flush() {
    if (started) {
      try {
        checkNotNull(handler).removeCallbacksAndMessages(null);
        blockUntilHandlerThreadIsIdle();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  /**
   * Waits for all queued releases to finish without discarding them. Must be called before {@link
   * MediaCodec#setOutputSurface} or {@link MediaCodec#detachOutputSurface}.
   */
  public void waitUntilIdle() {
    if (started) {
      try {
        blockUntilHandlerThreadIsIdle();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  public void shutdown() {
    if (started) {
      flush();
      handlerThread.quit();
    }
    started = false;
  }

  public void maybeThrowException() {
    @Nullable RuntimeException exception = pendingRuntimeException.getAndSet(null);
    if (exception != null) {
      throw exception;
    }
  }

  private void blockUntilHandlerThreadIsIdle() throws InterruptedException {
    conditionVariable.close();
    checkNotNull(handler).obtainMessage(MSG_OPEN_CV).sendToTarget();
    conditionVariable.block();
  }

  private void doHandleMessage(Message msg) {
    @Nullable MessageParams params = null;
    switch (msg.what) {
      case MSG_RELEASE_BUFFER:
        doReleaseOutputBuffer(msg.arg1, msg.arg2 != 0);
        break;
      case MSG_RELEASE_BUFFER_WITH_TIMESTAMP:
        params = (MessageParams) msg.obj;
        doReleaseOutputBufferWithTimestamp(params.index, params.renderTimeStampNs);
        break;
      case MSG_OPEN_CV:
        conditionVariable.open();
        break;
      default:
        pendingRuntimeException.compareAndSet(
            null, new IllegalStateException(String.valueOf(msg.what)));
    }
    if (params != null) {
      recycleMessageParams(params);
    }
  }

  private void doReleaseOutputBuffer(int index, boolean render) {
    try {
      codec.releaseOutputBuffer(index, render);
    } catch (RuntimeException e) {
      pendingRuntimeException.compareAndSet(null, e);
    }
  }

  private void doReleaseOutputBufferWithTimestamp(int index, long renderTimeStampNs) {
    try {
      codec.releaseOutputBuffer(index, renderTimeStampNs);
    } catch (RuntimeException e) {
      pendingRuntimeException.compareAndSet(null, e);
    }
  }

  private static MessageParams getMessageParams() {
    for (int i = 0; i < MAX_POOLED_PARAMS; i++) {
      @Nullable MessageParams params = MESSAGE_PARAMS_INSTANCE_POOL.get(i);
      if (params != null && MESSAGE_PARAMS_INSTANCE_POOL.compareAndSet(i, params, null)) {
        return params;
      }
    }
    return new MessageParams();
  }

  private static void recycleMessageParams(MessageParams params) {
    for (int i = 0; i < MAX_POOLED_PARAMS; i++) {
      if (MESSAGE_PARAMS_INSTANCE_POOL.get(i) == null
          && MESSAGE_PARAMS_INSTANCE_POOL.compareAndSet(i, null, params)) {
        return;
      }
    }
  }

  private static final class MessageParams {
    int index;
    boolean render;
    long renderTimeStampNs;

    void set(int index, boolean render, long renderTimeStampNs) {
      this.index = index;
      this.render = render;
      this.renderTimeStampNs = renderTimeStampNs;
    }
  }
}
