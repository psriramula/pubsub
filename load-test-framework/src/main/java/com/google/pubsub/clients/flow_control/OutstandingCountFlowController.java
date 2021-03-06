/*
 * Copyright 2019 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.google.pubsub.clients.flow_control;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.Monitor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A FlowController that tries to ensure that the outstanding count is roughly equivalent to the
 * completion rate in the next two seconds.
 */
public class OutstandingCountFlowController implements FlowController {
  private static final class None {
    static final None NONE = new None();

    private None() {}
  }

  private int numAllowedPermits() {
    return (int) ((ratePerSecond * 2) - outstanding);
  }

  private int outstanding = 0;
  private double ratePerSecond;
  private final Monitor monitor = new Monitor();
  private final Monitor.Guard requestAllowed =
      new Monitor.Guard(monitor) {
        // Allow if there are less than 2 seconds of throughput outstanding.
        @Override
        public boolean isSatisfied() {
          return numAllowedPermits() > 0;
        }
      };
  // ExpiryCache is a cache of counter value to None, used only for the expiration parameter to get
  // the average
  // throughput.
  private final Cache<Integer, None> expiryCache;
  private final AtomicInteger entryIncrementer = new AtomicInteger(0);
  // UpdateExecutor updates the rate at predictable intervals
  private static final int expiryLatencySeconds = 15;
  private static final int rateUpdateDelayMilliseconds = 100;
  private final ScheduledExecutorService updateExecutor =
      Executors.newSingleThreadScheduledExecutor();

  public OutstandingCountFlowController(double startingPerSecond) {
    this.ratePerSecond = startingPerSecond;
    this.expiryCache =
        CacheBuilder.newBuilder().expireAfterWrite(expiryLatencySeconds, TimeUnit.SECONDS).build();
    updateExecutor.scheduleAtFixedRate(
        this::updateRate,
        expiryLatencySeconds * 1000,
        rateUpdateDelayMilliseconds,
        TimeUnit.MILLISECONDS);
  }

  private void updateRate() {
    expiryCache.cleanUp();
    monitor.enter();
    ratePerSecond = expiryCache.size() * 1.0 / expiryLatencySeconds;
    monitor.leave();
  }

  /** Request starting a flow controlled action. Return the number of allowed actions. */
  public int requestStart() {
    monitor.enterWhenUninterruptibly(requestAllowed);
    int allocated = numAllowedPermits();
    outstanding += allocated;
    monitor.leave();
    return allocated;
  }

  /** Inform the FlowController that a publish has finished. */
  public void informFinished(boolean wasSuccessful) {
    monitor.enter();
    --outstanding;
    monitor.leave();
    if (wasSuccessful) {
      int val = entryIncrementer.incrementAndGet();
      expiryCache.put(val, None.NONE);
    }
  }
}
