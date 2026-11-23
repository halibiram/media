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

import android.os.Process;
import androidx.media3.common.util.UnstableApi;

/**
 * Native Linux kernel bridge for {@link PlaybackRealtimeThread}.
 *
 * <p>Provides non-root kernel thread hardening ({@code setpriority}, {@code PR_SET_TIMERSLACK=1}).
 * Falls back cleanly on JVM / Robolectric environments where the native library is unavailable.
 */
@UnstableApi
public final class PlaybackThreadNative {

  private static final String LIBRARY_NAME = "media3_exoplayer_jni";

  private static volatile boolean loadAttempted;
  private static volatile boolean isAvailable;

  /**
   * Hardens the calling thread using Linux kernel syscalls (or Android {@link Process} fallback).
   *
   * @param desiredNice Target Linux nice value (e.g. {@link Process#THREAD_PRIORITY_URGENT_AUDIO}
   *     = -19).
   * @return Packed telemetry bitmask (decode with {@link #unpackAchievedNice} and {@link
   *     #unpackTimerSlackSet}).
   */
  public static long hardenCurrentThread(int desiredNice) {
    if (isAvailable()) {
      try {
        return nativeHardenCurrentThread(desiredNice);
      } catch (UnsatisfiedLinkError e) {
        isAvailable = false;
      }
    }
    int achievedNice = applyJavaPriorityLadder(desiredNice);
    return (long) ((achievedNice + 20) & 0xFF);
  }

  public static int unpackAchievedNice(long telemetry) {
    return (int) (telemetry & 0xFFL) - 20;
  }

  public static boolean unpackTimerSlackSet(long telemetry) {
    return ((telemetry >>> 8) & 0x1L) != 0L;
  }

  public static boolean isNativeAvailable() {
    return isAvailable();
  }

  private static int applyJavaPriorityLadder(int desiredNice) {
    int[] ladder = new int[] {desiredNice, -18, Process.THREAD_PRIORITY_AUDIO, -10};
    for (int candidate : ladder) {
      if (candidate < desiredNice) {
        continue;
      }
      try {
        Process.setThreadPriority(candidate);
        return candidate;
      } catch (IllegalArgumentException | SecurityException ignored) {
        // Try next priority level in the ladder.
      }
    }
    return 0;
  }

  private static boolean isAvailable() {
    if (loadAttempted) {
      return isAvailable;
    }
    return loadLibrary();
  }

  private static synchronized boolean loadLibrary() {
    if (loadAttempted) {
      return isAvailable;
    }
    loadAttempted = true;
    try {
      System.loadLibrary(LIBRARY_NAME);
      isAvailable = true;
    } catch (SecurityException | UnsatisfiedLinkError e) {
      isAvailable = false;
    }
    return isAvailable;
  }

  private static native long nativeHardenCurrentThread(int desiredNice);

  private PlaybackThreadNative() {}
}
