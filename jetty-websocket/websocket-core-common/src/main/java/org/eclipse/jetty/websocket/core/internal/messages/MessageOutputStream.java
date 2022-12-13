//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.internal.messages;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.thread.AutoLock;
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

    private final AutoLock lock = new AutoLock();
    private final CoreSession coreSession;
    private final ByteBufferPool bufferPool;
    private final int bufferSize;
    private long frameCount;
    private long bytesSent;
    private ByteBuffer buffer;
    private Callback callback;
    private boolean closed;
    private byte messageOpCode = OpCode.BINARY;

    public MessageOutputStream(CoreSession coreSession, ByteBufferPool bufferPool)
    {
        this.coreSession = coreSession;
        this.bufferPool = bufferPool;
        this.bufferSize = coreSession.getOutputBufferSize();
        this.buffer = bufferPool.acquire(bufferSize, true);
    }

    void setMessageType(byte opcode)
    {
        this.messageOpCode = opcode;
    }

    @Override
    public void write(byte[] bytes, int off, int len) throws IOException
    {
        try
        {
            send(ByteBuffer.wrap(bytes, off, len));
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
            send(ByteBuffer.wrap(new byte[]{(byte)b}));
        }
        catch (Throwable x)
        {
            // Notify without holding locks.
            notifyFailure(x);
            throw x;
        }
    }

    public void write(ByteBuffer buffer) throws IOException
    {
        try
        {
            send(buffer);
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
        try (AutoLock l = lock.lock())
        {
            if (closed)
                throw new IOException("Stream is closed");

            closed = fin;
            Frame frame = new Frame(frameCount == 0 ? messageOpCode : OpCode.CONTINUATION);
            frame.setPayload(buffer);
            frame.setFin(fin);

            int initialBufferSize = buffer.remaining();
            FutureCallback b = new FutureCallback();
            coreSession.sendFrame(frame, b, false);
            b.block();

            // Any flush after the first will be a CONTINUATION frame.
            bytesSent += initialBufferSize;
            ++frameCount;

            // Buffer has been sent, but buffer should not have been consumed.
            try
            {
                assert buffer.remaining() == initialBufferSize;
                BufferUtil.clear(buffer);
            }
            catch (Throwable t)
            {
                t.printStackTrace();
            }
        }
    }

    private void send(ByteBuffer data) throws IOException
    {
        try (AutoLock l = lock.lock())
        {
            if (closed)
                throw new IOException("Stream is closed");

            while (data.hasRemaining())
            {
                int bufferRemainingSpace = bufferSize - buffer.remaining();
                int copied = Math.min(bufferRemainingSpace, data.remaining());
                BufferUtil.append(buffer, data.array(), data.arrayOffset() + data.position(), copied);
                data.position(data.position() + copied);

                if (data.hasRemaining())
                    flush(false);
            }
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
        try (AutoLock l = lock.lock())
        {
            this.callback = callback;
        }
    }

    private void notifySuccess()
    {
        Callback callback;
        try (AutoLock l = lock.lock())
        {
            callback = this.callback;
        }
        if (callback != null)
            callback.succeeded();
    }

    private void notifyFailure(Throwable failure)
    {
        Callback callback;
        try (AutoLock l = lock.lock())
        {
            callback = this.callback;
        }
        if (callback != null)
            callback.failed(failure);
    }
}
