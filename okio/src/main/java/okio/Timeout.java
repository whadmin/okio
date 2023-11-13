/*
 * Copyright (C) 2014 Square, Inc.
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
package okio;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;

/**
 * A policy on how much time to spend on a task before giving up. When a task
 * times out, it is left in an unspecified state and should be abandoned. For
 * example, if reading from a source times out, that source should be closed and
 * the read should be retried later. If writing to a sink times out, the same
 * rules apply: close the sink and retry later.
 *
 * <h3>Timeouts and Deadlines</h3>
 * This class offers two complementary controls to define a timeout policy.
 *
 * <p><strong>Timeouts</strong> specify the maximum time to wait for a single
 * operation to complete. Timeouts are typically used to detect problems like
 * network partitions. For example, if a remote peer doesn't return <i>any</i>
 * data for ten seconds, we may assume that the peer is unavailable.
 *
 * <p><strong>Deadlines</strong> specify the maximum time to spend on a job,
 * composed of one or more operations. Use deadlines to set an upper bound on
 * the time invested on a job. For example, a battery-conscious app may limit
 * how much time it spends pre-loading content.
 */
public class Timeout {
  /**
   * An empty timeout that neither tracks nor detects timeouts. Use this when
   * timeouts aren't necessary, such as in implementations whose operations
   * do not block.
   */
  public static final Timeout NONE = new Timeout() {
    @Override public Timeout timeout(long timeout, TimeUnit unit) {
      return this;
    }

    @Override public Timeout deadlineNanoTime(long deadlineNanoTime) {
      return this;
    }

    @Override public void throwIfReached() throws IOException {
    }
  };

  /**
   * 是否设置了截止时间
   */
  private boolean hasDeadline;

  /**
   * 截止时间(具体时间)
   */
  private long deadlineNanoTime;

  /**
   * 超时时间(时间间隔),如果设置为0则无限期运行
   */
  private long timeoutNanos;

  /**
   * 初始化
   */
  public Timeout() {
  }

  /**
   * 设置超时时间
   */
  public Timeout timeout(long timeout, TimeUnit unit) {
    if (timeout < 0) throw new IllegalArgumentException("timeout < 0: " + timeout);
    if (unit == null) throw new IllegalArgumentException("unit == null");
    this.timeoutNanos = unit.toNanos(timeout);
    return this;
  }

  /**
   * 设置截止时间(具体时间(参数))
   */
  public Timeout deadlineNanoTime(long deadlineNanoTime) {
    this.hasDeadline = true;
    this.deadlineNanoTime = deadlineNanoTime;
    return this;
  }

  /**
   * 设置截止时间(当前时间+时间间隔(参数))
   */
  public final Timeout deadline(long duration, TimeUnit unit) {
    if (duration <= 0) throw new IllegalArgumentException("duration <= 0: " + duration);
    if (unit == null) throw new IllegalArgumentException("unit == null");
    return deadlineNanoTime(System.nanoTime() + unit.toNanos(duration));
  }

  /**
   * 清理超时时间
   */
  public Timeout clearTimeout() {
    this.timeoutNanos = 0;
    return this;
  }

  /**
   * 清理截止时间
   */
  public Timeout clearDeadline() {
    this.hasDeadline = false;
    return this;
  }

  /**
   * 返回超时时间
   */
  public long timeoutNanos() {
    return timeoutNanos;
  }

  /**
   * 返回是否设置了截止时间
   */
  public boolean hasDeadline() {
    return hasDeadline;
  }

  /**
   * 返回截止时间
   */
  public long deadlineNanoTime() {
    if (!hasDeadline) throw new IllegalStateException("No deadline");
    return deadlineNanoTime;
  }

  /**
   * 如果截止日期已到或当前线程已中断，则抛出InterruptedIOException
   */
  public void throwIfReached() throws IOException {
    if (Thread.interrupted()) {
      Thread.currentThread().interrupt(); // Retain interrupted status.
      throw new InterruptedIOException("interrupted");
    }

    if (hasDeadline && deadlineNanoTime - System.nanoTime() <= 0) {
      throw new InterruptedIOException("deadline reached");
    }
  }

  /**
   * Waits on {@code monitor} until it is notified. Throws {@link InterruptedIOException} if either
   * the thread is interrupted or if this timeout elapses before {@code monitor} is notified. The
   * caller must be synchronized on {@code monitor}.
   *
   * <p>Here's a sample class that uses {@code waitUntilNotified()} to await a specific state. Note
   * that the call is made within a loop to avoid unnecessary waiting and to mitigate spurious
   * notifications. <pre>{@code
   *
   *   class Dice {
   *     Random random = new Random();
   *     int latestTotal;
   *
   *     public synchronized void roll() {
   *       latestTotal = 2 + random.nextInt(6) + random.nextInt(6);
   *       System.out.println("Rolled " + latestTotal);
   *       notifyAll();
   *     }
   *
   *     public void rollAtFixedRate(int period, TimeUnit timeUnit) {
   *       Executors.newScheduledThreadPool(0).scheduleAtFixedRate(new Runnable() {
   *         public void run() {
   *           roll();
   *          }
   *       }, 0, period, timeUnit);
   *     }
   *
   *     public synchronized void awaitTotal(Timeout timeout, int total)
   *         throws InterruptedIOException {
   *       while (latestTotal != total) {
   *         timeout.waitUntilNotified(this);
   *       }
   *     }
   *   }
   * }</pre>
   */
  public final void waitUntilNotified(Object monitor) throws InterruptedIOException {
    try {
      boolean hasDeadline = hasDeadline();
      long timeoutNanos = timeoutNanos();

      // 当没有设置超时时间，当前线程进入无线期等待
      if (!hasDeadline && timeoutNanos == 0L) {
        monitor.wait(); // There is no timeout: wait forever.
        return;
      }

      // 计算等待时间
      long waitNanos;
      long start = System.nanoTime();
      if (hasDeadline && timeoutNanos != 0) {
        long deadlineNanos = deadlineNanoTime() - start;
        waitNanos = Math.min(timeoutNanos, deadlineNanos);
      } else if (hasDeadline) {
        waitNanos = deadlineNanoTime() - start;
      } else {
        waitNanos = timeoutNanos;
      }

      // 当前线程根据等待时间进入有限期等待
      long elapsedNanos = 0L;
      if (waitNanos > 0L) {
        long waitMillis = waitNanos / 1000000L;
        monitor.wait(waitMillis, (int) (waitNanos - waitMillis * 1000000L));
        elapsedNanos = System.nanoTime() - start;
      }

      //实际等待时间大于等于设置等待时间，抛出超时异常
      if (elapsedNanos >= waitNanos) {
        throw new InterruptedIOException("timeout");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // Retain interrupted status.
      throw new InterruptedIOException("interrupted");
    }
  }

  static long minTimeout(long aNanos, long bNanos) {
    if (aNanos == 0L) return bNanos;
    if (bNanos == 0L) return aNanos;
    if (aNanos < bNanos) return aNanos;
    return bNanos;
  }
}
