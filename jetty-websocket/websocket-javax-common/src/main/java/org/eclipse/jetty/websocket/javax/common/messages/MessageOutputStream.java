//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.common.messages;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Support for writing a single WebSocket BINARY message via a {@link OutputStream}
 */
public class MessageOutputStream extends OutputStream
{
    private static final Logger LOG = LoggerFactory.getLogger(MessageOutputStream.class);

    private final CoreSession coreSession;
    private final ByteBufferPool bufferPool;
    private long frameCount;
    private long bytesSent;
    private Frame frame;
    private ByteBuffer buffer;
    private Callback callback;
    private boolean closed;

    public MessageOutputStream(CoreSession coreSession, int bufferSize, ByteBufferPool bufferPool)
    {
        this.coreSession = coreSession;
        this.bufferPool = bufferPool;
        this.buffer = bufferPool.acquire(bufferSize, true);
        BufferUtil.flipToFill(buffer);
        this.frame = new Frame(OpCode.BINARY);
    }

    @Override
    public void write(byte[] bytes, int off, int len) throws IOException
    {
        try
        {
            send(bytes, off, len);
        }
        catch (Throwable x)
        {
            // Notify without holding locks.
            notifyFailure(x);
            throw x;
        }
    }

    @Override
    public void write(int b) throws IOException
    {
        try
        {
            send(new byte[]{(byte)b}, 0, 1);
        }
        catch (Throwable x)
        {
            // Notify without holding locks.
            notifyFailure(x);
            throw x;
        }
    }

    @Override
    public void flush() throws IOException
    {
        try
        {
            flush(false);
        }
        catch (Throwable x)
        {
            // Notify without holding locks.
            notifyFailure(x);
            throw x;
        }
    }

    private void flush(boolean fin) throws IOException
    {
        synchronized (this)
        {
            if (closed)
                throw new IOException("Stream is closed");

            closed = fin;

            BufferUtil.flipToFlush(buffer, 0);
            frame.setPayload(buffer);
            frame.setFin(fin);

            int initialBufferSize = buffer.remaining();
            FutureCallback b = new FutureCallback();
            coreSession.sendFrame(frame, b, false);
            b.block();

            ++frameCount;
            // Any flush after the first will be a CONTINUATION frame.
            frame = new Frame(OpCode.CONTINUATION);

            // Buffer has been sent, but buffer should not have been consumed.
            assert buffer.remaining() == initialBufferSize;

            BufferUtil.clearToFill(buffer);
        }
    }

    private void send(byte[] bytes, final int offset, final int length) throws IOException
    {
        synchronized (this)
        {
            if (closed)
                throw new IOException("Stream is closed");

            int remaining = length;
            int off = offset;
            while (remaining > 0)
            {
                // There may be no space available, we want
                // to handle correctly when space == 0.
                int space = buffer.remaining();
                int size = Math.min(space, remaining);
                buffer.put(bytes, off, size);
                off += size;
                remaining -= size;
                if (remaining > 0)
                {
                    // If we could not write everything, it means
                    // that the buffer was full, so flush it.
                    flush(false);
                }
            }
            bytesSent += length;
        }
    }

    @Override
    public void close() throws IOException
    {
        try
        {
            flush(true);
            bufferPool.release(buffer);
            if (LOG.isDebugEnabled())
                LOG.debug("Stream closed, {} frames ({} bytes) sent", frameCount, bytesSent);
            // Notify without holding locks.
            notifySuccess();
        }
        catch (Throwable x)
        {
            // Notify without holding locks.
            notifyFailure(x);
            throw x;
        }
    }

    public void setCallback(Callback callback)
    {
        synchronized (this)
        {
            this.callback = callback;
        }
    }

    private void notifySuccess()
    {
        Callback callback;
        synchronized (this)
        {
            callback = this.callback;
        }
        if (callback != null)
        {
            callback.succeeded();
        }
    }

    private void notifyFailure(Throwable failure)
    {
        Callback callback;
        synchronized (this)
        {
            callback = this.callback;
        }
        if (callback != null)
        {
            callback.failed(failure);
        }
    }
}
