/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.utils.queue;

import org.jitsi.utils.logging.*;
import org.json.simple.*;
import org.jetbrains.annotations.*;

import java.util.concurrent.*;

/**
 * An abstract queue of packets.
 *
 * @author Boris Grozev
 * @author Yura Yaroshevich
 */
public abstract class PacketQueue<T>
{
    /**
     * The {@link Logger} used by the {@link PacketQueue} class and its
     * instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(PacketQueue.class.getName());

    /**
     * The default capacity of a {@link PacketQueue}.
     */
    private final static int DEFAULT_CAPACITY = 256;

    /**
     * The default value for the {@code enableStatistics} constructor argument.
     */
    private static boolean enableStatisticsDefault = false;

    /**
     * Sets the default value for the {@code enableStatistics} constructor
     * parameter.
     *
     * @param enable the value to set.
     */
    public static void setEnableStatisticsDefault(boolean enable)
    {
        enableStatisticsDefault = enable;
    }

    /**
     * The underlying {@link BlockingQueue} which holds packets.
     * Used as synchronization object between {@link #close()}, {@link #get()}
     * and {@link #doAdd(Object)}.
     */
    private final BlockingQueue<T> queue;

    /**
     * Whether this {@link PacketQueue} should store the {@code byte[]} or
     * {@code T} instances added to it via one of the {@code add} methods (if
     * {@code false}), or create and store a copy (if {@code true}).
     */
    private final boolean copy;

    /**
     * The {@link QueueStatistics} instance optionally used to collect and print
     * detailed statistics about this queue.
     */
    private final QueueStatistics queueStatistics;

    /**
     * The optionally used {@link AsyncQueueHandler} to perpetually read packets
     * from {@link #queue} on separate thread and handle them with provided
     * packet handler.
     */
    private final AsyncQueueHandler asyncQueueHandler;

    /**
     * A string used to identify this {@link PacketQueue} for logging purposes.
     */
    private final String id;

    /**
     * Whether this queue has been closed. Field is denoted as volatile,
     * because it is set in one thread and could be read in while loop in other.
     */
    private volatile boolean closed = false;

    /**
     * The maximum number of items the queue can contain before it starts
     * dropping items.
     */
    private final int capacity;

    /**
     * Handles dropped packets and exceptions thrown while processing.
     */
    @NotNull
    private ErrorHandler errorHandler = new ErrorHandler(){};

    /**
     * Initializes a new {@link PacketQueue} instance.
     */
    public PacketQueue()
    {
        this(null, "PacketQueue", null);
    }

    /**
     * Initializes a new {@link PacketQueue} instance.
     * @param enableStatistics whether detailed statistics should be gathered.
     * This might affect performance. A value of {@code null} indicates that
     * the default {@link #enableStatisticsDefault} value will be used.
     * @param id the ID of the packet queue, to be used for logging.
     * @param packetHandler An optional handler to be used by the queue for
     * packets read from it. If a non-null value is passed the queue will
     * start its own thread, which will read packets from the queue and execute
     * {@code handler.handlePacket} on them. If set to null, no thread will be
     * created, and the queue will provide access to the head element via
     * {@link #get()} and {@link #poll()}.
     */
    public PacketQueue(
        Boolean enableStatistics, String id, PacketHandler<T> packetHandler)
    {
        this(DEFAULT_CAPACITY, true, enableStatistics, id, packetHandler);
    }

    /**
     * Initializes a new {@link PacketQueue} instance.
     * @param capacity the capacity of the queue.
     * @param copy whether the queue is to store the instances it is given via
     * the various {@code add} methods, or create a copy.
     * @param enableStatistics whether detailed statistics should be gathered.
     * This might affect performance. A value of {@code null} indicates that
     * the default {@link #enableStatisticsDefault} value will be used.
     * @param id the ID of the packet queue, to be used for logging.
     * @param packetHandler An optional handler to be used by the queue for
     * packets read from it. If a non-null value is passed the queue will
     * start its own thread, which will read packets from the queue and execute
     * {@code handler.handlePacket} on them. If set to null, no thread will be
     * created, and the queue will provide access to the head element via
     * {@link #get()} and {@link #poll()}.
     */
    public PacketQueue(int capacity, boolean copy,
                       Boolean enableStatistics, String id,
                       PacketHandler<T> packetHandler)
    {
        this(capacity, copy, enableStatistics, id, packetHandler, null);
    }

    /**
     * Initializes a new {@link PacketQueue} instance.
     * @param capacity the capacity of the queue.
     * @param copy whether the queue is to store the instances it is given via
     * the various {@code add} methods, or create a copy.
     * @param enableStatistics whether detailed statistics should be gathered.
     * This might affect performance. A value of {@code null} indicates that
     * the default {@link #enableStatisticsDefault} value will be used.
     * @param id the ID of the packet queue, to be used for logging.
     * @param packetHandler An optional handler to be used by the queue for
     * packets read from it. If a non-null value is passed the queue will
     * start its own thread, which will read packets from the queue and execute
     * {@code handler.handlePacket} on them. If set to null, no thread will be
     * created, and the queue will provide access to the head element via
     * {@link #get()} and {@link #poll()}.
     * @param executor An optional executor service to use to execute
     * packetHandler for items added to queue.
     */
    public PacketQueue(
        int capacity,
        boolean copy,
        Boolean enableStatistics,
        String id,
        PacketHandler<T> packetHandler,
        ExecutorService executor)
    {
        this.copy = copy;
        this.id = id;
        this.capacity = capacity;
        queue = new ArrayBlockingQueue<>(capacity);

        if (enableStatistics == null)
        {
            enableStatistics = enableStatisticsDefault;
        }
        queueStatistics
            = enableStatistics ? new QueueStatistics() : null;

        if (packetHandler != null)
        {
            asyncQueueHandler = new AsyncQueueHandler<>(
                queue,
                new HandlerAdapter(packetHandler),
                id,
                executor,
                packetHandler.maxSequentiallyProcessedPackets());
        }
        else
        {
            asyncQueueHandler = null;
        }

        logger.debug("Initialized a PacketQueue instance with ID " + id);
    }

    /**
     * Adds a packet represented by a {@code byte[]} with a corresponding
     * offset and length to this queue.
     * @param buf the {@code byte[]} to add.
     * @param off the offset into {@code byte[]} where data begins.
     * @param len the length of the data.
     */
    public void add(byte[] buf, int off, int len)
    {
        add(buf, off, len, null);
    }

    /**
     * Adds a packet represented by a {@code byte[]} with a corresponding
     * offset and length, and a context object to this queue.
     * @param buf the {@code byte[]} to add.
     * @param off the offset into {@code byte[]} where data begins.
     * @param len the length of the data.
     * @param context an object which will be added to the queue as part of the
     * packet.
     */
    public void add(byte[] buf, int off, int len, Object context)
    {
        if (copy)
        {
            byte[] newBuf = getByteArray(len);
            System.arraycopy(buf, off, newBuf, 0, len);
            doAdd(createPacket(newBuf, 0, len, context));
        }
        else
        {
            doAdd(createPacket(buf, off, len, context));
        }
    }

    /**
     * Adds a specific packet ({@code T}) instance to the queue.
     * @param pkt the packet to add.
     */
    public void add(T pkt)
    {
        if (copy)
        {
            // create a new instance
            add(getBuffer(pkt), getOffset(pkt), getLength(pkt), getContext(pkt));
        }
        else
        {
            doAdd(pkt);
        }
    }

    /**
     * Get an unused {@link byte[]} instance with length at least {@code len}.
     * @param len the minimum length of the returned instance.
     * @return a {@link byte[]} instance with length at least {@code len}.
     */
    private byte[] getByteArray(int len)
    {
        return new byte[len];
    }

    /**
     * Adds a specific packet ({@code T}) instance to the queue.
     * @param pkt the packet to add.
     */
    private void doAdd(T pkt)
    {
        if (closed)
            return;

        while (!queue.offer(pkt))
        {
            // Drop from the head of the queue.
            T p = queue.poll();
            if (p != null)
            {
                if (queueStatistics != null)
                {
                    queueStatistics.drop(System.currentTimeMillis());
                }
                errorHandler.packetDropped();

                // Call release on dropped packet to allow proper implementation
                // of object pooling by PacketQueue users
                releasePacket(p);
            }
        }

        if (queueStatistics != null)
        {
            queueStatistics.add(System.currentTimeMillis());
        }

        synchronized (queue)
        {
            // notify single thread because only 1 item was added into queue
            queue.notify();
        }

        if (asyncQueueHandler != null)
        {
            asyncQueueHandler.handleQueueItemsUntilEmpty();
        }
    }

    /**
     * Removes and returns the packet ({@code T}) at the head of this queue.
     * Blocks until there is a packet in the queue. Returns {@code null} if
     * the queue is closed or gets closed while waiting for a packet to be added.
     * @return the packet at the head of this queue.
     */
    public T get()
    {
        if (asyncQueueHandler != null)
        {
            // If the queue was configured with a handler, it is running its
            // own reading thread, and reading from it via this interface would
            // not provide consistent results.
            throw new IllegalStateException(
                "Trying to read from a queue with a configured handler.");
        }

        while (true)
        {
            if (closed)
                return null;
            synchronized (queue)
            {
                T pkt = queue.poll();
                if (pkt != null)
                {
                    if (queueStatistics != null)
                    {
                        queueStatistics.remove(System.currentTimeMillis());
                    }
                    return pkt;
                }

                try
                {
                    queue.wait();
                }
                catch (InterruptedException ie)
                {}
            }
        }
    }

    /**
     * Removes and returns the packet ({@code T}) at the head of this queue, if
     * the queue is non-empty. If the queue is closed or empty, returns null
     * without blocking.
     * @return the packet at the head of this queue, or null if the queue is
     * empty.
     */
    public T poll()
    {
        if (closed)
            return null;

        if (asyncQueueHandler != null)
        {
            // If the queue was configured with a handler, it is running its
            // own reading thread, and reading from it via this interface would
            // not provide consistent results.
            throw new IllegalStateException(
                "Trying to read from a queue with a configured handler.");
        }

        synchronized (queue)
        {
            T pkt = queue.poll();
            if (pkt != null && queueStatistics != null)
            {
                queueStatistics.remove(System.currentTimeMillis());
            }

            return pkt;
        }
    }

    /**
     * Closes current <tt>PacketQueue</tt> instance. No items will be added
     * to queue when it's closed. Threads which were blocked in {@link #get()}
     * will receive <tt>null</tt>. Asynchronous queue processing by
     * {@link #asyncQueueHandler} is stopped.
     */
    public void close()
    {
        if (!closed)
        {
            closed = true;

            if (asyncQueueHandler != null)
            {
                asyncQueueHandler.cancel();
            }

            synchronized (queue)
            {
                // notify all threads because PacketQueue is closed and all
                // threads waiting on queue must stop reading it.
                queue.notifyAll();
            }
        }
    }

    /**
     * Extracts the underlying {@code byte[]} from a packet.
     * @param pkt the packet to get the {@code byte[]} from.
     * @return the underlying {@code byte[]} of {@code pkt}.
     */
    public abstract byte[] getBuffer(T pkt);

    /**
     * Extracts the offset of a packet.
     * @param pkt the packet to get the offset of.
     * @return the offset of {@code pkt}.
     */
    public abstract int getOffset(T pkt);

    /**
     * Extracts the length of a packet.
     * @param pkt the packet to get the length of.
     * @return the length of {@code pkt}.
     */
    public abstract int getLength(T pkt);

    /**
     * Extracts the context of a packet.
     * @param pkt the packet to get the context of.
     * @return the context of {@code pkt}.
     */
    public abstract Object getContext(T pkt);

    /**
     * Creates a new packet ({@link T} instance) with the given {@code byte[]},
     * offset, length and context.
     * @param buf the {@code byte[]} of the new instance.
     * @param off the offset of the new instance.
     * @param len the length of the new instance.
     * @param context the context of the new instance.
     * @return a new packet ({@link T} instance).
     */
    protected abstract T createPacket(
        byte[] buf, int off, int len, Object context);

    /**
     * Releases packet when it is handled by provided packet handler.
     * This method is not called when <tt>PacketQueue</tt> was created without
     * handler and hence no automatic queue processing is done.
     * Default implementation is empty, but it might be used to implement
     * packet pooling to re-use them.
     * @param pkt packet to release
     */
    protected void releasePacket(T pkt)
    {
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    public JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put("id", id);
        debugState.put("capacity", capacity);
        debugState.put("copy", copy);
        debugState.put("closed", closed);
        debugState.put(
                "statistics",
                queueStatistics == null
                        ? null : queueStatistics.getStats());

        return debugState;
    }

    /**
     * Sets the handler of errors (packets dropped or exceptions caught while
     * processing).
     * @param errorHandler the handler to set.
     */
    public void setErrorHandler(@NotNull ErrorHandler errorHandler)
    {
        this.errorHandler = errorHandler;
    }

    /**
     * A simple interface to handle packets.
     * @param <T> the type of the packets.
     */
    public interface PacketHandler<T>
    {
        /**
         * Does something with a packet.
         * @param pkt the packet to do something with.
         * @return {@code true} if the operation was successful, and
         * {@code false} otherwise.
         */
        boolean handlePacket(T pkt);

        /**
         * Specifies the number of packets allowed to be processed sequentially
         * without yielding control to executor's thread. Specifying positive
         * number will allow other possible queues sharing same
         * {@link ExecutorService} to process their packets.
         * @return positive value to specify max number of packets which allows
         * implementation of cooperative multi-tasking between different
         * {@link PacketQueue} sharing same {@link ExecutorService}.
         */
        default long maxSequentiallyProcessedPackets()
        {
            return -1;
        }
    }

    /**
     * An adapter class implementing {@link AsyncQueueHandler.Handler<T>}
     * to wrap {@link PacketHandler<T>}.
     */
    private final class HandlerAdapter implements AsyncQueueHandler.Handler<T>
    {
        /**
         * An actual handler of packets.
         */
        private final PacketHandler<T> handler;

        /**
         * Constructs adapter of {@link PacketHandler<T>} to
         * {@link AsyncQueueHandler.Handler<T>} interface.
         * @param handler an handler instance to adapt
         */
        HandlerAdapter(PacketHandler<T> handler)
        {
            this.handler = handler;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void handleItem(T item)
        {
            if (queueStatistics != null)
            {
                queueStatistics.remove(System.currentTimeMillis());
            }

            try
            {
                handler.handlePacket(item);
            }
            catch (Throwable t)
            {
                errorHandler.packetHandlingFailed(t);
            }
            finally
            {
                releasePacket(item);
            }
        }
    }
}
