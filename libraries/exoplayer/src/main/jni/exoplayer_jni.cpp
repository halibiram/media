#include <jni.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>

namespace {

jclass gAllocationClass = nullptr;
jmethodID gAllocationConstructor = nullptr;

void *allocateZeroedMemory(jint size) {
  if (size <= 0) {
    return nullptr;
  }
  // Use calloc instead of posix_memalign. Scudo (the Android allocator) only
  // guarantees 16-byte alignment on its Primary allocator and routes every
  // request with a larger alignment to the Secondary allocator, which performs
  // one mmap (+ guard page) per allocation. Since each 64 KB sample chunk was
  // allocated with posix_memalign(64, ...), a large buffer target produced tens
  // of thousands of tiny mappings, exhausted vm.max_map_count (65535) and Scudo
  // aborted the process with SIGABRT ("internal map failure (Out of memory)").
  // calloc stays on Scudo Primary size-class regions (shared mappings), and
  // its 16-byte alignment is sufficient for NewDirectByteBuffer and the
  // memcpy/NEON based sample pipeline.
  return std::calloc(1, static_cast<size_t>(size));
}

// 1. DefaultAllocatorNative methods
jobject createAllocation(JNIEnv *env, jclass clazz, jint size) {
  void *memory = allocateZeroedMemory(size);
  if (memory == nullptr) {
    return nullptr;
  }

  jobject buffer = env->NewDirectByteBuffer(memory, size);
  if (buffer == nullptr) {
    free(memory);
    return nullptr;
  }

  if (gAllocationClass == nullptr || gAllocationConstructor == nullptr) {
    free(memory);
    return nullptr;
  }

  jobject allocation =
      env->NewObject(gAllocationClass, gAllocationConstructor, buffer, 0, reinterpret_cast<jlong>(memory));
  if (allocation == nullptr) {
    free(memory);
  }
  return allocation;
}

// Declared @FastNative on the Java side. FastNative keeps the standard JNI ABI
// (JNIEnv*, jclass, args...), so the call is correct even on runtimes/devices
// that do not honor the optimization annotation. The previous @CriticalNative
// variant omitted these parameters, which corrupted the argument layout (and
// freed a garbage pointer) on any device where CriticalNative was not applied.
void freeAllocation(JNIEnv *env, jclass clazz, jlong handle) {
  (void)env;
  (void)clazz;
  if (handle != 0) {
    free(reinterpret_cast<void *>(handle));
  }
}

// 2. SampleDataQueueNative methods
jboolean copyFromArray(JNIEnv *env, jclass clazz, jbyteArray source, jint sourceOffset,
                       jobject target, jint targetOffset, jint length) {
  auto *targetAddress = static_cast<uint8_t *>(env->GetDirectBufferAddress(target));
  if (targetAddress == nullptr || source == nullptr) {
    return JNI_FALSE;
  }

  env->GetByteArrayRegion(
      source, sourceOffset, length,
      reinterpret_cast<jbyte *>(targetAddress + targetOffset));
  return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
}

jboolean copyToArray(JNIEnv *env, jclass clazz, jobject source, jint sourceOffset,
                     jbyteArray target, jint targetOffset, jint length) {
  auto *sourceAddress = static_cast<uint8_t *>(env->GetDirectBufferAddress(source));
  if (sourceAddress == nullptr || target == nullptr) {
    return JNI_FALSE;
  }

  env->SetByteArrayRegion(
      target, targetOffset, length,
      reinterpret_cast<jbyte *>(sourceAddress + sourceOffset));
  return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
}

jboolean copyBetweenDirectBuffers(JNIEnv *env, jclass clazz, jobject source, jint sourceOffset,
                                  jobject target, jint targetOffset, jint length) {
  auto *sourceAddress = static_cast<uint8_t *>(env->GetDirectBufferAddress(source));
  auto *targetAddress = static_cast<uint8_t *>(env->GetDirectBufferAddress(target));
  if (sourceAddress == nullptr || targetAddress == nullptr) {
    return JNI_FALSE;
  }

  std::memcpy(targetAddress + targetOffset, sourceAddress + sourceOffset,
              static_cast<size_t>(length));
  return JNI_TRUE;
}

// Declared @FastNative on the Java side (standard JNI ABI). See freeAllocation.
void nativeCopyAddresses(JNIEnv *env, jclass clazz, jlong sourceAddr, jint sourceOffset,
                         jlong targetAddr, jint targetOffset, jint length) {
  (void)env;
  (void)clazz;
  if (sourceAddr != 0 && targetAddr != 0 && length > 0) {
    std::memcpy(reinterpret_cast<uint8_t *>(targetAddr) + targetOffset,
                reinterpret_cast<const uint8_t *>(sourceAddr) + sourceOffset,
                static_cast<size_t>(length));
  }
}

// Returns the off-heap address of a direct ByteBuffer via the public JNI API.
// Replaces reflection on the hidden java.nio.Buffer.address field, which is
// blocked by non-SDK interface restrictions on API 28+ and silently disabled
// the zero-copy fast path on those devices.
jlong getBufferAddress(JNIEnv *env, jclass clazz, jobject buffer) {
  (void)clazz;
  if (buffer == nullptr) {
    return 0;
  }
  return reinterpret_cast<jlong>(env->GetDirectBufferAddress(buffer));
}

// Registration tables
const JNINativeMethod gAllocatorMethods[] = {
    {"nativeCreateAllocation", "(I)Landroidx/media3/exoplayer/upstream/Allocation;", reinterpret_cast<void *>(createAllocation)},
    {"nativeFreeAllocation", "(J)V", reinterpret_cast<void *>(freeAllocation)}
};

const JNINativeMethod gQueueMethods[] = {
    {"nativeCopyFromArray", "([BILjava/nio/ByteBuffer;II)Z", reinterpret_cast<void *>(copyFromArray)},
    {"nativeCopyToArray", "(Ljava/nio/ByteBuffer;I[BII)Z", reinterpret_cast<void *>(copyToArray)},
    {"nativeCopyBetweenDirectBuffers", "(Ljava/nio/ByteBuffer;ILjava/nio/ByteBuffer;II)Z", reinterpret_cast<void *>(copyBetweenDirectBuffers)},
    {"nativeCopyAddresses", "(JIJII)V", reinterpret_cast<void *>(nativeCopyAddresses)},
    {"nativeGetBufferAddress", "(Ljava/nio/ByteBuffer;)J", reinterpret_cast<void *>(getBufferAddress)}
};

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  JNIEnv *env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }

  // Find Allocation class
  jclass localClass = env->FindClass("androidx/media3/exoplayer/upstream/Allocation");
  if (localClass == nullptr) {
    env->ExceptionClear();
    return JNI_ERR;
  }

  gAllocationClass = reinterpret_cast<jclass>(env->NewGlobalRef(localClass));
  if (gAllocationClass == nullptr) {
    return JNI_ERR;
  }

  gAllocationConstructor = env->GetMethodID(gAllocationClass, "<init>", "(Ljava/nio/ByteBuffer;IJ)V");
  if (gAllocationConstructor == nullptr) {
    env->ExceptionClear();
    env->DeleteGlobalRef(gAllocationClass);
    gAllocationClass = nullptr;
    return JNI_ERR;
  }

  // Register DefaultAllocatorNative methods
  jclass allocatorClass = env->FindClass("androidx/media3/exoplayer/upstream/DefaultAllocatorNative");
  if (allocatorClass == nullptr) {
    env->ExceptionClear();
    return JNI_ERR;
  }
  if (env->RegisterNatives(allocatorClass, gAllocatorMethods, sizeof(gAllocatorMethods) / sizeof(JNINativeMethod)) < 0) {
    return JNI_ERR;
  }

  // Register SampleDataQueueNative methods
  jclass queueClass = env->FindClass("androidx/media3/exoplayer/source/SampleDataQueueNative");
  if (queueClass == nullptr) {
    env->ExceptionClear();
    return JNI_ERR;
  }
  if (env->RegisterNatives(queueClass, gQueueMethods, sizeof(gQueueMethods) / sizeof(JNINativeMethod)) < 0) {
    return JNI_ERR;
  }

  return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
  JNIEnv *env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) {
    if (gAllocationClass != nullptr) {
      env->DeleteGlobalRef(gAllocationClass);
      gAllocationClass = nullptr;
    }
  }
}

} // extern "C"
