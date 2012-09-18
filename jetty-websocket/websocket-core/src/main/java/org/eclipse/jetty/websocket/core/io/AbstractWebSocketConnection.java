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

package org.eclipse.jetty.websocket.core.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.core.api.BaseConnection;
import org.eclipse.jetty.websocket.core.api.CloseException;
import org.eclipse.jetty.websocket.core.api.StatusCode;
import org.eclipse.jetty.websocket.core.api.WebSocketConnection;
import org.eclipse.jetty.websocket.core.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.protocol.CloseInfo;
import org.eclipse.jetty.websocket.core.protocol.ExtensionConfig;
import org.eclipse.jetty.websocket.core.protocol.Generator;
import org.eclipse.jetty.websocket.core.protocol.OpCode;
import org.eclipse.jetty.websocket.core.protocol.Parser;
import org.eclipse.jetty.websocket.core.protocol.WebSocketFrame;

/**
 * Provides the implementation of {@link WebSocketConnection} within the framework of the new {@link Connection} framework of jetty-io
 */
public abstract class AbstractWebSocketConnection extends AbstractConnection implements BaseConnection, BaseConnection.SuspendToken, OutgoingFrames
{
    private static final Logger LOG = Log.getLogger(AbstractWebSocketConnection.class);
    private static final Logger LOG_FRAMES = Log.getLogger("org.eclipse.jetty.websocket.io.Frames");

    private final ByteBufferPool bufferPool;
    private final Scheduler scheduler;
    private final Generator generator;
    private final Parser parser;
    private final WebSocketPolicy policy;
    private final FrameQueue queue;
    private final AtomicBoolean suspendToken;
    private WebSocketSession session;
    private List<ExtensionConfig> extensions;
    private boolean flushing;
    private boolean isFilling;
    private BaseConnection.State connectionState;

    public AbstractWebSocketConnection(EndPoint endp, Executor executor, Scheduler scheduler, WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        super(endp,executor);
        this.policy = policy;
        this.bufferPool = bufferPool;
        this.generator = new Generator(policy,bufferPool);
        this.parser = new Parser(policy);
        this.scheduler = scheduler;
        this.extensions = new ArrayList<>();
        this.queue = new FrameQueue();
        this.suspendToken = new AtomicBoolean(false);
        this.connectionState = BaseConnection.State.OPENING;
    }

    @Override
    public void close()
    {
        close(StatusCode.NORMAL,null);
    }

    @Override
    public void close(int statusCode, String reason)
    {
        enqueClose(statusCode,reason);
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
    public void disconnect()
    {
        disconnect(false);
    }

    public void disconnect(boolean onlyOutput)
    {
        connectionState = BaseConnection.State.CLOSED;
        EndPoint endPoint = getEndPoint();
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

    /**
     * Enqueue a close frame.
     * 
     * @param statusCode
     *            the WebSocket status code.
     * @param reason
     *            the (optional) reason string. (null is allowed)
     * @see StatusCode
     */
    private void enqueClose(int statusCode, String reason)
    {
        CloseInfo close = new CloseInfo(statusCode,reason);
        FutureCallback<Void> nop = new FutureCallback<>();
        ControlFrameBytes<Void> frameBytes = new ControlFrameBytes<Void>(this,nop,null,close.asFrame());
        queue.append(frameBytes);
        flush();
    }

    public void flush()
    {
        FrameBytes<?> frameBytes = null;
        ByteBuffer buffer = null;
        synchronized (queue)
        {

            LOG.debug(".flush() - flushing={} - queue.size = {}",flushing,queue.size());
            if (flushing || queue.isEmpty())
            {
                return;
            }

            frameBytes = queue.pop();

            if (!isOpen())
            {
                // No longer have an open connection, drop the frame.
                queue.clear();
                return;
            }

            LOG.debug("Next FrameBytes: {}",frameBytes);

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

            if (connectionState != BaseConnection.State.CLOSED)
            {
                write(buffer,frameBytes);
            }
        }
    }

    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
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

    public Scheduler getScheduler()
    {
        return scheduler;
    }

    public WebSocketSession getSession()
    {
        return session;
    }

    @Override
    public BaseConnection.State getState()
    {
        return connectionState;
    }

    @Override
    public boolean isOpen()
    {
        return (getState() != BaseConnection.State.CLOSED) && getEndPoint().isOpen();
    }

    @Override
    public boolean isReading()
    {
        return isFilling;
    }

    @Override
    public void notifyClosing()
    {
        this.connectionState = BaseConnection.State.CLOSING;
    }

    @Override
    public void onClose()
    {
        super.onClose();
        this.connectionState = BaseConnection.State.CLOSED;
    }

    @Override
    public void onFillable()
    {
        LOG.debug("{} onFillable()",policy.getBehavior());
        ByteBuffer buffer = bufferPool.acquire(getInputBufferSize(),false);
        BufferUtil.clear(buffer);
        boolean readMore = false;
        try
        {
            isFilling = true;
            readMore = (read(buffer) != -1);
        }
        finally
        {
            bufferPool.release(buffer);
        }

        if (readMore && (suspendToken.get() == false))
        {
            fillInterested();
        }
        else
        {
            isFilling = false;
        }
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        this.connectionState = BaseConnection.State.OPENED;
        LOG.debug("fillInterested");
        fillInterested();
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

            if (isOpen())
            {
                if (frame.getOpCode() == OpCode.PING)
                {
                    queue.prepend(bytes);
                }
                else
                {
                    queue.append(bytes);
                }
            }
        }
        flush();
    }

    private int read(ByteBuffer buffer)
    {
        EndPoint endPoint = getEndPoint();
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
            enqueClose(StatusCode.PROTOCOL,e.getMessage());
            return -1;
        }
        catch (CloseException e)
        {
            LOG.warn(e);
            enqueClose(e.getStatusCode(),e.getMessage());
            return -1;
        }
    }

    @Override
    public void resume()
    {
        if (suspendToken.getAndSet(false))
        {
            fillInterested();
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

    @Override
    public SuspendToken suspend()
    {
        suspendToken.set(true);
        return this;
    }

    @Override
    public String toString()
    {
        return String.format("%s{g=%s,p=%s}",super.toString(),generator,parser);
    }

    private <C> void write(ByteBuffer buffer, FrameBytes<C> frameBytes)
    {
        EndPoint endpoint = getEndPoint();

        if (LOG_FRAMES.isDebugEnabled())
        {
            LOG_FRAMES.debug("{} Writing {} frame bytes of {}",policy.getBehavior(),buffer.remaining(),frameBytes);
        }

        if (connectionState == BaseConnection.State.CLOSED)
        {
            // connection is closed, STOP WRITING, geez.
            return;
        }

        try
        {
            endpoint.write(frameBytes.context,frameBytes,buffer);
        }
        catch (Throwable t)
        {
            frameBytes.failed(frameBytes.context,t);
        }
    }
}
