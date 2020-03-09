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

package org.eclipse.jetty.websocket.util.messages;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;

/**
 * Support for writing a single WebSocket BINARY message via a {@link OutputStream}
 */
public class MessageOutputStream extends OutputStream
{
    private static final Logger LOG = Log.getLogger(MessageOutputStream.class);

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
        BufferUtil.clear(buffer);
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
        synchronized (this)
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
