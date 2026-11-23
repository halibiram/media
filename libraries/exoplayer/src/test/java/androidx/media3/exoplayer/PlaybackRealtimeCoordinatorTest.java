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
package androidx.media3.exoplayer;

import static com.google.common.truth.Truth.assertThat;

import android.os.Handler;
import androidx.media3.common.NuvioEngineConfig;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link PlaybackRealtimeThread} and {@link PlaybackThreadNative}. */
@RunWith(AndroidJUnit4.class)
public final class PlaybackRealtimeCoordinatorTest {

  @Test
  public void playbackRealtimeThread_startsAndHardensCleanly() throws Exception {
    CountDownLatch startedLatch = new CountDownLatch(1);
    PlaybackRealtimeThread thread = new PlaybackRealtimeThread("ExoPlayer:TestRT");
    assertThat(thread.getPriority()).isEqualTo(Thread.NORM_PRIORITY);
    try {
      thread.start();
      assertThat(thread.getLooper()).isNotNull();
      new Handler(thread.getLooper()).post(startedLatch::countDown);
      assertThat(startedLatch.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(thread.getLinuxTid()).isGreaterThan(0);
    } finally {
      thread.quit();
      thread.join(2_000);
    }
  }

  @Test
  public void telemetryPackingAndConfigFlags_behaveAsExpected() {
    long packed = (1L) | (1L << 8); // nice=-19, slack=1
    assertThat(PlaybackThreadNative.unpackAchievedNice(packed)).isEqualTo(-19);
    assertThat(PlaybackThreadNative.unpackTimerSlackSet(packed)).isTrue();

    assertThat(NuvioEngineConfig.stockMode().isRealtimePlaybackThreadEnabled()).isFalse();
    assertThat(NuvioEngineConfig.nuvioMode().isRealtimePlaybackThreadEnabled()).isTrue();
    assertThat(NuvioEngineConfig.stockWithDecoupledAvSync().isRealtimePlaybackThreadEnabled())
        .isTrue();
  }
}
