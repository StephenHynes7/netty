/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.epoll;

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.channel.epoll.AbstractEpollChannel.AbstractEpollUnsafe;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * A {@link SingleThreadEventLoop} implementation which uses <a href="http://en.wikipedia.org/wiki/Epoll">epoll</a>
 * under the covers. This {@link EventLoop} works only on Linux systems!
 */
final class EpollEventLoop extends SingleThreadEventLoop {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(EpollEventLoop.class);
    private static final AtomicIntegerFieldUpdater<EpollEventLoop> WAKEN_UP_UPDATER;

    static {
        AtomicIntegerFieldUpdater<EpollEventLoop> updater =
                PlatformDependent.newAtomicIntegerFieldUpdater(EpollEventLoop.class, "wakenUp");
        if (updater == null) {
            updater = AtomicIntegerFieldUpdater.newUpdater(EpollEventLoop.class, "wakenUp");
        }
        WAKEN_UP_UPDATER = updater;
    }

    private final int epollFd;
    private final int eventFd;
    private final IntObjectMap<AbstractEpollChannel> channels = new IntObjectHashMap<AbstractEpollChannel>(4096);
    private final boolean allowGrowing;
    private final EpollEventArray events;

    @SuppressWarnings("unused")
    private volatile int wakenUp;
    private volatile int ioRatio = 50;

    EpollEventLoop(EventLoopGroup parent, Executor executor, int maxEvents) {
        super(parent, executor, false);
        if (maxEvents == 0) {
            allowGrowing = true;
            events = new EpollEventArray(4096);
        } else {
            allowGrowing = false;
            events = new EpollEventArray(maxEvents);
        }
        boolean success = false;
        int epollFd = -1;
        int eventFd = -1;
        try {
            this.epollFd = epollFd = Native.epollCreate();
            this.eventFd = eventFd = Native.eventFd();
            Native.epollCtlAdd(epollFd, eventFd, Native.EPOLLIN);
            success = true;
        } finally {
            if (!success) {
                if (epollFd != -1) {
                    try {
                        Native.close(epollFd);
                    } catch (Exception e) {
                        // ignore
                    }
                }
                if (eventFd != -1) {
                    try {
                        Native.close(eventFd);
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop && WAKEN_UP_UPDATER.compareAndSet(this, 0, 1)) {
            // write to the evfd which will then wake-up epoll_wait(...)
            Native.eventFdWrite(eventFd, 1L);
        }
    }

    /**
     * Register the given epoll with this {@link io.netty.channel.EventLoop}.
     */
    void add(AbstractEpollChannel ch) {
        assert inEventLoop();
        int fd = ch.fd().intValue();
        Native.epollCtlAdd(epollFd, fd, ch.flags);
        channels.put(fd, ch);
    }

    /**
     * The flags of the given epoll was modified so update the registration
     */
    void modify(AbstractEpollChannel ch) {
        assert inEventLoop();
        Native.epollCtlMod(epollFd, ch.fd().intValue(), ch.flags);
    }

    /**
     * Deregister the given epoll from this {@link io.netty.channel.EventLoop}.
     */
    void remove(AbstractEpollChannel ch) {
        assert inEventLoop();

        if (ch.isOpen()) {
            int fd = ch.fd().intValue();
            if (channels.remove(fd) != null) {
                // Remove the epoll. This is only needed if it's still open as otherwise it will be automatically
                // removed once the file-descriptor is closed.
                Native.epollCtlDel(epollFd, ch.fd().intValue());
            }
        }
    }

    @Override
    protected Queue<Runnable> newTaskQueue() {
        // This event loop never calls takeTask()
        return PlatformDependent.newMpscQueue();
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop.  The default value is
     * {@code 50}, which means the event loop will try to spend the same amount of time for I/O as for non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    private int epollWait(boolean oldWakenUp) throws IOException {
        int selectCnt = 0;
        long currentTimeNanos = System.nanoTime();
        long selectDeadLineNanos = currentTimeNanos + delayNanos(currentTimeNanos);
        for (;;) {
            long timeoutMillis = (selectDeadLineNanos - currentTimeNanos + 500000L) / 1000000L;
            if (timeoutMillis <= 0) {
                if (selectCnt == 0) {
                    int ready = Native.epollWait(epollFd, events, 0);
                    if (ready > 0) {
                        return ready;
                    }
                }
                break;
            }

            int selectedKeys = Native.epollWait(epollFd, events, (int) timeoutMillis);
            selectCnt ++;

            if (selectedKeys != 0 || oldWakenUp || wakenUp == 1 || hasTasks() || hasScheduledTasks()) {
                // - Selected something,
                // - waken up by user, or
                // - the task queue has a pending task.
                // - a scheduled task is ready for processing
                return selectedKeys;
            }
            currentTimeNanos = System.nanoTime();
        }
        return 0;
    }

    @Override
    protected void run() {
        boolean oldWakenUp = WAKEN_UP_UPDATER.getAndSet(this, 0) == 1;
        try {
            int ready;
            if (hasTasks()) {
                // Non blocking just return what is ready directly without block
                ready = Native.epollWait(epollFd, events, 0);
            } else {
                ready = epollWait(oldWakenUp);

                // 'wakenUp.compareAndSet(false, true)' is always evaluated
                // before calling 'selector.wakeup()' to reduce the wake-up
                // overhead. (Selector.wakeup() is an expensive operation.)
                //
                // However, there is a race condition in this approach.
                // The race condition is triggered when 'wakenUp' is set to
                // true too early.
                //
                // 'wakenUp' is set to true too early if:
                // 1) Selector is waken up between 'wakenUp.set(false)' and
                //    'selector.select(...)'. (BAD)
                // 2) Selector is waken up between 'selector.select(...)' and
                //    'if (wakenUp.get()) { ... }'. (OK)
                //
                // In the first case, 'wakenUp' is set to true and the
                // following 'selector.select(...)' will wake up immediately.
                // Until 'wakenUp' is set to false again in the next round,
                // 'wakenUp.compareAndSet(false, true)' will fail, and therefore
                // any attempt to wake up the Selector will fail, too, causing
                // the following 'selector.select(...)' call to block
                // unnecessarily.
                //
                // To fix this problem, we wake up the selector again if wakenUp
                // is true immediately after selector.select(...).
                // It is inefficient in that it wakes up the selector for both
                // the first case (BAD - wake-up required) and the second case
                // (OK - no wake-up required).

                if (wakenUp == 1) {
                    Native.eventFdWrite(eventFd, 1L);
                }
            }

            final int ioRatio = this.ioRatio;
            if (ioRatio == 100) {
                if (ready > 0) {
                    processReady(events, ready);
                }
                runAllTasks();
            } else {
                final long ioStartTime = System.nanoTime();

                if (ready > 0) {
                    processReady(events, ready);
                }

                final long ioTime = System.nanoTime() - ioStartTime;
                runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
            }
            if (allowGrowing && ready == events.length()) {
                //increase the size of the array as we needed the whole space for the events
                events.increase();
            }
            if (isShuttingDown()) {
                closeAll();
                if (confirmShutdown()) {
                    cleanupAndTerminate(true);
                    return;
                }
            }
        } catch (Throwable t) {
            logger.warn("Unexpected exception in the selector loop.", t);

            // Prevent possible consecutive immediate failures that lead to
            // excessive CPU consumption.
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // Ignore.
            }
        }

        scheduleExecution();
    }

    private void closeAll() {
        try {
            Native.epollWait(epollFd, events, 0);
        } catch (IOException ignore) {
            // ignore on close
        }
        Collection<AbstractEpollChannel> array = new ArrayList<AbstractEpollChannel>(channels.size());

        for (IntObjectMap.Entry<AbstractEpollChannel> entry: channels.entries()) {
            array.add(entry.value());
        }

        for (AbstractEpollChannel ch: array) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    private void processReady(EpollEventArray events, int ready) {
        for (int i = 0; i < ready; i ++) {
            final int fd = events.fd(i);
            if (fd == eventFd) {
                // consume wakeup event
                Native.eventFdRead(eventFd);
            } else {
                final long ev = events.events(i);

                AbstractEpollChannel ch = channels.get(fd);
                if (ch != null && ch.isOpen()) {
                    boolean close = (ev & Native.EPOLLRDHUP) != 0;
                    boolean read = (ev & Native.EPOLLIN) != 0;
                    boolean write = (ev & Native.EPOLLOUT) != 0;

                    AbstractEpollUnsafe unsafe = (AbstractEpollUnsafe) ch.unsafe();
                    if (write) {
                        // force flush of data as the epoll is writable again
                        unsafe.epollOutReady();
                    }
                    if (read) {
                        // Something is ready to read, so consume it now
                        unsafe.epollInReady();
                    }
                    if (close) {
                        unsafe.epollRdHupReady();
                    }
                } else {
                    // We received an event for an fd which we not use anymore. Remove it from the epoll_event set.
                    Native.epollCtlDel(epollFd, fd);
                }
            }
        }
    }

    @Override
    protected void cleanup() {
        try {
            try {
                Native.close(epollFd);
            } catch (IOException e) {
                logger.warn("Failed to close the epoll fd.", e);
            }
            try {
                Native.close(eventFd);
            } catch (IOException e) {
                logger.warn("Failed to close the event fd.", e);
            }
        } finally {
            // release native memory
            events.free();
        }
    }
}
