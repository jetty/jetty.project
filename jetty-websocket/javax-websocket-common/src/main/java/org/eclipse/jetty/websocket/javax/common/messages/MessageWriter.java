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

package org.eclipse.jetty.websocket.javax.common.messages;

import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.SharedBlockingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Support for writing a single WebSocket TEXT message via a {@link Writer}
 * <p>
 * Note: Per WebSocket spec, all WebSocket TEXT messages must be encoded in UTF-8
 */
public class MessageWriter extends Writer
{
    private static final Logger LOG = Log.getLogger(MessageWriter.class);

    private final CharsetEncoder utf8Encoder = UTF_8.newEncoder()
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .onMalformedInput(CodingErrorAction.REPORT);

    private final FrameHandler.CoreSession coreSession;
    private final SharedBlockingCallback blocker;
    private long frameCount;
    private Frame frame;
    private CharBuffer buffer;
    private Callback callback;
    private boolean closed;

    public MessageWriter(FrameHandler.CoreSession coreSession, int bufferSize)
    {
        this.coreSession = coreSession;
        this.blocker = new SharedBlockingCallback();
        this.buffer = CharBuffer.allocate(bufferSize);
        this.frame = new Frame(OpCode.TEXT);
    }

    @Override
    public void write(char[] chars, int off, int len) throws IOException
    {
        try
        {
            send(chars, off, len);
        }
        catch (Throwable x)
        {
            // Notify without holding locks.
            notifyFailure(x);
            throw x;
        }
    }

    @Override
    public void write(int c) throws IOException
    {
        try
        {
            send(new char[]{(char)c}, 0, 1);
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

            buffer.flip();
            ByteBuffer payload = utf8Encoder.encode(buffer);
            buffer.flip();

            if (LOG.isDebugEnabled())
                LOG.debug("flush({}): {}", fin, BufferUtil.toDetailString(payload));
            frame.setPayload(payload);
            frame.setFin(fin);

            try (SharedBlockingCallback.Blocker b = blocker.acquire())
            {
                coreSession.sendFrame(frame, b, false);
                b.block();
            }

            ++frameCount;
            // Any flush after the first will be a CONTINUATION frame.
            frame = new Frame(OpCode.CONTINUATION);
        }
    }

    private void send(char[] chars, int offset, int length) throws IOException
    {
        synchronized (this)
        {
            if (closed)
                throw new IOException("Stream is closed");

            CharBuffer source = CharBuffer.wrap(chars, offset, length);

            int remaining = length;

            while (remaining > 0)
            {
                int read = source.read(buffer);
                if (read == -1)
                {
                    return;
                }

                remaining -= read;

                if (remaining > 0)
                {
                    // If we could not write everything, it means
                    // that the buffer was full, so flush it.
                    flush(false);
                }
            }
        }
    }

    @Override
    public void close() throws IOException
    {
        try
        {
            flush(true);
            if (LOG.isDebugEnabled())
                LOG.debug("Stream closed, {} frames sent", frameCount);
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
