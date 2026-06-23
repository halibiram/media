package androidx.media3.exoplayer.upstream;

import androidx.annotation.Nullable;
import dalvik.annotation.optimization.FastNative;

public final class DefaultAllocatorNative {

  private static final String LIBRARY_NAME = "media3_exoplayer_jni";

  private static volatile boolean loadAttempted;
  private static volatile boolean isAvailable;



  private static final int ARENA_CHUNK_SIZE = 65536;

  // Desired arena: 512 chunks (32 MB). This ceiling is preserved on every device
  // that can allocate it, so devices that already worked are unaffected.
  private static final int ARENA_MAX_CHUNKS = 512;
  // Lower bound (4 MB) before giving up and using the per-allocation direct path.
  private static final int ARENA_MIN_CHUNKS = 64;

  private static final java.util.Queue<Allocation> arenaPool = new java.util.concurrent.ConcurrentLinkedQueue<>();
  private static volatile long arenaBaseAddress = 0L;
  private static volatile long arenaEndAddress = 0L;
  private static Allocation arenaBaseAllocation = null;
  private static volatile boolean arenaInitialized = false;

  private static synchronized void initializeArena() {
    if (arenaInitialized) {
      return;
    }
    arenaInitialized = true;
    if (!isAvailable()) {
      return;
    }
    // Try the full 32 MB arena first. Only step down if the contiguous off-heap
    // block genuinely fails to allocate (constrained / fragmented / 32-bit
    // devices). Devices that can allocate the full block keep the full arena,
    // so anything that already worked is byte-for-byte unaffected.
    int chunks = ARENA_MAX_CHUNKS;
    Allocation base = null;
    while (chunks >= ARENA_MIN_CHUNKS) {
      try {
        base = nativeCreateAllocation(chunks * ARENA_CHUNK_SIZE);
      } catch (Exception | UnsatisfiedLinkError e) {
        base = null;
      }
      if (base != null && base.buffer != null) {
        break;
      }
      base = null;
      chunks >>= 1; // Halve and retry only on failure.
    }
    if (base == null) {
      return; // Fall back to the per-allocation direct path.
    }

    arenaBaseAllocation = base;
    arenaBaseAddress = base.nativeHandle;
    arenaEndAddress = arenaBaseAddress + (long) chunks * ARENA_CHUNK_SIZE;

    java.nio.ByteBuffer baseBuffer = base.buffer;
    for (int i = 0; i < chunks; i++) {
      int offset = i * ARENA_CHUNK_SIZE;

      // Slice the base buffer for this chunk
      java.nio.ByteBuffer chunkBuffer = baseBuffer.duplicate();
      chunkBuffer.position(offset);
      chunkBuffer.limit(offset + ARENA_CHUNK_SIZE);
      java.nio.ByteBuffer sliced = chunkBuffer.slice();

      long chunkAddress = arenaBaseAddress + offset;
      Allocation chunkAllocation = new Allocation(sliced, 0, chunkAddress);
      arenaPool.offer(chunkAllocation);
    }
  }

  @Nullable
  private static Allocation createAllocationDirect(int size) {
    if (!isAvailable()) {
      return null;
    }
    try {
      return nativeCreateAllocation(size);
    } catch (UnsatisfiedLinkError e) {
      isAvailable = false;
      return null;
    }
  }

  @Nullable
  public static Allocation createAllocation(int size) {
    if (size == ARENA_CHUNK_SIZE) {
      if (!arenaInitialized) {
        initializeArena();
      }
      if (arenaBaseAddress != 0L) {
        Allocation allocation = arenaPool.poll();
        if (allocation != null) {
          return allocation;
        }
      }
    }
    return createAllocationDirect(size);
  }

  public static void freeAllocation(Allocation allocation) {
    final long nativeHandle = allocation.nativeHandle;
    if (nativeHandle == 0) {
      return;
    }
    // Check if the allocation belongs to the pre-allocated Arena pool
    if (nativeHandle >= arenaBaseAddress && nativeHandle < arenaEndAddress) {
      allocation.nativeHandle = 0; // Clear handle immediately
      // Offer a fresh wrapper back to the pool to prevent reference leaks
      Allocation recAllocation = new Allocation(allocation.buffer, allocation.offset, nativeHandle);
      arenaPool.offer(recAllocation);
      return;
    }

    allocation.nativeHandle = 0; // Clear immediately
    try {
      nativeFreeAllocation(nativeHandle);
    } catch (UnsatisfiedLinkError e) {
      isAvailable = false;
    }
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

  @FastNative
  private static native Allocation nativeCreateAllocation(int size);

  // @FastNative (not @CriticalNative): FastNative uses the standard JNI ABI, so
  // it stays correct on devices/runtimes that do not honor the annotation, while
  // CriticalNative changes the native calling convention and corrupted the
  // handle argument (freeing a garbage pointer) wherever it was not applied.
  @FastNative
  private static native void nativeFreeAllocation(long handle);

  private DefaultAllocatorNative() {}
}
