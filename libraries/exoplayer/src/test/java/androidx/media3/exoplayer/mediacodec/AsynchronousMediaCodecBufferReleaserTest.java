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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import android.media.MediaCodec;
import android.os.HandlerThread;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link AsynchronousMediaCodecBufferReleaser}. */
@RunWith(AndroidJUnit4.class)
public final class AsynchronousMediaCodecBufferReleaserTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private MediaCodec mockCodec;
  private HandlerThread releaserThread;
  private AsynchronousMediaCodecBufferReleaser releaser;

  @Before
  public void setUp() {
    releaserThread = new HandlerThread("TestReleaserThread");
    releaser = new AsynchronousMediaCodecBufferReleaser(mockCodec, releaserThread);
  }

  @After
  public void tearDown() {
    releaser.shutdown();
  }

  @Test
  public void releaseOutputBufferWithTimestamp_runsAsynchronouslyWithoutBlockingCaller()
      throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch allowReturn = new CountDownLatch(1);
    AtomicReference<String> execThread = new AtomicReference<>();

    doAnswer(
            invocation -> {
              execThread.set(Thread.currentThread().getName());
              entered.countDown();
              allowReturn.await(2, TimeUnit.SECONDS);
              return null;
            })
        .when(mockCodec)
        .releaseOutputBuffer(3, 123_456_789L);

    releaser.start();
    long startNs = System.nanoTime();
    releaser.releaseOutputBuffer(3, 123_456_789L);
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

    assertThat(elapsedMs).isLessThan(100L);
    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(execThread.get()).isEqualTo("TestReleaserThread");

    allowReturn.countDown();
    releaser.waitUntilIdle();
    verify(mockCodec).releaseOutputBuffer(3, 123_456_789L);
  }

  @Test
  public void waitUntilIdle_completesAllQueuedReleases() {
    releaser.start();
    releaser.releaseOutputBuffer(1, false);
    releaser.releaseOutputBuffer(2, 999_000L);
    releaser.waitUntilIdle();

    verify(mockCodec).releaseOutputBuffer(1, false);
    verify(mockCodec).releaseOutputBuffer(2, 999_000L);
  }

  @Test
  public void runtimeExceptionOnReleaserThread_propagatesViaMaybeThrowException() {
    IllegalStateException expected = new IllegalStateException("codec failed");
    doThrow(expected).when(mockCodec).releaseOutputBuffer(5, true);

    releaser.start();
    releaser.releaseOutputBuffer(5, true);
    releaser.waitUntilIdle();

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, releaser::maybeThrowException);
    assertThat(thrown).isSameInstanceAs(expected);
  }
}
