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
import javax.annotation.Nullable;

import static okio.Util.checkOffsetAndCount;

/**
 * AsyncTimeout 描述一个异步任务实现。
 * </p>
 * 如何使用:
 * 1 设置超时时间
 * AsyncTimeout a = new RecordingAsyncTimeout()
 * a.timeout( 250, TimeUnit.MILLISECONDS);
 *
 * 2 开启关闭超时
 * </p>
 *   boolean throwOnTimeout = false;
 *   //异步任务开始
 *   enter();
 *   try {
 *       //完成IO写入操作
 *       sink.write(source, toWrite);
 *       //正常写入，标记发生超时抛出超时异常
 *       throwOnTimeout = true;
 *   } catch (IOException e) {
 *     //异步任务异常退出
 *     throw exit(e);
 *   } finally {
 *     //异步任务正常退出
 *     exit(throwOnTimeout);
 *   }
 * </p>
 * 实现原理：
 * </p>
 * 其内部通过静态变量封装了一个单链表,链表中每一个节点都是一个 AsyncTimeout，链表中AsyncTimeout按照超时从小到大排序.
 * AsyncTimeout 内部有一个守护线程
 *
 * 调用enter，会将当前异步任务加入异步任务链表，调用exit，会将当前异步任务退出异步任务链表
 *
 */
public class AsyncTimeout extends Timeout {

  /** 如果链表 */
  private static final int TIMEOUT_WRITE_SIZE = 64 * 1024;

  /** 如果链表 */
  private static final long IDLE_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60);

  /** 如果链表 */
  private static final long IDLE_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(IDLE_TIMEOUT_MILLIS);


  /** 当前异步任务是否在链表 */
  private boolean inQueue;

  /** 异步任务在链表中截止时间 */
  private long timeoutAt;

  /** 链表中的头节点。 */
  static @Nullable AsyncTimeout head;

  /** 链表中下一个节点。 */
  private @Nullable AsyncTimeout next;


  /**
   * 异步任务开始
   */
  public final void enter() {
    if (inQueue) throw new IllegalStateException("Unbalanced enter/exit");
    //获取当前任务超时时间
    long timeoutNanos = timeoutNanos();
    //获取当前任务是否设置截止时间
    boolean hasDeadline = hasDeadline();

    //如果超时时间设置为0(任务无限期运行) 且 没有设置截止时间，直接返回
    if (timeoutNanos == 0 && !hasDeadline) {
      return;
    }
    //标记当前异步任务加入异步任务计划
    inQueue = true;
    //将异步任务加入异步任务计划
    scheduleTimeout(this, timeoutNanos, hasDeadline);
  }

  /**
   * 将异步任务加入异步任务链表
   *
   * @param node  异步任务
   * @param timeoutNanos 异步任务超时时间
   * @param hasDeadline  异步任务是否设置截止时间
   */
  private static synchronized void scheduleTimeout(
      AsyncTimeout node, long timeoutNanos, boolean hasDeadline) {
    //1 链表队列初始化，启动 Watchdog 线程
    if (head == null) {
      head = new AsyncTimeout();
      new Watchdog().start();
    }

    /** 2 计算当前异步任务在异步任务链表中截止时间 **/
    long now = System.nanoTime();
    if (timeoutNanos != 0 && hasDeadline) {
      node.timeoutAt = now + Math.min(timeoutNanos, node.deadlineNanoTime() - now);
    } else if (timeoutNanos != 0) {
      node.timeoutAt = now + timeoutNanos;
    } else if (hasDeadline) {
      node.timeoutAt = node.deadlineNanoTime();
    } else {
      throw new AssertionError();
    }

    /** 3 将当前异步任务插入异步任务链表中,按超时之前剩余的时间从小到大排序 **/
    //返回相对当前时间而言，超时之前剩余的时间
    long remainingNanos = node.remainingNanos(now);
    //从链接列表头部开始遍历链表
    for (AsyncTimeout prev = head; true; prev = prev.next) {
      //链表遍历当前节点prev下一个节点为null，或下一个节点剩余的时间比当前节点大，则将当前节点插入当前节点下一个节点位置 pre -> node -> pre.next
      if (prev.next == null || remainingNanos < prev.next.remainingNanos(now)) {
        node.next = prev.next;
        prev.next = node;
        //如果当前节点为头节点，
        if (prev == head) {
          AsyncTimeout.class.notify(); // Wake up the watchdog when inserting at the front.
        }
        //完成插入退出循环
        break;
      }
    }
  }

  /**
   * 异步任务正常退出
   *
   * @param throwOnTimeout 是否超时抛出异常
   */
  final void exit(boolean throwOnTimeout) throws IOException {
    //判断退出时是否超时
    boolean timedOut = exit();
    //如果超时且throwOnTimeout为true抛出newTimeoutException异常
    if (timedOut && throwOnTimeout) throw newTimeoutException(null);
  }

  /**
   * 异步任务异常退出
   *
   * @param cause 是否超时抛出异常
   */
  final IOException exit(IOException cause) throws IOException {
    if (!exit()) return cause;
    return newTimeoutException(cause);
  }

  /**
   * 异步任务退出
   */
  public final boolean exit() {
    //如果当前异步任务不在异步任务链表，直接返回
    if (!inQueue) return false;

    //标记当前异步任务退出异步任务链表
    inQueue = false;
    return cancelScheduledTimeout(this);
  }

  /**
   * 将异步任务退出异步任务链表
   *
   * @param node  异步任务
   */
  private static synchronized boolean cancelScheduledTimeout(AsyncTimeout node) {
    // 将异步任务退出异步任务链表，如果找到返回false，说明未超时
    for (AsyncTimeout prev = head; prev != null; prev = prev.next) {
      if (prev.next == node) {
        prev.next = node.next;
        node.next = null;
        return false;
      }
    }
    // 将异步任务退出异步任务链表，如果未找到返回true，说明已超时
    return true;
  }



  /**
   * 返回一个新的Sink，新的Sink IO写入，刷新，关闭操作都封装到一个异步超时任务内。
   */
  public final Sink sink(final Sink sink) {
    return new Sink() {
      @Override public void write(Buffer source, long byteCount) throws IOException {
        checkOffsetAndCount(source.size, 0, byteCount);

        while (byteCount > 0L) {
          // Count how many bytes to write. This loop guarantees we split on a segment boundary.
          long toWrite = 0L;
          for (Segment s = source.head; toWrite < TIMEOUT_WRITE_SIZE; s = s.next) {
            int segmentSize = s.limit - s.pos;
            toWrite += segmentSize;
            if (toWrite >= byteCount) {
              toWrite = byteCount;
              break;
            }
          }

          // Emit one write. Only this section is subject to the timeout.
          boolean throwOnTimeout = false;
          enter();
          try {
            sink.write(source, toWrite);
            byteCount -= toWrite;
            throwOnTimeout = true;
          } catch (IOException e) {
            throw exit(e);
          } finally {
            exit(throwOnTimeout);
          }
        }
      }

      @Override public void flush() throws IOException {
        boolean throwOnTimeout = false;
        enter();
        try {
          sink.flush();
          throwOnTimeout = true;
        } catch (IOException e) {
          throw exit(e);
        } finally {
          exit(throwOnTimeout);
        }
      }

      @Override public void close() throws IOException {
        boolean throwOnTimeout = false;
        enter();
        try {
          sink.close();
          throwOnTimeout = true;
        } catch (IOException e) {
          throw exit(e);
        } finally {
          exit(throwOnTimeout);
        }
      }

      @Override public Timeout timeout() {
        return AsyncTimeout.this;
      }

      @Override public String toString() {
        return "AsyncTimeout.sink(" + sink + ")";
      }
    };
  }

  /**
   * 返回一个新的Source，新的Source IO读取，刷新，关闭操作都封装到一个异步超时任务内。
   */
  public final Source source(final Source source) {
    return new Source() {
      @Override public long read(Buffer sink, long byteCount) throws IOException {
        boolean throwOnTimeout = false;
        enter();
        try {
          long result = source.read(sink, byteCount);
          throwOnTimeout = true;
          return result;
        } catch (IOException e) {
          throw exit(e);
        } finally {
          exit(throwOnTimeout);
        }
      }

      @Override public void close() throws IOException {
        boolean throwOnTimeout = false;
        enter();
        try {
          source.close();
          throwOnTimeout = true;
        } catch (IOException e) {
          throw exit(e);
        } finally {
          exit(throwOnTimeout);
        }
      }

      @Override public Timeout timeout() {
        return AsyncTimeout.this;
      }

      @Override public String toString() {
        return "AsyncTimeout.source(" + source + ")";
      }
    };
  }




  /**
   * Returns an {@link IOException} to represent a timeout. By default this method returns {@link
   * java.io.InterruptedIOException}. If {@code cause} is non-null it is set as the cause of the
   * returned exception.
   */
  protected IOException newTimeoutException(@Nullable IOException cause) {
    InterruptedIOException e = new InterruptedIOException("timeout");
    if (cause != null) {
      e.initCause(cause);
    }
    return e;
  }

  /**
   * 守护线程
   */
  private static final class Watchdog extends Thread {
    Watchdog() {
      super("Okio Watchdog");
      setDaemon(true);
    }

    @Override
    public void run() {
      while (true) {
        try {
          AsyncTimeout timedOut;
          //加AsyncTimeout类锁
          synchronized (AsyncTimeout.class) {
            //返回链表中第一个超时任务节点，从链表中退出，并返回。
            timedOut = awaitTimeout();

            //如果timedOut == null，表示链表中异步任务还未超时，进入下一次循环
            if (timedOut == null) continue;

            //如果队列为空，线程退出，等待下次scheduleTimeout
            if (timedOut == head) {
              head = null;
              return;
            }
          }

          //回调timedOut方法
          timedOut.timedOut();
        } catch (InterruptedException ignored) {
        }
      }
    }
  }

  /**
   * 返回链表中第一个超时任务节点，从链表中退出，并返回。
   * 如果链表中节点未超时返回null,链表未加入任务返回head
   */
  static @Nullable AsyncTimeout awaitTimeout() throws InterruptedException {
    //找到链表第一节点
    AsyncTimeout node = head.next;

    //如果链表第一节点为null,当前线程等待IDLE_TIMEOUT_NANOS，如果等待时间超过IDLE_TIMEOUT_NANOS返回head，否则返回null
    if (node == null) {
      long startNanos = System.nanoTime();
      AsyncTimeout.class.wait(IDLE_TIMEOUT_MILLIS);
      return head.next == null && (System.nanoTime() - startNanos) >= IDLE_TIMEOUT_NANOS
          ? head
          : null;
    }

    //返回一个节点超时剩余的时间
    long waitNanos = node.remainingNanos(System.nanoTime());

    //如果第一个节点剩余的时间大于0，还未超时当前线程等待，返回 null
    if (waitNanos > 0) {
      long waitMillis = waitNanos / 1000000L;
      waitNanos -= (waitMillis * 1000000L);
      AsyncTimeout.class.wait(waitMillis, (int) waitNanos);
      return null;
    }

    //如果第一个节点剩余的时间小于0，
    head.next = node.next;
    node.next = null;
    return node;
  }

  /**
   * 返回相对当前时间而言，超时之前剩余的时间，如果超时已经过去，并且应该立即发生超时，则这将是负的。
   */
  private long remainingNanos(long now) {
    return timeoutAt - now;
  }

  /**
   * 异步任务 添加异步任务计划,watchdog 发现异步任务超时会回调此方法
   */
  protected void timedOut() {
  }
}
