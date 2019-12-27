//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.message;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.SharedBlockingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;

/**
 * Support for writing a single WebSocket BINARY message via a {@link OutputStream}
 */
public class MessageOutputStream extends OutputStream
{
    private static final Logger LOG = Log.getLogger(MessageOutputStream.class);

    private final FrameHandler.CoreSession coreSession;
    private final ByteBufferPool bufferPool;
    private final SharedBlockingCallback blocker;
    private long frameCount;
    private long bytesSent;
    private Frame frame;
    private ByteBuffer buffer; // Kept in fill mode
    private Callback callback;
    private boolean closed;

    public MessageOutputStream(FrameHandler.CoreSession coreSession, int bufferSize, ByteBufferPool bufferPool)
    {
        this.coreSession = coreSession;
        this.bufferPool = bufferPool;
        this.blocker = new SharedBlockingCallback();
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
            try (SharedBlockingCallback.Blocker b = blocker.acquire())
            {
                coreSession.sendFrame(frame, b, false);
                b.block();
                assert buffer.remaining() == 0;
            }
            finally
            {
                BufferUtil.clearToFill(buffer);
            }

            ++frameCount;
            // Any flush after the first will be a CONTINUATION frame.
            frame = new Frame(OpCode.CONTINUATION);
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
            int space = buffer.remaining();
            while (remaining > 0)
            {
                // There may be no space available, we want
                // to handle correctly when space == 0.
                int size = Math.min(space, remaining);
                buffer.put(bytes, off, size);
                off += size;
                remaining -= size;
                space = buffer.remaining();
                if (space == 0)
                {
                    flush(false);
                    space = buffer.remaining();
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
