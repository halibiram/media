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
package androidx.media3.exoplayer.upstream;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.NuvioEngineConfig;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DefaultAllocator}. */
@RunWith(AndroidJUnit4.class)
public final class DefaultAllocatorTest {

  private static final int ALLOCATION_SIZE = 16;

  @Before
  public void setUp() {
    NuvioEngineConfig.set(NuvioEngineConfig.stockMode());
  }

  @After
  public void tearDown() {
    NuvioEngineConfig.set(NuvioEngineConfig.stockMode());
  }

  @Test
  public void release_doesNotTrimUntilExplicitlyRequested() {
    DefaultAllocator allocator = new DefaultAllocator(/* trimOnReset= */ true, ALLOCATION_SIZE);
    Allocation allocation = allocator.allocate();

    allocator.release(allocation);

    assertThat(allocator.getTotalBytesAllocated()).isEqualTo(0);
    assertThat(allocator.getAvailableBytes()).isEqualTo(ALLOCATION_SIZE);

    allocator.trim();

    assertThat(allocator.getAvailableBytes()).isEqualTo(0);
  }

  @Test
  public void releaseChain_doesNotTrimUntilExplicitlyRequested() {
    DefaultAllocator allocator = new DefaultAllocator(/* trimOnReset= */ true, ALLOCATION_SIZE);
    Allocation first = allocator.allocate();
    Allocation second = allocator.allocate();

    allocator.release(new TestAllocationNode(first, new TestAllocationNode(second, null)));

    assertThat(allocator.getTotalBytesAllocated()).isEqualTo(0);
    assertThat(allocator.getAvailableBytes()).isEqualTo(2 * ALLOCATION_SIZE);

    allocator.trim();

    assertThat(allocator.getAvailableBytes()).isEqualTo(0);
  }

  @Test
  public void setTargetBufferSize_reductionTrimsOnlyExcessAvailableAllocations() {
    DefaultAllocator allocator = new DefaultAllocator(/* trimOnReset= */ true, ALLOCATION_SIZE);
    allocator.setTargetBufferSize(3 * ALLOCATION_SIZE);
    Allocation first = allocator.allocate();
    Allocation second = allocator.allocate();
    Allocation third = allocator.allocate();
    allocator.release(first);
    allocator.release(second);
    allocator.release(third);

    allocator.setTargetBufferSize(ALLOCATION_SIZE);

    assertThat(allocator.getAvailableBytes()).isEqualTo(ALLOCATION_SIZE);
    assertThat(allocator.getMemoryFootprint()).isEqualTo(ALLOCATION_SIZE);
  }

  @Test
  public void trim_preservesInitialAllocations() {
    DefaultAllocator allocator =
        new DefaultAllocator(
            /* trimOnReset= */ true, ALLOCATION_SIZE, /* initialAllocationCount= */ 2);
    Allocation firstInitial = allocator.allocate();
    Allocation secondInitial = allocator.allocate();
    Allocation nonInitial = allocator.allocate();
    allocator.release(firstInitial);
    allocator.release(secondInitial);
    allocator.release(nonInitial);

    allocator.trim();

    assertThat(allocator.getAvailableBytes()).isEqualTo(2 * ALLOCATION_SIZE);
    assertThat(allocator.getMemoryFootprint()).isEqualTo(2 * ALLOCATION_SIZE);
  }

  @Test
  public void trim_preservesNativeInitialAllocations() {
    DefaultAllocator allocator =
        new DefaultAllocator(
            /* trimOnReset= */ true,
            ALLOCATION_SIZE,
            /* initialAllocationCount= */ 2,
            /* forceNativeAllocation= */ true);
    Allocation firstInitial = allocator.allocate();
    Allocation secondInitial = allocator.allocate();
    Allocation nonInitial = allocator.allocate();
    allocator.release(firstInitial);
    allocator.release(secondInitial);
    allocator.release(nonInitial);

    allocator.trim();

    assertThat(allocator.getAvailableBytes()).isEqualTo(2 * ALLOCATION_SIZE);
  }

  @Test
  public void reset_trimsOnlyWhenConfigured() {
    DefaultAllocator trimmingAllocator =
        new DefaultAllocator(/* trimOnReset= */ true, ALLOCATION_SIZE);
    trimmingAllocator.setTargetBufferSize(ALLOCATION_SIZE);
    Allocation trimmingAllocation = trimmingAllocator.allocate();
    trimmingAllocator.release(trimmingAllocation);

    trimmingAllocator.reset();

    assertThat(trimmingAllocator.getAvailableBytes()).isEqualTo(0);

    DefaultAllocator retainingAllocator =
        new DefaultAllocator(/* trimOnReset= */ false, ALLOCATION_SIZE);
    retainingAllocator.setTargetBufferSize(ALLOCATION_SIZE);
    Allocation retainingAllocation = retainingAllocator.allocate();
    retainingAllocator.release(retainingAllocation);

    retainingAllocator.reset();

    assertThat(retainingAllocator.getAvailableBytes()).isEqualTo(ALLOCATION_SIZE);
  }

  private static final class TestAllocationNode implements Allocator.AllocationNode {

    private final Allocation allocation;
    private final Allocator.AllocationNode next;

    public TestAllocationNode(Allocation allocation, Allocator.AllocationNode next) {
      this.allocation = allocation;
      this.next = next;
    }

    @Override
    public Allocation getAllocation() {
      return allocation;
    }

    @Override
    public Allocator.AllocationNode next() {
      return next;
    }
  }
}
