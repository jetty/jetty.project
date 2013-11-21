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

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.BlockingWriteCallback;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.frames.TextFrame;

/**
 * Support for writing a single WebSocket TEXT message via a {@link Writer}
 * <p>
 * Note: Per WebSocket spec, all WebSocket TEXT messages must be encoded in UTF-8
 */
public class MessageWriter extends Writer
{
    private static final Logger LOG = Log.getLogger(MessageWriter.class);
    private final OutgoingFrames outgoing;
    private final ByteBufferPool bufferPool;
    private final BlockingWriteCallback blocker;
    private long frameCount = 0;
    private TextFrame frame;
    private ByteBuffer buffer;
    private Utf8CharBuffer utf;
    private WriteCallback callback;
    private boolean closed = false;

    public MessageWriter(OutgoingFrames outgoing, int bufferSize, ByteBufferPool bufferPool)
    {
        this.outgoing = outgoing;
        this.bufferPool = bufferPool;
        this.blocker = new BlockingWriteCallback();
        this.buffer = bufferPool.acquire(bufferSize,true);
        BufferUtil.flipToFill(buffer);
        this.utf = Utf8CharBuffer.wrap(buffer);
        this.frame = new TextFrame();
    }

    public MessageWriter(WebSocketSession session)
    {
        this(session.getOutgoingHandler(),session.getPolicy().getMaxTextMessageBufferSize(),session.getBufferPool());
    }

    private void assertNotClosed() throws IOException
    {
        if (closed)
        {
            IOException e = new IOException("Stream is closed");
            notifyFailure(e);
            throw e;
        }
    }

    @Override
    public synchronized void close() throws IOException
    {
        assertNotClosed();

        // finish sending whatever in the buffer with FIN=true
        flush(true);

        // close stream
        closed = true;
        if (callback != null)
        {
            callback.writeSuccess();
        }
        bufferPool.release(buffer);
        LOG.debug("closed (frame count={})",frameCount);
    }

    @Override
    public void flush() throws IOException
    {
        assertNotClosed();

        // flush whatever is in the buffer with FIN=false
        flush(false);
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
        ByteBuffer data = utf.getByteBuffer();
        frame.setPayload(data);
        frame.setFin(fin);

        try
        {
            outgoing.outgoingFrame(frame,blocker);
            // block on write
            blocker.block();
            // write success
            // clear utf buffer
            utf.clear();
            frameCount++;
            frame.setIsContinuation();
        }
        catch (IOException e)
        {
            notifyFailure(e);
            throw e;
        }
    }

    private void notifyFailure(IOException e)
    {
        if (callback != null)
        {
            callback.writeFailed(e);
        }
    }

    public void setCallback(WriteCallback callback)
    {
        this.callback = callback;
    }

    @Override
    public void write(char[] cbuf) throws IOException
    {
        try
        {
            this.write(cbuf,0,cbuf.length);
        }
        catch (IOException e)
        {
            notifyFailure(e);
            throw e;
        }
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException
    {
        assertNotClosed();
        int left = len; // bytes left to write
        int offset = off; // offset within provided array
        while (left > 0)
        {
            int space = utf.remaining();
            int size = Math.min(space,left);
            assert (space > 0);
            assert (size > 0);
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
        utf.append(c); // append with utf logic
        if (utf.remaining() <= 0)
        {
            flush(false);
        }
    }
}
