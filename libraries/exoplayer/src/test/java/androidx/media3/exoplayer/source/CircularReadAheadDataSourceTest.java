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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.NuvioEngineConfig;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link CircularReadAheadDataSource}. */
@RunWith(AndroidJUnit4.class)
public final class CircularReadAheadDataSourceTest {

  private static final Uri TEST_URI = Uri.parse("https://cdn.example.com/remux4k.mkv");

  @Test
  public void smallExtractorReads_coalescedAcrossMultipleRingWraps_matchExactBytes()
      throws Exception {
    byte[] payload = createPatternBytes(2 * 1024 * 1024 + 137);
    FakeSeekableDataSource upstream = new FakeSeekableDataSource(payload);
    CircularReadAheadDataSource ds =
        new CircularReadAheadDataSource(upstream, /* bufferBytes= */ 512 * 1024);

    try {
      long openedLen = ds.open(new DataSpec(TEST_URI));
      assertThat(openedLen).isEqualTo(payload.length);
      assertThat(ds.getUri()).isEqualTo(TEST_URI);

      // Simulate MatroskaExtractor's 184-byte EBML small reads across >4x ring wraps.
      byte[] actual = new byte[payload.length];
      int offset = 0;
      int smallReadCalls = 0;
      while (offset < payload.length) {
        int want = Math.min(184, payload.length - offset);
        int read = ds.read(actual, offset, want);
        assertThat(read).isGreaterThan(0);
        offset += read;
        smallReadCalls++;
      }
      assertThat(ds.read(new byte[16], 0, 16)).isEqualTo(C.RESULT_END_OF_INPUT);
      assertThat(actual).isEqualTo(payload);

      // 11,000+ 184-byte reads should be coalesced into <= 30 large upstream reads!
      assertThat(smallReadCalls).isGreaterThan(10_000);
      assertThat(ds.getUpstreamReadCallCount()).isLessThan(40L);
    } finally {
      ds.release();
    }
    assertThat(upstream.openCount.get()).isEqualTo(1);
    assertThat(upstream.closeCount.get()).isEqualTo(1);
  }

  @Test
  public void byteBufferDataReader_zeroCopyDirectRead_matchesExactBytesAcrossWrap()
      throws Exception {
    byte[] payload = createPatternBytes(1_200_000);
    FakeSeekableDataSource upstream = new FakeSeekableDataSource(payload);
    CircularReadAheadDataSource ds =
        new CircularReadAheadDataSource(upstream, /* bufferBytes= */ 512 * 1024);

    try {
      ds.open(new DataSpec(TEST_URI));
      assertThat(ds.supportsByteBufferRead()).isTrue();

      ByteBuffer directTarget = ByteBuffer.allocateDirect(32 * 1024);
      byte[] actual = new byte[payload.length];
      int offset = 0;
      while (offset < payload.length) {
        directTarget.clear();
        int want = Math.min(directTarget.capacity(), payload.length - offset);
        int read = ds.read(directTarget, want);
        assertThat(read).isGreaterThan(0);
        assertThat(directTarget.position()).isEqualTo(read);
        directTarget.flip();
        directTarget.get(actual, offset, read);
        offset += read;
      }
      directTarget.clear();
      assertThat(ds.read(directTarget, 64)).isEqualTo(C.RESULT_END_OF_INPUT);
      assertThat(actual).isEqualTo(payload);
    } finally {
      ds.release();
    }
  }

  @Test
  public void backSeekInsideBackReserve_hitsRamCacheWithoutReopeningAtSeekPos() throws Exception {
    byte[] payload = createPatternBytes(1_500_000);
    FakeSeekableDataSource upstream = new FakeSeekableDataSource(payload);
    CircularReadAheadDataSource ds =
        new CircularReadAheadDataSource(upstream, /* bufferBytes= */ 512 * 1024);

    try {
      ds.open(new DataSpec(TEST_URI));
      // Read 300 KB sequentially; backReserve is 128 KB so [172 KB .. writePos] is cached.
      byte[] firstChunk = readExactly(ds, 300 * 1024);
      assertRangeEquals(payload, 0, firstChunk);
      ds.close();

      // Re-open 64 KB behind last readPos (236 KB) -> must be an instant RAM cache hit!
      int backSeekPos = 236 * 1024;
      long remaining = ds.open(new DataSpec.Builder().setUri(TEST_URI).setPosition(backSeekPos).build());
      assertThat(remaining).isEqualTo(payload.length - backSeekPos);
      assertThat(ds.getCacheHitOpenCount()).isEqualTo(1L);

      // Verify entire remainder of stream (crossing from cached RAM into async upstream continuation)
      // matches byte-for-byte.
      byte[] rest = readAllRemaining(ds);
      assertRangeEquals(payload, backSeekPos, rest);
    } finally {
      ds.release();
    }
  }

  @Test
  public void seekOutsideRingWindow_missesCacheAndReadsFromNewPosition() throws Exception {
    byte[] payload = createPatternBytes(2_000_000);
    FakeSeekableDataSource upstream = new FakeSeekableDataSource(payload);
    CircularReadAheadDataSource ds =
        new CircularReadAheadDataSource(upstream, /* bufferBytes= */ 512 * 1024);

    try {
      ds.open(new DataSpec(TEST_URI));
      byte[] head = readExactly(ds, 8 * 1024);
      assertRangeEquals(payload, 0, head);
      ds.close();

      // Jump far ahead to 1.8 MB (outside the 512 KB ring).
      int farPos = 1_800_000;
      long len = ds.open(new DataSpec.Builder().setUri(TEST_URI).setPosition(farPos).build());
      assertThat(len).isEqualTo(payload.length - farPos);
      assertThat(ds.getCacheHitOpenCount()).isEqualTo(0L);
      assertThat(ds.getUpstreamOpenCount()).isEqualTo(2L);

      byte[] tail = readAllRemaining(ds);
      assertRangeEquals(payload, farPos, tail);
    } finally {
      ds.release();
    }
  }

  @Test
  public void smallBoundedDataSpec_bypassesRingBuffer() throws Exception {
    byte[] payload = createPatternBytes(128 * 1024);
    FakeSeekableDataSource upstream = new FakeSeekableDataSource(payload);
    CircularReadAheadDataSource ds =
        new CircularReadAheadDataSource(upstream, /* bufferBytes= */ 512 * 1024);

    try {
      DataSpec smallSpec =
          new DataSpec.Builder().setUri(TEST_URI).setPosition(1000).setLength(4096).build();
      assertThat(ds.open(smallSpec)).isEqualTo(4096);
      byte[] actual = readAllRemaining(ds);
      assertRangeEquals(payload, 1000, actual);
      assertThat(ds.getUpstreamReadCallCount()).isEqualTo(0L);
    } finally {
      ds.close();
    }
  }

  @Test
  public void upstreamErrorDuringRead_propagatesToConsumerAndInvalidatesCache() throws Exception {
    IOException expectedError = new IOException("simulated CDN reset");
    byte[] payload = createPatternBytes(1_000_000);
    FakeSeekableDataSource upstream = new FakeSeekableDataSource(payload);
    upstream.failAfterBytes = 100 * 1024;
    upstream.failureException = expectedError;

    CircularReadAheadDataSource ds =
        new CircularReadAheadDataSource(upstream, /* bufferBytes= */ 512 * 1024);
    try {
      ds.open(new DataSpec(TEST_URI));
      IOException thrown =
          assertThrows(
              IOException.class,
              () -> {
                byte[] buf = new byte[16 * 1024];
                while (ds.read(buf, 0, buf.length) != C.RESULT_END_OF_INPUT) {
                  // Drain until error.
                }
              });
      assertThat(thrown).isSameInstanceAs(expectedError);
      ds.close();

      // Next open must NOT hit stale cache from errored session.
      upstream.failAfterBytes = Long.MAX_VALUE;
      ds.open(new DataSpec(TEST_URI));
      assertThat(ds.getCacheHitOpenCount()).isEqualTo(0L);
      byte[] full = readAllRemaining(ds);
      assertThat(full).isEqualTo(payload);
    } finally {
      ds.release();
    }
  }

  @Test
  public void closeDuringInFlightUpstreamRead_neverOverlapsReadAndCloseEvenWhenCallerInterrupted()
      throws Exception {
    CountDownLatch readEntered = new CountDownLatch(1);
    CountDownLatch upstreamClosed = new CountDownLatch(1);
    AtomicBoolean inRead = new AtomicBoolean(false);
    AtomicBoolean overlapDetected = new AtomicBoolean(false);

    DataSource strictNonConcurrentUpstream =
        new DataSource() {
          @Override
          public void addTransferListener(TransferListener transferListener) {}

          @Override
          public long open(DataSpec dataSpec) {
            return 10_000_000L;
          }

          @Override
          public int read(byte[] buffer, int offset, int length) throws IOException {
            inRead.set(true);
            try {
              readEntered.countDown();
              // Simulate an OkHttp read completing after 60 ms (> old 25 ms join timeout) even if
              // SocketInputStream ignores interrupt until packet arrival.
              long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(60);
              while (System.nanoTime() < deadline) {
                try {
                  Thread.sleep(5);
                } catch (InterruptedException ignored) {
                  // Simulate blocking socket read finishing current packet.
                }
              }
              return Math.min(length, 1024);
            } finally {
              inRead.set(false);
            }
          }

          @Override
          public Uri getUri() {
            return TEST_URI;
          }

          @Override
          public void close() {
            // Mirrors OkHttp/Okio AsyncTimeout throwing IllegalStateException if close() runs
            // concurrently with read().
            if (inRead.get()) {
              overlapDetected.set(true);
              throw new IllegalStateException("Unbalanced enter/exit");
            }
            upstreamClosed.countDown();
          }
        };

    CircularReadAheadDataSource ds =
        new CircularReadAheadDataSource(strictNonConcurrentUpstream, /* bufferBytes= */ 512 * 1024);
    ds.open(new DataSpec(TEST_URI));
    assertThat(readEntered.await(2, TimeUnit.SECONDS)).isTrue();

    // Simulate Loader.cancelLoad() having interrupted the caller thread right before close().
    Thread.currentThread().interrupt();
    try {
      ds.close();
    } finally {
      assertThat(Thread.interrupted()).isTrue();
    }
    assertThat(overlapDetected.get()).isFalse();
    assertThat(upstreamClosed.await(1, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  public void shouldWrap_andNuvioEngineConfigIsolation() {
    DataSource dummy = new FakeSeekableDataSource(new byte[16]);
    assertThat(CircularReadAheadDataSource.shouldWrap(Uri.parse("https://cdn.example.com/a.mkv"), dummy))
        .isTrue();
    assertThat(CircularReadAheadDataSource.shouldWrap(Uri.parse("http://192.168.1.5/a.mkv"), dummy))
        .isTrue();
    assertThat(CircularReadAheadDataSource.shouldWrap(Uri.parse("file:///sdcard/a.mkv"), dummy))
        .isTrue();
    assertThat(CircularReadAheadDataSource.shouldWrap(Uri.parse("http://127.0.0.1:8080/s"), dummy))
        .isFalse();
    assertThat(CircularReadAheadDataSource.shouldWrap(Uri.parse("http://localhost:8080/s"), dummy))
        .isFalse();
    assertThat(CircularReadAheadDataSource.shouldWrap(Uri.parse("asset:///a.mp4"), dummy))
        .isFalse();
    assertThat(
            CircularReadAheadDataSource.shouldWrap(
                TEST_URI, new CircularReadAheadDataSource(dummy, 512 * 1024)))
        .isFalse();

    // Verify 100% Stock isolation vs Decoupled/Nuvio opt-in.
    assertThat(NuvioEngineConfig.stockMode().getCircularReadAheadBufferBytes()).isEqualTo(0);
    assertThat(NuvioEngineConfig.stockWithDecoupledAvSync().getCircularReadAheadBufferBytes())
        .isEqualTo(NuvioEngineConfig.DEFAULT_CIRCULAR_READ_AHEAD_BYTES);
    assertThat(NuvioEngineConfig.nuvioMode().getCircularReadAheadBufferBytes())
        .isEqualTo(NuvioEngineConfig.DEFAULT_CIRCULAR_READ_AHEAD_BYTES);
  }

  @Test
  public void failedUpstreamOpen_alwaysClosesUpstreamToPreventSocketLeak() {
    AtomicInteger closeCount = new AtomicInteger();
    DataSource failingOpenUpstream =
        new DataSource() {
          @Override
          public void addTransferListener(TransferListener transferListener) {}

          @Override
          public long open(DataSpec dataSpec) throws IOException {
            throw new IOException("HTTP 403 Forbidden");
          }

          @Override
          public int read(byte[] buffer, int offset, int length) {
            return C.RESULT_END_OF_INPUT;
          }

          @Override
          public Uri getUri() {
            return TEST_URI;
          }

          @Override
          public void close() {
            closeCount.incrementAndGet();
          }
        };

    CircularReadAheadDataSource ds =
        new CircularReadAheadDataSource(failingOpenUpstream, /* bufferBytes= */ 512 * 1024);
    assertThrows(IOException.class, () -> ds.open(new DataSpec(TEST_URI)));
    assertThat(closeCount.get()).isEqualTo(1);
  }

  @Test
  public void unknownLengthLiveStream_neverServesStaleCacheOnReconnect() throws Exception {
    AtomicInteger generation = new AtomicInteger(0);
    DataSource liveUpstream =
        new DataSource() {
          private int pos;
          private int currentGen;

          @Override
          public void addTransferListener(TransferListener transferListener) {}

          @Override
          public long open(DataSpec dataSpec) {
            currentGen = generation.incrementAndGet();
            pos = 0;
            return C.LENGTH_UNSET;
          }

          @Override
          public int read(byte[] buffer, int offset, int length) {
            if (pos >= 32 * 1024) {
              return C.RESULT_END_OF_INPUT;
            }
            int n = Math.min(length, 32 * 1024 - pos);
            for (int i = 0; i < n; i++) {
              buffer[offset + i] = (byte) currentGen;
            }
            pos += n;
            return n;
          }

          @Override
          public Uri getUri() {
            return TEST_URI;
          }

          @Override
          public void close() {}
        };

    CircularReadAheadDataSource ds =
        new CircularReadAheadDataSource(liveUpstream, /* bufferBytes= */ 512 * 1024);
    try {
      assertThat(ds.open(new DataSpec(TEST_URI))).isEqualTo(C.LENGTH_UNSET);
      byte[] gen1 = readAllRemaining(ds);
      assertThat(gen1[0]).isEqualTo((byte) 1);
      ds.close();

      // Reconnecting from position 0 on a live stream MUST hit upstream (gen 2), never stale gen 1.
      assertThat(ds.open(new DataSpec(TEST_URI))).isEqualTo(C.LENGTH_UNSET);
      assertThat(ds.getCacheHitOpenCount()).isEqualTo(0L);
      byte[] gen2 = readAllRemaining(ds);
      assertThat(gen2[0]).isEqualTo((byte) 2);
    } finally {
      ds.release();
    }
  }

  @Test
  public void errorInReaderThread_wakesConsumerWithoutHanging() throws Exception {
    DataSource oomUpstream =
        new DataSource() {
          @Override
          public void addTransferListener(TransferListener transferListener) {}

          @Override
          public long open(DataSpec dataSpec) {
            return 1024 * 1024L;
          }

          @Override
          public int read(byte[] buffer, int offset, int length) {
            throw new OutOfMemoryError("simulated OOM inside upstream");
          }

          @Override
          public Uri getUri() {
            return TEST_URI;
          }

          @Override
          public void close() {}
        };

    CircularReadAheadDataSource ds =
        new CircularReadAheadDataSource(oomUpstream, /* bufferBytes= */ 512 * 1024);
    try {
      ds.open(new DataSpec(TEST_URI));
      IOException thrown = assertThrows(IOException.class, () -> ds.read(new byte[64], 0, 64));
      assertThat(thrown.getCause()).isInstanceOf(OutOfMemoryError.class);
    } finally {
      ds.release();
    }
  }

  @Test
  public void repeatedZeroByteUpstreamReads_failSafelyInsteadOfSpinning() throws Exception {
    DataSource zeroReadUpstream =
        new DataSource() {
          @Override
          public void addTransferListener(TransferListener transferListener) {}

          @Override
          public long open(DataSpec dataSpec) {
            return 1024 * 1024L;
          }

          @Override
          public int read(byte[] buffer, int offset, int length) {
            return 0;
          }

          @Override
          public Uri getUri() {
            return TEST_URI;
          }

          @Override
          public void close() {}
        };

    CircularReadAheadDataSource ds =
        new CircularReadAheadDataSource(zeroReadUpstream, /* bufferBytes= */ 512 * 1024);
    try {
      ds.open(new DataSpec(TEST_URI));
      assertThrows(IOException.class, () -> ds.read(new byte[64], 0, 64));
    } finally {
      ds.release();
    }
  }

  private static byte[] createPatternBytes(int length) {
    byte[] data = new byte[length];
    for (int i = 0; i < length; i++) {
      data[i] = (byte) ((i * 31 + (i >>> 8) * 17) & 0xFF);
    }
    return data;
  }

  private static byte[] readExactly(DataSource ds, int length) throws IOException {
    byte[] out = new byte[length];
    int offset = 0;
    while (offset < length) {
      int read = ds.read(out, offset, length - offset);
      assertThat(read).isGreaterThan(0);
      offset += read;
    }
    return out;
  }

  private static byte[] readAllRemaining(DataSource ds) throws IOException {
    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int read;
    while ((read = ds.read(chunk, 0, chunk.length)) != C.RESULT_END_OF_INPUT) {
      baos.write(chunk, 0, read);
    }
    return baos.toByteArray();
  }

  private static void assertRangeEquals(byte[] expectedFull, int startOffset, byte[] actual) {
    for (int i = 0; i < actual.length; i++) {
      if (actual[i] != expectedFull[startOffset + i]) {
        assertThat(actual[i]).isEqualTo(expectedFull[startOffset + i]);
      }
    }
  }

  private static final class FakeSeekableDataSource implements DataSource {
    private final byte[] data;
    final AtomicInteger openCount = new AtomicInteger();
    final AtomicInteger closeCount = new AtomicInteger();
    volatile long failAfterBytes = Long.MAX_VALUE;
    @Nullable volatile IOException failureException;

    private boolean open;
    private int pos;
    private int end;
    private long bytesDeliveredThisOpen;
    @Nullable private Uri uri;

    FakeSeekableDataSource(byte[] data) {
      this.data = data;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {}

    @Override
    public long open(DataSpec dataSpec) throws IOException {
      openCount.incrementAndGet();
      uri = dataSpec.uri;
      if (dataSpec.position > data.length) {
        throw new IOException("Position out of range");
      }
      pos = (int) dataSpec.position;
      end =
          dataSpec.length == C.LENGTH_UNSET
              ? data.length
              : (int) Math.min(data.length, dataSpec.position + dataSpec.length);
      bytesDeliveredThisOpen = 0;
      open = true;
      return end - pos;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (bytesDeliveredThisOpen >= failAfterBytes && failureException != null) {
        throw failureException;
      }
      if (pos >= end) {
        return C.RESULT_END_OF_INPUT;
      }
      int toRead = Math.min(length, end - pos);
      System.arraycopy(data, pos, buffer, offset, toRead);
      pos += toRead;
      bytesDeliveredThisOpen += toRead;
      return toRead;
    }

    @Override
    @Nullable
    public Uri getUri() {
      return open ? uri : null;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
      return open ? Collections.singletonMap("Content-Type", Collections.singletonList("video/x-matroska")) : Collections.emptyMap();
    }

    @Override
    public void close() {
      if (open) {
        open = false;
        closeCount.incrementAndGet();
      }
    }
  }
}
