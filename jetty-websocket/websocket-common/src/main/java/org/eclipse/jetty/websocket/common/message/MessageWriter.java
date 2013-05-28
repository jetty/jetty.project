//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import java.io.Writer;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.io.FutureWriteCallback;

/**
 * Support for writing a single WebSocket TEXT message via a {@link Writer}
 */
public class MessageWriter extends Writer
{
    private static final Logger LOG = Log.getLogger(MessageWriter.class);
    private final OutgoingFrames outgoing;
    private final ByteBufferPool bufferPool;
    private long frameCount = 0;
    private WebSocketFrame frame;
    private ByteBuffer buffer;
    private Utf8ByteBuffer utf;
    private FutureWriteCallback blocker;
    private boolean closed = false;

    public MessageWriter(OutgoingFrames outgoing, int bufferSize, ByteBufferPool bufferPool)
    {
        this.outgoing = outgoing;
        this.bufferPool = bufferPool;
        this.buffer = bufferPool.acquire(bufferSize,true);
        this.utf = Utf8ByteBuffer.wrap(buffer);
        BufferUtil.flipToFill(buffer);
        this.frame = new WebSocketFrame(OpCode.TEXT);
    }

    public MessageWriter(WebSocketSession session)
    {
        this(session.getOutgoingHandler(),session.getPolicy().getMaxTextMessageBufferSize(),session.getBufferPool());
    }

    private void assertNotClosed() throws IOException
    {
        if (closed)
        {
            throw new IOException("Stream is closed");
        }
    }

    @Override
    public synchronized void close() throws IOException
    {
        assertNotClosed();
        LOG.debug("close()");

        // finish sending whatever in the buffer with FIN=true
        flush(true);

        // close stream
        LOG.debug("Sent Frame Count: {}",frameCount);
        closed = true;
        bufferPool.release(buffer);
        LOG.debug("closed");
    }

    @Override
    public void flush() throws IOException
    {
        LOG.debug("flush()");
        assertNotClosed();

        // flush whatever is in the buffer with FIN=false
        flush(false);
        LOG.debug("flushed");
    }

    /**
     * Flush whatever is in the buffer.
     * 
     * @param fin
     *            fin flag
     * @throws IOException
     */
    private synchronized void flush(boolean fin) throws IOException
    {
        ByteBuffer data = utf.getBuffer();
        LOG.debug("flush({}): {}",fin,BufferUtil.toDetailString(data));
        frame.setPayload(data);
        frame.setFin(fin);

        blocker = new FutureWriteCallback();
        outgoing.outgoingFrame(frame,blocker);
        try
        {
            // block on write
            blocker.get();
            // write success
            // clear utf buffer
            utf.clear();
            frameCount++;
            frame.setOpCode(OpCode.CONTINUATION);
        }
        catch (ExecutionException e)
        {
            Throwable cause = e.getCause();
            if (cause != null)
            {
                if (cause instanceof IOException)
                {
                    throw (IOException)cause;
                }
                else
                {
                    throw new IOException(cause);
                }
            }
            throw new IOException("Failed to flush",e);
        }
        catch (InterruptedException e)
        {
            throw new IOException("Failed to flush",e);
        }
    }

    @Override
    public void write(char[] cbuf) throws IOException
    {
        LOG.debug("write(char[{}])",cbuf.length);
        this.write(cbuf,0,cbuf.length);
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException
    {
        LOG.debug("write(char[{}], {}, {})",cbuf.length,off,len);
        int left = len; // bytes left to write
        int offset = off; // offset within provided array
        while (left > 0)
        {
            LOG.debug("buffer: {}",BufferUtil.toDetailString(buffer));
            int space = utf.length();
            int size = Math.min(space,left);
            utf.append(cbuf,offset,size); // append with utf logic
            left -= size; // decrement char left
            if (left > 0)
            {
                flush(false);
            }
            offset += size; // increment offset
        }
    }

    @Override
    public void write(int c) throws IOException
    {
        assertNotClosed();

        // buffer up to limit, flush once buffer reached.
        utf.append((byte)c); // append with utf logic
        if (utf.length() <= 0)
        {
            flush(false);
        }
    }
}
