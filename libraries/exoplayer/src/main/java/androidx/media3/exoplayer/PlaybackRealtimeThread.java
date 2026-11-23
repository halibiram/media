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

import android.os.HandlerThread;
import android.os.Process;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;

/**
 * Kernel-hardened {@link HandlerThread} for ExoPlayer's real-time playback pipeline
 * ({@code ExoPlayer:Playback}, {@code ExoPlayer:AudioSink}, {@code ExoPlayer:MediaCodec*}).
 *
 * <p>On startup, this thread:
 *
 * <ol>
 *   <li>Elevates Linux nice to {@code -19} / {@code -18} directly via {@code setpriority} without
 *       raising Java {@link Thread#getPriority()}, preventing child threads spawned from the
 *       playback loop (such as {@code ExoPlayer:Loader:*}) from inheriting {@code MAX_PRIORITY}
 *       ({@code nice -8}).
 *   <li>Sets {@code PR_SET_TIMERSLACK = 1 ns} via native syscall to eliminate Linux hrtimer
 *       coalescing delays on {@link android.os.Looper} wakeups.
 * </ol>
 */
@UnstableApi
public final class PlaybackRealtimeThread extends HandlerThread {

  private static final String TAG = "PlaybackRealtimeThread";

  /** Recommended Linux nice value for the primary {@code ExoPlayer:Playback} loop (-19). */
  public static final int PRIORITY_PLAYBACK_LOOP = Process.THREAD_PRIORITY_URGENT_AUDIO;

  /** Recommended Linux nice value for companion media threads (AudioSink, Codec workers: -18). */
  public static final int PRIORITY_COMPANION_MEDIA = Process.THREAD_PRIORITY_URGENT_AUDIO + 1;

  private final int desiredNice;
  private volatile int linuxTid = -1;
  private volatile long hardeningTelemetry;

  public PlaybackRealtimeThread(String name) {
    this(name, PRIORITY_PLAYBACK_LOOP);
  }

  public PlaybackRealtimeThread(String name, int desiredNice) {
    super(name, desiredNice);
    this.desiredNice = desiredNice;
    // Intentionally keep Java Thread priority at default NORM_PRIORITY: nativeHardenCurrentThread
    // elevates this thread directly to Linux nice -19/-18 in onLooperPrepared(), whereas calling
    // setPriority(MAX_PRIORITY) would leak Java priority 10 (nice -8) to child threads spawned
    // from ExoPlayer:Playback (notably ExoPlayer:Loader:ProgressiveMediaPeriod).
  }

  @Override
  protected void onLooperPrepared() {
    long telemetry = PlaybackThreadNative.hardenCurrentThread(desiredNice);
    hardeningTelemetry = telemetry;
    int tid = Process.myTid();
    linuxTid = tid;
    Log.i(
        TAG,
        "Hardened "
            + getName()
            + " tid="
            + tid
            + " nice="
            + PlaybackThreadNative.unpackAchievedNice(telemetry)
            + " timerSlack1ns="
            + PlaybackThreadNative.unpackTimerSlackSet(telemetry));
  }

  /** Returns the Linux thread ID once started, or {@code -1} if not yet prepared. */
  public int getLinuxTid() {
    return linuxTid;
  }

  /** Returns the packed native hardening telemetry from {@link PlaybackThreadNative}. */
  public long getHardeningTelemetry() {
    return hardeningTelemetry;
  }
}
