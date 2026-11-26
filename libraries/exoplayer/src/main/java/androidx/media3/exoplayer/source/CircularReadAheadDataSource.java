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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
 *       I/O by pumping upstream in large adaptive chunks ({@code 16 KB -> 64 KB -> 128 KB -> 256
 *       KB}) on a background reader thread, with a lock-free SPSC volatile fast path on steady
 *       reads/writes.
 *   <li>Retains the cached byte window across {@link #close()} / {@link #open(DataSpec)} calls for
 *       the same URI so short back-seeks, forward in-buffer seeks, and track switches inside the
 *       ring resume in 0 ms from RAM while reconnecting upstream asynchronously at the cache tail.
 *   <li>When constructed with a {@link DataSource.Factory}, detaches slow in-flight network reads
 *       asynchronously on seek {@link #close()} (after a brief fast-join grace window) so the new
 *       seek HTTP request launches immediately without blocking for 400-550 ms while still strictly
 *       serializing {@code read()} and {@code close()} per {@link DataSource} instance (preventing
 *       OkHttp/Okio {@code AsyncTimeout} overlap crashes).
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

  /**
   * Brief join window before detaching a slow in-flight network read during seek {@link #close()}.
   * Idle/parked or local workers exit within ~1 ms and reuse the existing {@link DataSource}; slow
   * remote TCP reads detach after 15 ms so the next seek starts immediately.
   */
  /* package */ static final long FAST_JOIN_TIMEOUT_MS = 15L;

  /** Maximum concurrent detached workers draining old HTTP sockets during rapid D-pad scrubbing. */
  /* package */ static final int MAX_DETACHED_WORKERS = 2;

  /** Initial upstream chunk size after open/seek to keep startup probes and seeks instantaneous. */
  /* package */ static final int INITIAL_CHUNK_BYTES = 16 * 1024;

  /** Early ramp-up upstream chunk size. */
  private static final int RAMP_1_CHUNK_BYTES = 64 * 1024;

  /** Mid ramp-up upstream chunk size. */
  private static final int RAMP_2_CHUNK_BYTES = 128 * 1024;

  /** Steady-state upstream chunk size to coalesce small reads and saturate high-bitrate links. */
  /* package */ static final int STEADY_CHUNK_BYTES = 256 * 1024;

  /** Per-reader-run ownership capsule so a detached worker never touches live ring/upstream state. */
  private static final class ReaderSession {
    final DataSource upstream;
    final AtomicBoolean upstreamOpen;
    final boolean directRingWrite;
    volatile boolean canceled;
    boolean detached;
    boolean exited;

    ReaderSession(DataSource upstream, AtomicBoolean upstreamOpen, boolean directRingWrite) {
      this.upstream = upstream;
      this.upstreamOpen = upstreamOpen;
      this.directRingWrite = directRingWrite;
    }

    void closeUpstreamQuietly() {
      if (upstreamOpen.compareAndSet(true, false)) {
        try {
          upstream.close();
        } catch (IOException | RuntimeException ignored) {
          // Ignored when unwinding reader.
        }
      }
    }
  }

  @Nullable private final DataSource.Factory upstreamFactory;
  private final List<TransferListener> transferListeners = new ArrayList<>(2);
  private final int capacity;
  private final int backReserveBytes;
  private final int maxForwardBytes;
  private final int minWakeupRoomBytes;
  private final Object lock = new Object();
  /**
   * Uncontended fence lock held only while copying a fetched chunk into {@link #ringBuffer} (never
   * held during network I/O or consumer reads). Acquiring this fence during {@link #close()}
   * guarantees a detached worker will never touch {@link #ringBuffer} again.
   */
  private final Object ringWriteLock = new Object();

  private final AtomicInteger activeDetachedWorkers = new AtomicInteger(0);

  @Nullable private DataSource currentUpstream;
  private AtomicBoolean currentUpstreamOpen = new AtomicBoolean(false);

  @Nullable private ByteBuffer ringBuffer;
  @Nullable private ByteBuffer consumerRingView;
  @Nullable private ByteBuffer readerRingView;
  @Nullable private byte[] stagingBuffer;

  // Session state.
  private boolean opened;
  private boolean passthroughMode;
  private long openEndPos = C.LENGTH_UNSET;
  @Nullable private Thread readerThread;
  @Nullable private ReaderSession activeSession;

  // Cached metadata retained across close()/open() for in-buffer seek hits.
  @Nullable private DataSpec cachedRequestSpec;
  @Nullable private Uri resolvedUri;
  private Map<String, List<String>> responseHeaders = Collections.emptyMap();
  private long totalStreamLength = C.LENGTH_UNSET;

  // Lock-free SPSC ring indices (single producer updates writePos; single consumer updates
  // validStart and readPos; Dekker wait flags synchronize parking/waking via lock).
  private volatile long validStart;
  private volatile long readPos;
  private volatile long writePos;
  private volatile boolean readerWaiting;
  private volatile boolean consumerWaiting;

  @GuardedBy("lock")
  private boolean reachedEof;

  @GuardedBy("lock")
  private boolean stopRequested;

  @GuardedBy("lock")
  private boolean readerExited;

  @GuardedBy("lock")
  @Nullable
  private IOException readerError;

  // Telemetry counters for diagnostics & tests.
  private volatile long cacheHitOpenCount;
  private volatile long upstreamOpenCount;
  private volatile long upstreamReadCallCount;
  private volatile long asyncDetachCount;
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
   * Creates a circular read-ahead wrapper around a single {@code upstream} {@link DataSource}.
   *
   * @param upstream The underlying {@link DataSource}.
   * @param bufferBytes Total ring buffer capacity in bytes (75% forward, 25% back).
   */
  public CircularReadAheadDataSource(DataSource upstream, int bufferBytes) {
    this(upstream, /* upstreamFactory= */ null, /* initialTransferListener= */ null, bufferBytes);
  }

  /**
   * Creates a circular read-ahead wrapper with an optional {@link DataSource.Factory} so slow
   * in-flight HTTP reads can be detached asynchronously on seek {@link #close()} without blocking
   * the next seek request.
   *
   * @param initialUpstream The initial underlying {@link DataSource} (already configured with
   *     {@code initialTransferListener}, if any).
   * @param upstreamFactory Optional factory used to create fresh {@link DataSource} instances after
   *     an async seek detach.
   * @param initialTransferListener Optional transfer listener already attached to {@code
   *     initialUpstream}, remembered so future factory-created upstreams receive it too.
   * @param bufferBytes Total ring buffer capacity in bytes (75% forward, 25% back).
   */
  public CircularReadAheadDataSource(
      DataSource initialUpstream,
      @Nullable DataSource.Factory upstreamFactory,
      @Nullable TransferListener initialTransferListener,
      int bufferBytes) {
    this.currentUpstream = Assertions.checkNotNull(initialUpstream);
    this.upstreamFactory = upstreamFactory;
    if (initialTransferListener != null) {
      this.transferListeners.add(initialTransferListener);
    }
    this.capacity = min(MAX_BUFFER_BYTES, max(MIN_BUFFER_BYTES, bufferBytes));
    this.backReserveBytes = this.capacity / 4;
    this.maxForwardBytes = this.capacity - this.backReserveBytes;
    this.minWakeupRoomBytes = min(STEADY_CHUNK_BYTES, this.maxForwardBytes / 2);
  }

  @Override
  public void addTransferListener(TransferListener transferListener) {
    Assertions.checkNotNull(transferListener);
    if (!transferListeners.contains(transferListener)) {
      transferListeners.add(transferListener);
    }
    if (currentUpstream != null) {
      currentUpstream.addTransferListener(transferListener);
    }
  }

  @Override
  public long open(DataSpec dataSpec) throws IOException {
    if (opened) {
      close();
    }

    if (shouldBypass(dataSpec) || !tryEnsureRingAllocated()) {
      return openDirectPassthrough(dataSpec);
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
        readerWaiting = false;
        consumerWaiting = false;
        readerExited = reachedEof;
        readerError = null;
        needBackgroundContinuation = !reachedEof;
        continuationPos = writePos;
      }
      openEndPos = totalStreamLength;
      passthroughMode = false;
      opened = true;
      cacheHitOpenCount++;

      if (needBackgroundContinuation) {
        DataSource upstream = ensureUpstream();
        DataSpec continuationSpec =
            dataSpec.buildUpon().setPosition(continuationPos).setLength(C.LENGTH_UNSET).build();
        if (!tryStartReaderThread(upstream, currentUpstreamOpen, /* lazyOpenSpec= */ continuationSpec)) {
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
    DataSource upstream = ensureUpstream();
    upstreamOpenCount++;
    currentUpstreamOpen.set(true);
    long openedLength;
    try {
      openedLength = upstream.open(dataSpec);
    } catch (IOException | RuntimeException e) {
      // Honor DataSource contract: always close upstream after a failed open() attempt.
      closeCurrentUpstreamQuietly();
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
      readerWaiting = false;
      consumerWaiting = false;
      reachedEof = immediateEof;
      stopRequested = false;
      readerExited = immediateEof;
      readerError = null;
    }

    passthroughMode = false;
    opened = true;

    if (!immediateEof
        && !tryStartReaderThread(upstream, currentUpstreamOpen, /* lazyOpenSpec= */ null)) {
      // Thread creation OOM: upstream is already open at dataSpec.position, so seamlessly serve
      // this session in direct passthrough mode without failing playback!
      passthroughMode = true;
      invalidateCacheLocked();
    }
    return openedLength;
  }

  private long openDirectPassthrough(DataSpec dataSpec) throws IOException {
    DataSource upstream = ensureUpstream();
    passthroughMode = true;
    opened = true;
    upstreamOpenCount++;
    currentUpstreamOpen.set(true);
    try {
      return upstream.open(dataSpec);
    } catch (IOException | RuntimeException e) {
      closeCurrentUpstreamQuietly();
      opened = false;
      passthroughMode = false;
      throw e;
    }
  }

  private DataSource ensureUpstream() {
    if (currentUpstream == null) {
      DataSource created = Assertions.checkNotNull(upstreamFactory).createDataSource();
      for (int i = 0; i < transferListeners.size(); i++) {
        created.addTransferListener(transferListeners.get(i));
      }
      currentUpstream = created;
      currentUpstreamOpen = new AtomicBoolean(false);
    }
    return currentUpstream;
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
      return Assertions.checkNotNull(currentUpstream).read(buffer, offset, length);
    }

    // Lock-free SPSC fast path: volatile read of writePos establishes happens-before with the
    // producer's writes into ringBuffer.
    long readStartPos = readPos;
    long available = writePos - readStartPos;
    int toRead;
    if (available > 0) {
      toRead = (int) min(length, available);
    } else {
      synchronized (lock) {
        toRead = awaitAvailableBytesLocked(length);
        if (toRead == C.RESULT_END_OF_INPUT) {
          return C.RESULT_END_OF_INPUT;
        }
        readStartPos = readPos;
      }
    }

    copyFromRingToArray(readStartPos, buffer, offset, toRead);
    advanceReadPos(readStartPos + toRead);
    return toRead;
  }

  @Override
  public boolean supportsByteBufferRead() {
    if (passthroughMode) {
      return currentUpstream instanceof ByteBufferDataReader
          && ((ByteBufferDataReader) currentUpstream).supportsByteBufferRead();
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
      if (!(currentUpstream instanceof ByteBufferDataReader)
          || !((ByteBufferDataReader) currentUpstream).supportsByteBufferRead()) {
        throw new UnsupportedOperationException();
      }
      return ((ByteBufferDataReader) currentUpstream).read(target, length);
    }

    int maxRead = min(length, target.remaining());
    if (maxRead == 0) {
      return 0;
    }

    long readStartPos = readPos;
    long available = writePos - readStartPos;
    int toRead;
    if (available > 0) {
      toRead = (int) min(maxRead, available);
    } else {
      synchronized (lock) {
        toRead = awaitAvailableBytesLocked(maxRead);
        if (toRead == C.RESULT_END_OF_INPUT) {
          return C.RESULT_END_OF_INPUT;
        }
        readStartPos = readPos;
      }
    }

    copyFromRingToByteBuffer(readStartPos, target, toRead);
    advanceReadPos(readStartPos + toRead);
    return toRead;
  }

  @Override
  @Nullable
  public Uri getUri() {
    if (passthroughMode) {
      return currentUpstream != null ? currentUpstream.getUri() : null;
    }
    if (!opened) {
      return null;
    }
    if (resolvedUri != null) {
      return resolvedUri;
    }
    return currentUpstream != null ? currentUpstream.getUri() : null;
  }

  @Override
  public Map<String, List<String>> getResponseHeaders() {
    if (passthroughMode) {
      return currentUpstream != null
          ? currentUpstream.getResponseHeaders()
          : Collections.emptyMap();
    }
    return opened ? responseHeaders : Collections.emptyMap();
  }

  @Override
  public void close() throws IOException {
    closeInternal(/* allowAsyncDetach= */ true);
  }

  private void closeInternal(boolean allowAsyncDetach) throws IOException {
    if (!opened) {
      return;
    }
    opened = false;

    if (passthroughMode) {
      passthroughMode = false;
      IOException closeException = closeCurrentUpstreamReturningException();
      if (closeException != null) {
        throw closeException;
      }
      return;
    }

    Thread worker;
    ReaderSession session;
    boolean hadError;
    synchronized (lock) {
      stopRequested = true;
      hadError = readerError != null;
      worker = readerThread;
      readerThread = null;
      session = activeSession;
      activeSession = null;
      if (session != null) {
        session.canceled = true;
      }
      lock.notifyAll();
    }

    boolean detached = false;
    if (worker != null) {
      worker.interrupt();
      boolean canDetach =
          allowAsyncDetach
              && upstreamFactory != null
              && session != null
              && !session.directRingWrite
              && tryReserveDetachedSlot();
      boolean wasInterrupted = Thread.interrupted();
      try {
        if (canDetach) {
          // Wait briefly for idle/fast workers to exit cleanly and reuse the existing DataSource.
          long deadlineNs = System.nanoTime() + FAST_JOIN_TIMEOUT_MS * 1_000_000L;
          while (worker.isAlive()) {
            long remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0L) {
              break;
            }
            try {
              worker.join(remainingMs);
            } catch (InterruptedException ignored) {
              wasInterrupted = true;
            }
          }
          if (worker.isAlive()) {
            // Fence against any in-flight copy into ringBuffer (~50 us max) and coordinate exact
            // detach handoff with the worker's finally block so activeDetachedWorkers can never
            // leak a slot even if the worker exits concurrently.
            synchronized (ringWriteLock) {
              if (!session.exited) {
                session.detached = true;
                detached = true;
              }
            }
          }
          if (detached) {
            // Relinquish ownership of both currentUpstream and stagingBuffer to the detached worker
            // so the next seek immediately opens a fresh DataSource with zero Okio overlap.
            currentUpstream = null;
            currentUpstreamOpen = new AtomicBoolean(false);
            stagingBuffer = null;
            asyncDetachCount++;
          } else {
            // Worker exited before detach; release the unused slot and reuse currentUpstream.
            activeDetachedWorkers.decrementAndGet();
          }
        } else {
          // Strictly serialize upstream ownership: never call upstream.close() concurrently with
          // an in-flight upstream.read() on another thread (OkHttp/Okio AsyncTimeout throws
          // IllegalStateException("Unbalanced enter/exit") if read() and close() overlap).
          while (worker.isAlive()) {
            try {
              worker.join();
            } catch (InterruptedException ignored) {
              wasInterrupted = true;
            }
          }
        }
      } finally {
        if (wasInterrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }

    IOException closeException = detached ? null : closeCurrentUpstreamReturningException();

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

  private boolean tryReserveDetachedSlot() {
    while (true) {
      int current = activeDetachedWorkers.get();
      if (current >= MAX_DETACHED_WORKERS) {
        return false;
      }
      if (activeDetachedWorkers.compareAndSet(current, current + 1)) {
        return true;
      }
    }
  }

  /**
   * Releases the off-heap ring buffer and cached metadata once the owning {@link
   * ProgressiveMediaPeriod} is permanently released.
   */
  public void release() {
    try {
      closeInternal(/* allowAsyncDetach= */ true);
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
              + " asyncDetaches="
              + asyncDetachCount
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
      readerWaiting = false;
      consumerWaiting = false;
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

  /** Returns the number of slow in-flight network readers detached asynchronously during close. */
  public long getAsyncDetachCount() {
    return asyncDetachCount;
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
      Log.w(
          TAG,
          "Direct ring buffer allocation (" + capacity + "B) OOM; falling back to passthrough",
          e);
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
      readerWaiting = false;
      consumerWaiting = false;
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
        consumerWaiting = false;
        return (int) min(requestedLength, available);
      }
      if (readerError != null) {
        consumerWaiting = false;
        throw readerError;
      }
      if (reachedEof || (openEndPos != C.LENGTH_UNSET && readPos >= openEndPos)) {
        consumerWaiting = false;
        return C.RESULT_END_OF_INPUT;
      }
      if (stopRequested) {
        consumerWaiting = false;
        throw new InterruptedIOException("CircularReadAheadDataSource closed");
      }
      if (readerExited) {
        consumerWaiting = false;
        // Defensive anti-hang guard: worker exited unexpectedly without setting EOF or error.
        return C.RESULT_END_OF_INPUT;
      }
      consumerWaiting = true;
      // Dekker re-check after publishing consumerWaiting = true so a lock-free writePos update
      // from the producer can never miss waking us up.
      available = writePos - readPos;
      if (available > 0) {
        consumerWaiting = false;
        return (int) min(requestedLength, available);
      }
      try {
        lock.wait();
      } catch (InterruptedException e) {
        consumerWaiting = false;
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("Interrupted while waiting for read-ahead data");
      }
    }
  }

  private void advanceReadPos(long newReadPos) {
    long minValidStart = newReadPos - backReserveBytes;
    if (minValidStart > validStart) {
      validStart = minValidStart;
    }
    // Volatile publish of readPos AFTER ring copy completes and BEFORE checking readerWaiting.
    readPos = newReadPos;
    if (readerWaiting) {
      synchronized (lock) {
        if (readerWaiting && computeWritableRoom() >= minWakeupRoomBytes) {
          readerWaiting = false;
          lock.notifyAll();
        }
      }
    }
  }

  private long computeWritableRoom() {
    long rPos = readPos;
    long vStart = validStart;
    long wPos = writePos;
    long forwardBuffered = wPos - rPos;
    long usedBytes = wPos - vStart;
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

  private boolean tryStartReaderThread(
      DataSource sessionUpstream,
      AtomicBoolean sessionUpstreamOpen,
      @Nullable DataSpec lazyOpenSpec) {
    ByteBuffer workerRingView = Assertions.checkNotNull(readerRingView);
    boolean directRingWrite =
        sessionUpstream instanceof ByteBufferDataReader
            && ((ByteBufferDataReader) sessionUpstream).supportsByteBufferRead();
    ReaderSession session =
        new ReaderSession(sessionUpstream, sessionUpstreamOpen, directRingWrite);
    byte[] localStaging = null;
    if (!directRingWrite) {
      localStaging = stagingBuffer;
      if (localStaging == null || localStaging.length < STEADY_CHUNK_BYTES) {
        localStaging = new byte[STEADY_CHUNK_BYTES];
        stagingBuffer = localStaging;
      }
    }
    final byte[] workerStaging = localStaging;
    Thread worker =
        new Thread(
            () -> runReaderLoop(session, workerRingView, workerStaging, lazyOpenSpec),
            "ExoPlayer:CircularReadAhead");
    worker.setDaemon(true);
    synchronized (lock) {
      readerThread = worker;
      activeSession = session;
      readerExited = false;
    }
    try {
      worker.start();
      return true;
    } catch (OutOfMemoryError e) {
      Log.w(TAG, "Failed to start CircularReadAhead thread; falling back to passthrough", e);
      synchronized (lock) {
        readerThread = null;
        activeSession = null;
        readerExited = true;
      }
      return false;
    }
  }

  private void runReaderLoop(
      ReaderSession session,
      ByteBuffer workerRingView,
      @Nullable byte[] localStaging,
      @Nullable DataSpec lazyOpenSpec) {
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
        if (session.canceled) {
          return;
        }
        upstreamOpenCount++;
        session.upstreamOpen.set(true);
        long continuationLength = session.upstream.open(lazyOpenSpec);
        if (session.canceled) {
          return;
        }
        if (continuationLength == 0) {
          synchronized (lock) {
            if (!session.canceled) {
              reachedEof = true;
              lock.notifyAll();
            }
          }
          return;
        }
      }

      int chunkStep = 0;
      int consecutiveZeroReads = 0;
      while (!session.canceled) {
        long currentWritePos = writePos;
        long allowed = computeWritableRoom();
        long forwardBufferedSnapshot = currentWritePos - readPos;
        if (allowed < minWakeupRoomBytes) {
          synchronized (lock) {
            while (!stopRequested && !session.canceled) {
              allowed = computeWritableRoom();
              if (allowed >= minWakeupRoomBytes) {
                readerWaiting = false;
                currentWritePos = writePos;
                forwardBufferedSnapshot = currentWritePos - readPos;
                break;
              }
              readerWaiting = true;
              // Dekker re-check after publishing readerWaiting = true to close any window with
              // lock-free advanceReadPos().
              allowed = computeWritableRoom();
              if (allowed >= minWakeupRoomBytes) {
                readerWaiting = false;
                currentWritePos = writePos;
                forwardBufferedSnapshot = currentWritePos - readPos;
                break;
              }
              lock.wait();
            }
            readerWaiting = false;
            if (stopRequested || session.canceled) {
              return;
            }
          }
        }

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
        int maxWritable = (int) min(allowed, targetChunk);

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
        if (session.directRingWrite) {
          int start = (int) (currentWritePos % capacity);
          int contiguous = min(maxWritable, capacity - start);
          workerRingView.limit(start + contiguous).position(start);
          upstreamReadCallCount++;
          bytesRead = ((ByteBufferDataReader) session.upstream).read(workerRingView, contiguous);
        } else {
          upstreamReadCallCount++;
          bytesRead = session.upstream.read(Assertions.checkNotNull(localStaging), 0, maxWritable);
        }

        if (session.canceled) {
          return;
        }

        if (bytesRead == C.RESULT_END_OF_INPUT) {
          synchronized (lock) {
            if (!session.canceled) {
              reachedEof = true;
              if (totalStreamLength == C.LENGTH_UNSET && cachedRequestSpec != null) {
                totalStreamLength = writePos;
                openEndPos = writePos;
              }
              lock.notifyAll();
            }
          }
          return;
        }

        if (bytesRead > 0) {
          consecutiveZeroReads = 0;
          chunkStep = min(chunkStep + 1, 3);
          if (session.directRingWrite) {
            writePos = currentWritePos + bytesRead;
          } else {
            // Uncontended fence lock guarantees close() never detaches mid-copy into ringBuffer.
            synchronized (ringWriteLock) {
              if (session.canceled) {
                return;
              }
              copyFromArrayToRing(
                  workerRingView, currentWritePos, Assertions.checkNotNull(localStaging), 0, bytesRead);
              writePos = currentWritePos + bytesRead;
            }
          }
          // Lock-free publish complete; wake consumer only if it is parked waiting for bytes.
          if (consumerWaiting) {
            synchronized (lock) {
              if (consumerWaiting) {
                consumerWaiting = false;
                lock.notifyAll();
              }
            }
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
      if (!session.canceled) {
        synchronized (lock) {
          if (!stopRequested && !session.canceled) {
            readerError = e;
            lock.notifyAll();
          }
        }
      }
    } catch (Throwable t) {
      // Catch RuntimeException AND Error (e.g. OutOfMemoryError) so consumer read() can NEVER
      // deadlock waiting on a dead worker thread.
      if (!session.canceled) {
        synchronized (lock) {
          if (!stopRequested && !session.canceled) {
            readerError = new IOException("Unexpected CircularReadAhead error", t);
            lock.notifyAll();
          }
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
      session.closeUpstreamQuietly();
      // Coordinate detach decision with close() under ringWriteLock so we never leak an
      // activeDetachedWorkers slot or clobber a new session's state.
      boolean wasDetached;
      synchronized (ringWriteLock) {
        session.exited = true;
        wasDetached = session.detached;
      }
      if (wasDetached) {
        activeDetachedWorkers.decrementAndGet();
      } else {
        synchronized (lock) {
          if (activeSession == session || activeSession == null) {
            readerExited = true;
            lock.notifyAll();
          }
        }
      }
    }
  }

  private void closeCurrentUpstreamQuietly() {
    DataSource upstream = currentUpstream;
    if (upstream != null && currentUpstreamOpen.compareAndSet(true, false)) {
      try {
        upstream.close();
      } catch (IOException | RuntimeException ignored) {
        // Ignored when unwinding reader.
      }
    }
  }

  @Nullable
  private IOException closeCurrentUpstreamReturningException() {
    DataSource upstream = currentUpstream;
    if (upstream != null && currentUpstreamOpen.compareAndSet(true, false)) {
      try {
        upstream.close();
      } catch (IOException e) {
        return e;
      }
    }
    return null;
  }
}
