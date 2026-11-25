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
package androidx.media3.exoplayer.source;

import static java.lang.Math.max;
import static java.lang.Math.min;

import android.net.Uri;
import android.os.Debug;
import android.os.Process;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.media3.common.ByteBufferDataReader;
import androidx.media3.common.C;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kodi-inspired off-heap circular RAM read-ahead {@link DataSource} decorator for progressive media
 * streams.
 *
 * <p>Architecture:
 *
 * <ul>
 *   <li>Allocates a single reusable off-heap {@link ByteBuffer#allocateDirect(int)} ring buffer
 *       partitioned into 75% forward read-ahead and 25% backward history, eliminating JVM heap GC
 *       churn on high-bitrate 4K streams.
 *   <li>Decouples extractor small reads (e.g. 184-byte Matroska EBML header reads) from upstream
 *       I/O by pumping upstream in large adaptive chunks ({@code 64 KB -> 128 KB -> 256 KB}) on a
 *       low-priority background reader thread.
 *   <li>Retains the cached byte window across {@link #close()} / {@link #open(DataSpec)} calls for
 *       the same URI so short back-seeks, forward in-buffer seeks, and track switches inside the
 *       ring resume in 0 ms from RAM while reconnecting upstream asynchronously at the cache tail.
 *   <li>Implements {@link ByteBufferDataReader} so downstream {@link SampleDataQueue} off-heap
 *       native chunks can copy directly from the direct ring buffer without intermediate {@code
 *       byte[]} copies.
 * </ul>
 */
@UnstableApi
public final class CircularReadAheadDataSource implements DataSource, ByteBufferDataReader {

  private static final String TAG = "CircularReadAheadDS";

  /** Minimum ring buffer capacity (512 KB). */
  public static final int MIN_BUFFER_BYTES = 512 * 1024;

  /** Maximum ring buffer capacity (32 MB) to protect 32-bit devices against misconfiguration. */
  public static final int MAX_BUFFER_BYTES = 32 * 1024 * 1024;

  private static final int MAX_CONSECUTIVE_ZERO_READS = 8;

  /** Initial upstream chunk size after open/seek to keep startup probes and seeks instantaneous. */
  /* package */ static final int INITIAL_CHUNK_BYTES = 16 * 1024;

  /** Early ramp-up upstream chunk size. */
  private static final int RAMP_1_CHUNK_BYTES = 64 * 1024;

  /** Mid ramp-up upstream chunk size. */
  private static final int RAMP_2_CHUNK_BYTES = 128 * 1024;

  /** Steady-state upstream chunk size to coalesce small reads and saturate high-bitrate links. */
  /* package */ static final int STEADY_CHUNK_BYTES = 256 * 1024;

  private final DataSource upstream;
  private final int capacity;
  private final int backReserveBytes;
  private final int maxForwardBytes;
  private final int minWakeupRoomBytes;
  private final Object lock = new Object();

  @Nullable private ByteBuffer ringBuffer;
  @Nullable private ByteBuffer consumerRingView;
  @Nullable private ByteBuffer readerRingView;
  @Nullable private byte[] stagingBuffer;

  // Session state.
  private boolean opened;
  private boolean passthroughMode;
  private long openEndPos = C.LENGTH_UNSET;
  @Nullable private Thread readerThread;
  private final AtomicBoolean upstreamOpen = new AtomicBoolean(false);

  // Cached metadata retained across close()/open() for in-buffer seek hits.
  @Nullable private DataSpec cachedRequestSpec;
  @Nullable private Uri resolvedUri;
  private Map<String, List<String>> responseHeaders = Collections.emptyMap();
  private long totalStreamLength = C.LENGTH_UNSET;

  // Ring window state guarded by lock.
  @GuardedBy("lock")
  private long validStart;

  @GuardedBy("lock")
  private long readPos;

  @GuardedBy("lock")
  private long writePos;

  @GuardedBy("lock")
  private boolean reachedEof;

  @GuardedBy("lock")
  private boolean stopRequested;

  @GuardedBy("lock")
  private boolean readerWaiting;

  @GuardedBy("lock")
  private boolean readerExited;

  @GuardedBy("lock")
  @Nullable
  private IOException readerError;

  // Telemetry counters for diagnostics & tests.
  private volatile long cacheHitOpenCount;
  private volatile long upstreamOpenCount;
  private volatile long upstreamReadCallCount;
  private volatile long readerThreadCpuNs;

  /**
   * Returns whether {@code dataSource} reading {@code uri} should be wrapped in a {@link
   * CircularReadAheadDataSource}.
   *
   * <p>Wraps standard progressive HTTP(S), file, and content streams while avoiding double-wrapping
   * and bypassing loopback URLs (e.g. local proxy / Usenet sidecar servers that own their own
   * read-ahead).
   */
  public static boolean shouldWrap(@Nullable Uri uri, DataSource dataSource) {
    if (uri == null || dataSource instanceof CircularReadAheadDataSource) {
      return false;
    }
    String scheme = uri.getScheme();
    if (scheme == null) {
      return false;
    }
    String lowerScheme = scheme.toLowerCase(Locale.US);
    if ("http".equals(lowerScheme) || "https".equals(lowerScheme)) {
      String host = uri.getHost();
      if (host == null) {
        return false;
      }
      String lowerHost = host.toLowerCase(Locale.US);
      if ("localhost".equals(lowerHost)
          || "127.0.0.1".equals(lowerHost)
          || "::1".equals(lowerHost)
          || "[::1]".equals(lowerHost)) {
        return false;
      }
      return true;
    }
    return "file".equals(lowerScheme) || "content".equals(lowerScheme);
  }

  /**
   * Creates a circular read-ahead wrapper around {@code upstream}.
   *
   * @param upstream The underlying {@link DataSource}.
   * @param bufferBytes Total ring buffer capacity in bytes (75% forward, 25% back).
   */
  public CircularReadAheadDataSource(DataSource upstream, int bufferBytes) {
    this.upstream = Assertions.checkNotNull(upstream);
    this.capacity = min(MAX_BUFFER_BYTES, max(MIN_BUFFER_BYTES, bufferBytes));
    this.backReserveBytes = this.capacity / 4;
    this.maxForwardBytes = this.capacity - this.backReserveBytes;
    this.minWakeupRoomBytes = min(STEADY_CHUNK_BYTES, this.maxForwardBytes / 2);
  }

  @Override
  public void addTransferListener(TransferListener transferListener) {
    Assertions.checkNotNull(transferListener);
    upstream.addTransferListener(transferListener);
  }

  @Override
  public long open(DataSpec dataSpec) throws IOException {
    if (opened) {
      close();
    }

    if (shouldBypass(dataSpec) || !tryEnsureRingAllocated()) {
      passthroughMode = true;
      opened = true;
      upstreamOpenCount++;
      upstreamOpen.set(true);
      try {
        return upstream.open(dataSpec);
      } catch (IOException | RuntimeException e) {
        closeUpstreamQuietly();
        opened = false;
        passthroughMode = false;
        throw e;
      }
    }

    // Kodi-style in-buffer seek / track-switch hit: serve buffered bytes immediately from RAM and
    // lazily reconnect upstream at writePos in the background thread if EOF has not been reached.
    if (canServeFromCache(dataSpec)) {
      long startPos = dataSpec.position;
      long remainingLength =
          totalStreamLength != C.LENGTH_UNSET ? (totalStreamLength - startPos) : C.LENGTH_UNSET;
      boolean needBackgroundContinuation;
      long continuationPos;
      synchronized (lock) {
        readPos = startPos;
        stopRequested = false;
        readerExited = !(!reachedEof);
        readerError = null;
        needBackgroundContinuation = !reachedEof;
        continuationPos = writePos;
      }
      openEndPos = totalStreamLength;
      passthroughMode = false;
      opened = true;
      cacheHitOpenCount++;

      if (needBackgroundContinuation) {
        DataSpec continuationSpec =
            dataSpec.buildUpon().setPosition(continuationPos).setLength(C.LENGTH_UNSET).build();
        if (!tryStartReaderThread(/* lazyOpenSpec= */ continuationSpec)) {
          // Thread creation OOM: fall through to direct synchronous open at requested startPos.
          opened = false;
          invalidateCacheLocked();
          return openDirectPassthrough(dataSpec);
        }
      }
      return remainingLength;
    }

    // Cache miss: open upstream synchronously so HTTP errors, redirects, and response headers
    // behave identically to stock DataSource.
    upstreamOpenCount++;
    upstreamOpen.set(true);
    long openedLength;
    try {
      openedLength = upstream.open(dataSpec);
    } catch (IOException | RuntimeException e) {
      // Honor DataSource contract: always close upstream after a failed open() attempt.
      closeUpstreamQuietly();
      invalidateCacheLocked();
      throw e;
    }

    resolvedUri = upstream.getUri();
    Map<String, List<String>> headers = upstream.getResponseHeaders();
    responseHeaders = headers != null ? headers : Collections.emptyMap();
    openEndPos =
        openedLength != C.LENGTH_UNSET ? (dataSpec.position + openedLength) : C.LENGTH_UNSET;
    // Only cache finite, seekable unbounded streams (never live/unknown-length streams so retries
    // after live disconnects always fetch fresh data).
    if (dataSpec.length == C.LENGTH_UNSET && openedLength != C.LENGTH_UNSET && openedLength > 0) {
      totalStreamLength = dataSpec.position + openedLength;
      cachedRequestSpec = dataSpec;
    } else {
      totalStreamLength = C.LENGTH_UNSET;
      cachedRequestSpec = null;
    }

    boolean immediateEof = (openedLength == 0);
    synchronized (lock) {
      validStart = dataSpec.position;
      readPos = dataSpec.position;
      writePos = dataSpec.position;
      reachedEof = immediateEof;
      stopRequested = false;
      readerExited = immediateEof;
      readerError = null;
    }

    passthroughMode = false;
    opened = true;

    if (!immediateEof && !tryStartReaderThread(/* lazyOpenSpec= */ null)) {
      // Thread creation OOM: upstream is already open at dataSpec.position, so seamlessly serve
      // this session in direct passthrough mode without failing playback!
      passthroughMode = true;
      invalidateCacheLocked();
    }
    return openedLength;
  }

  private long openDirectPassthrough(DataSpec dataSpec) throws IOException {
    passthroughMode = true;
    opened = true;
    upstreamOpenCount++;
    upstreamOpen.set(true);
    try {
      return upstream.open(dataSpec);
    } catch (IOException | RuntimeException e) {
      closeUpstreamQuietly();
      opened = false;
      passthroughMode = false;
      throw e;
    }
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if (length == 0) {
      return 0;
    }
    if (!opened) {
      throw new IOException("CircularReadAheadDataSource is not open");
    }
    if (passthroughMode) {
      return upstream.read(buffer, offset, length);
    }

    long readStartPos;
    int toRead;
    synchronized (lock) {
      toRead = awaitAvailableBytesLocked(length);
      if (toRead == C.RESULT_END_OF_INPUT) {
        return C.RESULT_END_OF_INPUT;
      }
      readStartPos = readPos;
    }

    copyFromRingToArray(readStartPos, buffer, offset, toRead);
    advanceReadPos(readStartPos + toRead);
    return toRead;
  }

  @Override
  public boolean supportsByteBufferRead() {
    if (passthroughMode) {
      return upstream instanceof ByteBufferDataReader
          && ((ByteBufferDataReader) upstream).supportsByteBufferRead();
    }
    return true;
  }

  @Override
  public int read(ByteBuffer target, int length) throws IOException {
    if (length == 0) {
      return 0;
    }
    if (!opened) {
      throw new IOException("CircularReadAheadDataSource is not open");
    }
    if (passthroughMode) {
      if (!(upstream instanceof ByteBufferDataReader)
          || !((ByteBufferDataReader) upstream).supportsByteBufferRead()) {
        throw new UnsupportedOperationException();
      }
      return ((ByteBufferDataReader) upstream).read(target, length);
    }

    int maxRead = min(length, target.remaining());
    if (maxRead == 0) {
      return 0;
    }

    long readStartPos;
    int toRead;
    synchronized (lock) {
      toRead = awaitAvailableBytesLocked(maxRead);
      if (toRead == C.RESULT_END_OF_INPUT) {
        return C.RESULT_END_OF_INPUT;
      }
      readStartPos = readPos;
    }

    copyFromRingToByteBuffer(readStartPos, target, toRead);
    advanceReadPos(readStartPos + toRead);
    return toRead;
  }

  @Override
  @Nullable
  public Uri getUri() {
    if (passthroughMode) {
      return upstream.getUri();
    }
    return opened ? (resolvedUri != null ? resolvedUri : upstream.getUri()) : null;
  }

  @Override
  public Map<String, List<String>> getResponseHeaders() {
    if (passthroughMode) {
      return upstream.getResponseHeaders();
    }
    return opened ? responseHeaders : Collections.emptyMap();
  }

  @Override
  public void close() throws IOException {
    if (!opened) {
      return;
    }
    opened = false;

    if (passthroughMode) {
      passthroughMode = false;
      IOException closeException = closeUpstreamReturningException();
      if (closeException != null) {
        throw closeException;
      }
      return;
    }

    Thread worker;
    boolean hadError;
    synchronized (lock) {
      stopRequested = true;
      hadError = readerError != null;
      worker = readerThread;
      readerThread = null;
      lock.notifyAll();
    }

    if (worker != null) {
      worker.interrupt();
      boolean wasInterrupted = Thread.interrupted();
      try {
        // Strictly serialize upstream ownership: never call upstream.close() concurrently with
        // an in-flight upstream.read() on another thread (OkHttp/Okio AsyncTimeout throws
        // IllegalStateException("Unbalanced enter/exit") if read() and close() overlap).
        // The worker always closes upstream in its own finally block before exiting.
        while (worker.isAlive()) {
          try {
            worker.join();
          } catch (InterruptedException ignored) {
            wasInterrupted = true;
          }
        }
      } finally {
        if (wasInterrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }

    IOException closeException = closeUpstreamReturningException();

    if (hadError) {
      synchronized (lock) {
        validStart = 0;
        readPos = 0;
        writePos = 0;
        reachedEof = false;
        readerError = null;
      }
      cachedRequestSpec = null;
      totalStreamLength = C.LENGTH_UNSET;
    }

    if (closeException != null) {
      throw closeException;
    }
  }

  /**
   * Releases the off-heap ring buffer and cached metadata once the owning {@link
   * ProgressiveMediaPeriod} is permanently released.
   */
  public void release() {
    try {
      close();
    } catch (IOException e) {
      Log.w(TAG, "Error closing CircularReadAheadDataSource during release", e);
    }
    if (upstreamOpenCount > 0 || cacheHitOpenCount > 0) {
      Log.i(
          TAG,
          "Released ring("
              + (capacity / 1024)
              + "KB): upstreamOpens="
              + upstreamOpenCount
              + " cacheHits="
              + cacheHitOpenCount
              + " upstreamReads="
              + upstreamReadCallCount
              + " readerCPU="
              + (readerThreadCpuNs / 1_000_000L)
              + "ms");
    }
    synchronized (lock) {
      validStart = 0;
      readPos = 0;
      writePos = 0;
      reachedEof = false;
      readerError = null;
    }
    cachedRequestSpec = null;
    resolvedUri = null;
    responseHeaders = Collections.emptyMap();
    totalStreamLength = C.LENGTH_UNSET;
    ringBuffer = null;
    consumerRingView = null;
    readerRingView = null;
    stagingBuffer = null;
  }

  /** Returns the number of {@link #open(DataSpec)} calls served immediately from the RAM ring. */
  public long getCacheHitOpenCount() {
    return cacheHitOpenCount;
  }

  /** Returns the number of upstream {@link DataSource#open(DataSpec)} calls issued. */
  public long getUpstreamOpenCount() {
    return upstreamOpenCount;
  }

  /** Returns the number of upstream {@link DataSource#read} calls issued by the reader thread. */
  public long getUpstreamReadCallCount() {
    return upstreamReadCallCount;
  }

  private boolean shouldBypass(DataSpec dataSpec) {
    if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET || dataSpec.position < 0) {
      return true;
    }
    // Small bounded range probes/metadata reads gain nothing from spawning a read-ahead worker.
    return dataSpec.length != C.LENGTH_UNSET && dataSpec.length <= INITIAL_CHUNK_BYTES;
  }

  private boolean canServeFromCache(DataSpec dataSpec) {
    if (ringBuffer == null || cachedRequestSpec == null) {
      return false;
    }
    if (dataSpec.length != C.LENGTH_UNSET
        || dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET
        || !dataSpec.uri.equals(cachedRequestSpec.uri)
        || !Objects.equals(dataSpec.key, cachedRequestSpec.key)
        || !dataSpec.httpRequestHeaders.equals(cachedRequestSpec.httpRequestHeaders)) {
      return false;
    }
    synchronized (lock) {
      if (readerError != null) {
        return false;
      }
      boolean insideWindow = dataSpec.position >= validStart && dataSpec.position < writePos;
      if (!insideWindow) {
        return false;
      }
      return reachedEof || totalStreamLength != C.LENGTH_UNSET;
    }
  }

  private boolean tryEnsureRingAllocated() {
    if (ringBuffer != null) {
      return true;
    }
    try {
      ByteBuffer allocated = ByteBuffer.allocateDirect(capacity);
      ringBuffer = allocated;
      consumerRingView = allocated.duplicate();
      readerRingView = allocated.duplicate();
      return true;
    } catch (OutOfMemoryError e) {
      Log.w(TAG, "Direct ring buffer allocation (" + capacity + "B) OOM; falling back to direct passthrough", e);
      ringBuffer = null;
      consumerRingView = null;
      readerRingView = null;
      return false;
    }
  }

  private void invalidateCacheLocked() {
    synchronized (lock) {
      validStart = 0;
      readPos = 0;
      writePos = 0;
      reachedEof = false;
      readerError = null;
    }
    cachedRequestSpec = null;
    totalStreamLength = C.LENGTH_UNSET;
  }

  @GuardedBy("lock")
  private int awaitAvailableBytesLocked(int requestedLength) throws IOException {
    while (true) {
      long available = writePos - readPos;
      if (available > 0) {
        return (int) min(requestedLength, available);
      }
      if (readerError != null) {
        throw readerError;
      }
      if (reachedEof || (openEndPos != C.LENGTH_UNSET && readPos >= openEndPos)) {
        return C.RESULT_END_OF_INPUT;
      }
      if (stopRequested) {
        throw new InterruptedIOException("CircularReadAheadDataSource closed");
      }
      if (readerExited) {
        // Defensive anti-hang guard: worker exited unexpectedly without setting EOF or error.
        return C.RESULT_END_OF_INPUT;
      }
      try {
        lock.wait();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("Interrupted while waiting for read-ahead data");
      }
    }
  }

  private void advanceReadPos(long newReadPos) {
    synchronized (lock) {
      readPos = newReadPos;
      long minValidStart = newReadPos - backReserveBytes;
      if (minValidStart > validStart) {
        validStart = minValidStart;
      }
      if (readerWaiting && computeWritableRoomLocked() >= minWakeupRoomBytes) {
        readerWaiting = false;
        lock.notifyAll();
      }
    }
  }

  @GuardedBy("lock")
  private long computeWritableRoomLocked() {
    long forwardBuffered = writePos - readPos;
    long usedBytes = writePos - validStart;
    return min(maxForwardBytes - forwardBuffered, capacity - usedBytes);
  }

  private void copyFromRingToArray(long streamPos, byte[] target, int offset, int length) {
    ByteBuffer view = Assertions.checkNotNull(consumerRingView);
    int start = (int) (streamPos % capacity);
    int firstPart = min(length, capacity - start);
    view.limit(start + firstPart).position(start);
    view.get(target, offset, firstPart);
    int remaining = length - firstPart;
    if (remaining > 0) {
      view.limit(remaining).position(0);
      view.get(target, offset + firstPart, remaining);
    }
  }

  private void copyFromRingToByteBuffer(long streamPos, ByteBuffer target, int length) {
    ByteBuffer view = Assertions.checkNotNull(consumerRingView);
    int start = (int) (streamPos % capacity);
    int firstPart = min(length, capacity - start);
    view.limit(start + firstPart).position(start);
    target.put(view);
    int remaining = length - firstPart;
    if (remaining > 0) {
      view.limit(remaining).position(0);
      target.put(view);
    }
  }

  private void copyFromArrayToRing(
      ByteBuffer view, long streamPos, byte[] source, int offset, int length) {
    int start = (int) (streamPos % capacity);
    int firstPart = min(length, capacity - start);
    view.limit(start + firstPart).position(start);
    view.put(source, offset, firstPart);
    int remaining = length - firstPart;
    if (remaining > 0) {
      view.limit(remaining).position(0);
      view.put(source, offset + firstPart, remaining);
    }
  }

  private boolean tryStartReaderThread(@Nullable DataSpec lazyOpenSpec) {
    ByteBuffer workerRingView = Assertions.checkNotNull(readerRingView);
    Thread worker =
        new Thread(
            () -> runReaderLoop(workerRingView, lazyOpenSpec),
            "ExoPlayer:CircularReadAhead");
    worker.setDaemon(true);
    synchronized (lock) {
      readerThread = worker;
      readerExited = false;
    }
    try {
      worker.start();
      return true;
    } catch (OutOfMemoryError e) {
      Log.w(TAG, "Failed to start CircularReadAhead thread; falling back to passthrough", e);
      synchronized (lock) {
        readerThread = null;
        readerExited = true;
      }
      return false;
    }
  }

  private void runReaderLoop(ByteBuffer workerRingView, @Nullable DataSpec lazyOpenSpec) {
    long cpuStartNs = 0L;
    int currentPriority = Process.THREAD_PRIORITY_DEFAULT;
    try {
      // Start at default priority (nice 0) so startup/seek first chunks never suffer priority
      // inversion, then drop to THREAD_PRIORITY_BACKGROUND (nice +10) as soon as >= 256 KB is
      // buffered ahead so speculative read-ahead never competes with nice=0 vendor codec HALs.
      Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
      cpuStartNs = Debug.threadCpuTimeNanos();
    } catch (Throwable ignored) {
      // Non-Android / Robolectric JVM.
    }

    try {
      if (lazyOpenSpec != null) {
        synchronized (lock) {
          if (stopRequested) {
            return;
          }
        }
        upstreamOpenCount++;
        upstreamOpen.set(true);
        long continuationLength = upstream.open(lazyOpenSpec);
        if (continuationLength == 0) {
          synchronized (lock) {
            reachedEof = true;
            lock.notifyAll();
          }
          return;
        }
      }

      boolean upstreamSupportsByteBuffer =
          upstream instanceof ByteBufferDataReader
              && ((ByteBufferDataReader) upstream).supportsByteBufferRead();
      byte[] localStaging = null;
      if (!upstreamSupportsByteBuffer) {
        localStaging = stagingBuffer;
        if (localStaging == null || localStaging.length < STEADY_CHUNK_BYTES) {
          localStaging = new byte[STEADY_CHUNK_BYTES];
          stagingBuffer = localStaging;
        }
      }

      int chunkStep = 0;
      int consecutiveZeroReads = 0;
      while (true) {
        long currentWritePos = 0;
        int maxWritable = 0;
        long forwardBufferedSnapshot = 0;
        synchronized (lock) {
          while (!stopRequested) {
            long allowed = computeWritableRoomLocked();
            if (allowed >= minWakeupRoomBytes) {
              readerWaiting = false;
              currentWritePos = writePos;
              forwardBufferedSnapshot = writePos - readPos;
              int targetChunk;
              if (chunkStep == 0) {
                targetChunk = INITIAL_CHUNK_BYTES;
              } else if (chunkStep == 1) {
                targetChunk = RAMP_1_CHUNK_BYTES;
              } else if (chunkStep == 2) {
                targetChunk = RAMP_2_CHUNK_BYTES;
              } else {
                targetChunk = STEADY_CHUNK_BYTES;
              }
              maxWritable = (int) min(allowed, targetChunk);
              break;
            }
            readerWaiting = true;
            lock.wait();
          }
          readerWaiting = false;
          if (stopRequested) {
            return;
          }
        }

        int desiredPriority =
            forwardBufferedSnapshot < STEADY_CHUNK_BYTES
                ? Process.THREAD_PRIORITY_DEFAULT
                : Process.THREAD_PRIORITY_BACKGROUND;
        if (desiredPriority != currentPriority) {
          currentPriority = desiredPriority;
          try {
            Process.setThreadPriority(desiredPriority);
          } catch (Throwable ignored) {
            // Non-Android / Robolectric JVM.
          }
        }

        int bytesRead;
        if (upstreamSupportsByteBuffer) {
          int start = (int) (currentWritePos % capacity);
          int contiguous = min(maxWritable, capacity - start);
          workerRingView.limit(start + contiguous).position(start);
          upstreamReadCallCount++;
          bytesRead = ((ByteBufferDataReader) upstream).read(workerRingView, contiguous);
        } else {
          upstreamReadCallCount++;
          bytesRead = upstream.read(Assertions.checkNotNull(localStaging), 0, maxWritable);
          if (bytesRead > 0) {
            copyFromArrayToRing(workerRingView, currentWritePos, localStaging, 0, bytesRead);
          }
        }

        if (bytesRead == C.RESULT_END_OF_INPUT) {
          synchronized (lock) {
            reachedEof = true;
            if (totalStreamLength == C.LENGTH_UNSET && cachedRequestSpec != null) {
              totalStreamLength = writePos;
              openEndPos = writePos;
            }
            lock.notifyAll();
          }
          return;
        }

        if (bytesRead > 0) {
          consecutiveZeroReads = 0;
          chunkStep = min(chunkStep + 1, 3);
          synchronized (lock) {
            writePos += bytesRead;
            lock.notifyAll();
          }
        } else {
          consecutiveZeroReads++;
          if (consecutiveZeroReads >= MAX_CONSECUTIVE_ZERO_READS) {
            throw new IOException("Upstream DataSource returned 0 bytes repeatedly");
          }
          Thread.yield();
        }
      }
    } catch (InterruptedException e) {
      // Expected on close().
    } catch (IOException e) {
      synchronized (lock) {
        if (!stopRequested) {
          readerError = e;
          lock.notifyAll();
        }
      }
    } catch (Throwable t) {
      // Catch RuntimeException AND Error (e.g. OutOfMemoryError) so consumer read() can NEVER
      // deadlock waiting on a dead worker thread.
      synchronized (lock) {
        if (!stopRequested) {
          readerError = new IOException("Unexpected CircularReadAhead error", t);
          lock.notifyAll();
        }
      }
    } finally {
      if (cpuStartNs > 0L) {
        try {
          long cpuEndNs = Debug.threadCpuTimeNanos();
          if (cpuEndNs > cpuStartNs) {
            readerThreadCpuNs += (cpuEndNs - cpuStartNs);
          }
        } catch (Throwable ignored) {
          // Non-Android / Robolectric JVM.
        }
      }
      closeUpstreamQuietly();
      synchronized (lock) {
        readerExited = true;
        lock.notifyAll();
      }
    }
  }

  private void closeUpstreamQuietly() {
    if (upstreamOpen.compareAndSet(true, false)) {
      try {
        upstream.close();
      } catch (IOException | RuntimeException ignored) {
        // Ignored when unwinding reader.
      }
    }
  }

  @Nullable
  private IOException closeUpstreamReturningException() {
    if (upstreamOpen.compareAndSet(true, false)) {
      try {
        upstream.close();
      } catch (IOException e) {
        return e;
      }
    }
    return null;
  }
}
