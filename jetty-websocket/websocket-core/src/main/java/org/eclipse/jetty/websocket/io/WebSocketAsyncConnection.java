// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.io.AbstractAsyncConnection;
import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.CloseException;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.protocol.CloseInfo;
import org.eclipse.jetty.websocket.protocol.ExtensionConfig;
import org.eclipse.jetty.websocket.protocol.Generator;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.Parser;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

/**
 * Provides the implementation of {@link WebSocketConnection} within the framework of the new {@link AsyncConnection} framework of jetty-io
 */
public abstract class WebSocketAsyncConnection extends AbstractAsyncConnection implements RawConnection, OutgoingFrames
{
    private static final Logger LOG = Log.getLogger(WebSocketAsyncConnection.class);
    private static final Logger LOG_FRAMES = Log.getLogger("org.eclipse.jetty.websocket.io.Frames");

    private final ByteBufferPool bufferPool;
    private final ScheduledExecutorService scheduler;
    private final Generator generator;
    private final Parser parser;
    private final WebSocketPolicy policy;
    private final FrameQueue queue;
    private WebSocketSession session;
    private List<ExtensionConfig> extensions;
    private boolean flushing;
    private AtomicLong writes;

    public WebSocketAsyncConnection(AsyncEndPoint endp, Executor executor, ScheduledExecutorService scheduler, WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        super(endp,executor);
        this.policy = policy;
        this.bufferPool = bufferPool;
        this.generator = new Generator(policy,bufferPool);
        this.parser = new Parser(policy);
        this.scheduler = scheduler;
        this.extensions = new ArrayList<>();
        this.queue = new FrameQueue();
        this.writes = new AtomicLong(0);
    }

    @Override
    public void close() throws IOException
    {
        terminateConnection(StatusCode.NORMAL,null);
    }

    @Override
    public void close(int statusCode, String reason) throws IOException
    {
        terminateConnection(statusCode,reason);
    }

    public <C> void complete(FrameBytes<C> frameBytes)
    {
        synchronized (queue)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("Completed Write of {} ({} frame(s) in queue)",frameBytes,queue.size());
            }
            flushing = false;
        }
    }

    @Override
    public void disconnect(boolean onlyOutput)
    {
        AsyncEndPoint endPoint = getEndPoint();
        // We need to gently close first, to allow
        // SSL close alerts to be sent by Jetty
        LOG.debug("Shutting down output {}",endPoint);
        endPoint.shutdownOutput();
        if (!onlyOutput)
        {
            LOG.debug("Closing {}",endPoint);
            endPoint.close();
        }
    }

    public void flush()
    {
        FrameBytes<?> frameBytes = null;
        ByteBuffer buffer = null;
        synchronized (queue)
        {
            if (flushing || queue.isEmpty())
            {
                return;
            }

            frameBytes = queue.pop();

            buffer = frameBytes.getByteBuffer();

            if (buffer == null)
            {
                return;
            }

            flushing = true;
            if (LOG.isDebugEnabled())
            {
                LOG.debug("Flushing {}, {} frame(s) in queue",frameBytes,queue.size());
            }
        }
        write(buffer,this,frameBytes);
    }

    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    public Executor getExecutor()
    {
        return getExecutor();
    }

    /**
     * Get the list of extensions in use.
     * <p>
     * This list is negotiated during the WebSocket Upgrade Request/Response handshake.
     *
     * @return the list of negotiated extensions in use.
     */
    public List<ExtensionConfig> getExtensions()
    {
        return extensions;
    }

    public Generator getGenerator()
    {
        return generator;
    }

    public Parser getParser()
    {
        return parser;
    }

    public WebSocketPolicy getPolicy()
    {
        return this.policy;
    }

    public FrameQueue getQueue()
    {
        return queue;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return getEndPoint().getRemoteAddress();
    }

    public ScheduledExecutorService getScheduler()
    {
        return scheduler;
    }

    public WebSocketSession getSession()
    {
        return session;
    }

    @Override
    public boolean isOpen()
    {
        return getEndPoint().isOpen();
    }

    @Override
    public void onFillable()
    {
        ByteBuffer buffer = bufferPool.acquire(policy.getBufferSize(),false);
        BufferUtil.clear(buffer);
        boolean readMore = false;
        try
        {
            readMore = (read(buffer) != -1);
        }
        finally
        {
            bufferPool.release(buffer);
        }
        if (readMore)
        {
            fillInterested();
        }
    }

    /**
     * Enqueue internal frame from {@link OutgoingFrames} stack for eventual write out on the physical connection.
     */
    @Override
    public <C> void output(C context, Callback<C> callback, WebSocketFrame frame)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("output({}, {}, {})",context,callback,frame);
        }

        synchronized (queue)
        {
            FrameBytes<C> bytes = null;

            if (frame.isControlFrame())
            {
                bytes = new ControlFrameBytes<C>(this,callback,context,frame);
            }
            else
            {
                bytes = new DataFrameBytes<C>(this,callback,context,frame);
            }

            scheduleTimeout(bytes);
            if (frame.getOpCode() == OpCode.PING)
            {
                queue.prepend(bytes);
            }
            else
            {
                queue.append(bytes);
            }
        }
        flush();
    }

    private int read(ByteBuffer buffer)
    {
        AsyncEndPoint endPoint = getEndPoint();
        try
        {
            while (true)
            {
                int filled = endPoint.fill(buffer);
                if (filled == 0)
                {
                    return 0;
                }
                else if (filled < 0)
                {
                    LOG.debug("read - EOF Reached");
                    disconnect(false);
                    return -1;
                }
                else
                {
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("Filled {} bytes - {}",filled,BufferUtil.toDetailString(buffer));
                    }
                    parser.parse(buffer);
                }
            }
        }
        catch (IOException e)
        {
            LOG.warn(e);
            terminateConnection(StatusCode.PROTOCOL,e.getMessage());
            return -1;
        }
        catch (CloseException e)
        {
            LOG.warn(e);
            terminateConnection(e.getStatusCode(),e.getMessage());
            return -1;
        }
    }

    private <C> void scheduleTimeout(FrameBytes<C> bytes)
    {
        if (policy.getIdleTimeout() > 0)
        {
            bytes.task = scheduler.schedule(bytes,policy.getIdleTimeout(),TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Get the list of extensions in use.
     * <p>
     * This list is negotiated during the WebSocket Upgrade Request/Response handshake.
     *
     * @param extensions
     *            the list of negotiated extensions in use.
     */
    public void setExtensions(List<ExtensionConfig> extensions)
    {
        this.extensions = extensions;
    }

    public void setSession(WebSocketSession session)
    {
        this.session = session;
    }

    /**
     * For terminating connections forcefully.
     *
     * @param statusCode
     *            the WebSocket status code.
     * @param reason
     *            the (optional) reason string. (null is allowed)
     * @see StatusCode
     */
    private void terminateConnection(int statusCode, String reason)
    {
        CloseInfo close = new CloseInfo(statusCode,reason);
        FutureCallback<Void> nop = new FutureCallback<>();
        ControlFrameBytes<Void> frameBytes = new ControlFrameBytes<Void>(this,nop,null,close.asFrame());
        queue.append(frameBytes);
        flush();
    }

    @Override
    public String toString()
    {
        return String.format("%s{g=%s,p=%s}",super.toString(),generator,parser);
    }

    private <C> void write(ByteBuffer buffer, WebSocketAsyncConnection webSocketAsyncConnection, FrameBytes<C> frameBytes)
    {
        AsyncEndPoint endpoint = getEndPoint();

        if (LOG_FRAMES.isDebugEnabled())
        {
            LOG_FRAMES.debug("{} Writing {} frame bytes of {}",policy.getBehavior(),buffer.remaining(),frameBytes);
        }
        try
        {
            endpoint.write(frameBytes.context,frameBytes,buffer);
            long count = writes.incrementAndGet();
            if ((count % 10) == 0)
            {
                LOG.info("Server wrote {} ByteBuffers",count);
            }
        }
        catch (Throwable t)
        {
            LOG.debug(t);
            frameBytes.failed(frameBytes.context,t);
        }
    }
}
