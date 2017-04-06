//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.FrameCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;

/**
 * Support class for reading a WebSocket BINARY message via a InputStream.
 * <p>
 * An InputStream that can access a queue of ByteBuffer payloads, along with expected InputStream blocking behavior.
 * </p>
 */
public class MessageInputStream extends InputStream implements MessageSink
{
    private static final Logger LOG = Log.getLogger(MessageInputStream.class);
    private static final FrameCallbackBuffer EOF = new FrameCallbackBuffer(new FrameCallback.Adapter(), ByteBuffer.allocate(0).asReadOnlyBuffer());
    private final Deque<FrameCallbackBuffer> buffers = new ArrayDeque<>(2);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    @Override
    public void accept(Frame frame, FrameCallback callback)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("accepting {}", frame);
        }
        
        // If closed, we should just toss incoming payloads into the bit bucket.
        if (closed.get())
        {
            callback.fail(new IOException("Already Closed"));
            return;
        }
        
        if (!frame.hasPayload() && !frame.isFin())
        {
            callback.succeed();
            return;
        }
        
        synchronized (buffers)
        {
            ByteBuffer payload = frame.getPayload();
            buffers.offer(new FrameCallbackBuffer(callback, payload));
            
            if (frame.isFin())
            {
                buffers.offer(EOF);
            }
            
            // notify other thread
            buffers.notify();
        }
    }
    
    @Override
    public void close() throws IOException
    {
        if (closed.compareAndSet(false, true))
        {
            synchronized (buffers)
            {
                buffers.offer(EOF);
                buffers.notify();
            }
        }
        super.close();
    }
    
    private void shutdown()
    {
        closed.set(true);
        // Removed buffers that may have remained in the queue.
        buffers.clear();
    }
    
    @Override
    public void mark(int readlimit)
    {
        // Not supported.
    }
    
    @Override
    public boolean markSupported()
    {
        return false;
    }
    
    @Override
    public int read() throws IOException
    {
        byte buf[] = new byte[1];
        while (true)
        {
            int len = read(buf, 0, 1);
            if (len < 0) // EOF
                return -1;
            if (len > 0) // did read something
                return buf[0];
            // reading nothing (len == 0) tries again
        }
    }
    
    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        if (closed.get())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Stream closed");
            return -1;
        }
        
        // sync and poll queue
        FrameCallbackBuffer result;
        synchronized (buffers)
        {
            try
            {
                while ((result = buffers.poll()) == null)
                {
                    // TODO: handle read timeout here?
                    buffers.wait();
                }
            }
            catch (InterruptedException e)
            {
                shutdown();
                throw new InterruptedIOException();
            }
        }
        
        if (result == EOF)
        {
            shutdown();
            return -1;
        }
        
        // We have content
        int fillLen = Math.min(result.buffer.remaining(), len);
        result.buffer.get(b, off, fillLen);
        
        if (!result.buffer.hasRemaining())
        {
            result.callback.succeed();
        }
        
        // return number of bytes actually copied into buffer
        return fillLen;
    }
    
    @Override
    public void reset() throws IOException
    {
        throw new IOException("reset() not supported");
    }
}
