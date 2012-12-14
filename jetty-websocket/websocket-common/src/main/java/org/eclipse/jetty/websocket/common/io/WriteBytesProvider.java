//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.common.io;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Objects;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.Generator;

/**
 * Interface for working with bytes destined for {@link EndPoint#write(Callback, ByteBuffer...)}
 */
public class WriteBytesProvider implements Callback
{
    private class FrameEntry
    {
        private final Logger LOG = Log.getLogger(FrameEntry.class);
        protected final Frame frame;
        protected final Callback callback;

        public FrameEntry(Frame frame, Callback callback)
        {
            this.frame = frame;
            this.callback = callback;
        }

        public ByteBuffer getByteBuffer()
        {
            ByteBuffer buffer = generator.generate(bufferSize,frame);
            if (LOG.isDebugEnabled())
            {
                LOG.debug("getByteBuffer() - {}",BufferUtil.toDetailString(buffer));
            }
            return buffer;
        }
    }

    /** The websocket generator */
    private final Generator generator;
    /** Flush callback, for notifying when a flush should be performed */
    private final Callback flushCallback;
    /** Backlog of frames */
    private LinkedList<FrameEntry> queue;
    /** the buffer input size */
    private int bufferSize = 2048;
    /** Currently active frame */
    private FrameEntry active;
    /** Failure state for the entire WriteBytesProvider */
    private Throwable failure;
    /** The last requested buffer */
    private ByteBuffer buffer;

    /**
     * Create a WriteBytesProvider with specified Generator and "flush" Callback.
     * 
     * @param generator
     *            the generator to use for converting {@link Frame} objects to network {@link ByteBuffer}s
     * @param flushCallback
     *            the flush callback to call, on a write event, after the write event has been processed by this {@link WriteBytesProvider}.
     *            <p>
     *            Used to trigger another flush of the next set of bytes.
     */
    public WriteBytesProvider(Generator generator, Callback flushCallback)
    {
        this.generator = Objects.requireNonNull(generator);
        this.flushCallback = Objects.requireNonNull(flushCallback);
        this.queue = new LinkedList<>();
    }

    /**
     * Append a Frame &amp; Callback to the pending queue.
     * 
     * @param frame
     *            the frame to add
     * @param callback
     *            the optional callback for the frame write (can be null)
     */
    public void append(Frame frame, Callback callback)
    {
        Objects.requireNonNull(frame);
        synchronized (this)
        {
            if (isFailed())
            {
                // no changes when failed
                notifyFailure(callback);
                return;
            }
            queue.addLast(new FrameEntry(frame,callback));
        }
    }

    public void failAll(Throwable t)
    {
        synchronized (this)
        {
            if (isFailed())
            {
                // already failed.
                return;
            }

            failure = t;

            for (FrameEntry fe : queue)
            {
                notifyFailure(fe.callback);
            }

            queue.clear();

            // notify flush callback
            flushCallback.failed(failure);
        }
    }

    /**
     * Write of ByteBuffer failed.
     * 
     * @param cause
     *            the cause of the failure
     */
    @Override
    public void failed(Throwable cause)
    {
        failAll(cause);
    }

    public int getBufferSize()
    {
        return bufferSize;
    }

    /**
     * Get the next ByteBuffer to write.
     * 
     * @return the next ByteBuffer (or null if nothing to write)
     */
    public ByteBuffer getByteBuffer()
    {
        synchronized (this)
        {
            if (active == null)
            {
                if (queue.isEmpty())
                {
                    // nothing in queue
                    return null;
                }
                // get current topmost entry
                active = queue.pop();
            }

            if (active == null)
            {
                // no active frame available, even in queue.
                return null;
            }

            buffer = active.getByteBuffer();
        }
        return buffer;
    }

    public Throwable getFailure()
    {
        return failure;
    }

    public boolean isFailed()
    {
        return (failure != null);
    }

    /**
     * Notify specific callback of failure.
     * 
     * @param callback
     *            the callback to notify
     */
    private void notifyFailure(Callback callback)
    {
        if (callback == null)
        {
            return;
        }
        callback.failed(failure);
    }

    /**
     * Prepend a Frame &amp; Callback to the pending queue.
     * 
     * @param frame
     *            the frame to add
     * @param callback
     *            the optional callback for the frame write (can be null)
     */
    public void prepend(Frame frame, Callback callback)
    {
        Objects.requireNonNull(frame);
        synchronized (this)
        {
            if (isFailed())
            {
                // no changes when failed
                notifyFailure(callback);
                return;
            }

            queue.addFirst(new FrameEntry(frame,callback));
        }
    }

    /**
     * Set the buffer size used for generating ByteBuffers from the frames.
     * <p>
     * Value usually obtained from {@link AbstractConnection#getInputBufferSize()}
     * 
     * @param bufferSize
     *            the buffer size to use
     */
    public void setBufferSize(int bufferSize)
    {
        this.bufferSize = bufferSize;
    }

    /**
     * Write of ByteBuffer succeeded.
     */
    @Override
    public void succeeded()
    {
        synchronized (this)
        {
            // Release the active byte buffer first
            generator.getBufferPool().release(buffer);

            if (active == null)
            {
                return;
            }

            if (active.frame.remaining() <= 0)
            {
                // All done with active FrameEntry
                active = null;
            }

            // notify flush callback
            flushCallback.succeeded();
        }
    }
}
