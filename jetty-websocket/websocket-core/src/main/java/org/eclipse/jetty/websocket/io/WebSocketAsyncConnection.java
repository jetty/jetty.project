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

import org.eclipse.jetty.io.AbstractAsyncConnection;
import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
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
public class WebSocketAsyncConnection extends AbstractAsyncConnection implements RawConnection, WebSocketConnection, OutgoingFrames
{
    static final Logger LOG = Log.getLogger(WebSocketAsyncConnection.class);
    private static final ThreadLocal<WebSocketAsyncConnection> CURRENT_CONNECTION = new ThreadLocal<WebSocketAsyncConnection>();

    public static WebSocketAsyncConnection getCurrentConnection()
    {
        return CURRENT_CONNECTION.get();
    }

    protected static void setCurrentConnection(WebSocketAsyncConnection connection)
    {
        CURRENT_CONNECTION.set(connection);
    }

    private final ByteBufferPool bufferPool;
    private final ScheduledExecutorService scheduler;
    private final Generator generator;
    private final Parser parser;
    private final WebSocketPolicy policy;
    private final FrameQueue queue;
    private List<ExtensionConfig> extensions;
    private OutgoingFrames outgoingFramesHandler;
    private boolean flushing;

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

    @Override
    public <C> void complete(FrameBytes<C> frameBytes)
    {
        synchronized (queue)
        {
            LOG.debug("Completed Write of {} ({} frame(s) in queue)",frameBytes,queue.size());
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

    private int fill(AsyncEndPoint endPoint, ByteBuffer buffer)
    {
        try
        {
            return endPoint.fill(buffer);
        }
        catch (IOException e)
        {
            LOG.warn(e);
            terminateConnection(StatusCode.PROTOCOL,e.getMessage());
            return -1;
        }
    }

    @Override
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
        }
        write(buffer,this,frameBytes);
    }

    @Override
    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    @Override
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

    @Override
    public Generator getGenerator()
    {
        return generator;
    }

    @Override
    public Parser getParser()
    {
        return parser;
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return this.policy;
    }

    @Override
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

    @Override
    public String getSubProtocol()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean isOpen()
    {
        return getEndPoint().isOpen();
    }

    @Override
    public void onClose()
    {
        LOG.debug("onClose()");
        super.onClose();
    }

    @Override
    public void onFillable()
    {
        setCurrentConnection(this);
        ByteBuffer buffer = bufferPool.acquire(policy.getBufferSize(),false);
        BufferUtil.clear(buffer);
        try
        {
            read(buffer);
        }
        finally
        {
            fillInterested();
            setCurrentConnection(null);
            bufferPool.release(buffer);
        }
    }

    @Override
    public void onOpen()
    {
        LOG.debug("onOpen()");
        super.onOpen();
        fillInterested();
    }

    @Override
    public void output(WebSocketFrame frame)
    {
        // TODO Auto-generated method stub
    }

    @Override
    public <C> void ping(C context, Callback<C> callback, byte[] payload) throws IOException
    {
        WebSocketFrame frame = new WebSocketFrame(OpCode.PING).setPayload(payload);
        ControlFrameBytes<C> bytes = new ControlFrameBytes<C>(this,callback,context,frame);
        scheduleTimeout(bytes);
        queue.prepend(bytes);
    }

    private void read(ByteBuffer buffer)
    {
        while (true)
        {
            int filled = fill(getEndPoint(),buffer);
            if (filled == 0)
            {
                break;
            }
            if (filled < 0)
            {
                // IO error
                terminateConnection(StatusCode.PROTOCOL,null);
                break;
            }

            LOG.debug("Filled {} bytes - {}",filled,BufferUtil.toDetailString(buffer));
            parser.parse(buffer);
        }
    }

    private <C> void scheduleTimeout(FrameBytes<C> bytes)
    {
        if (policy.getMaxIdleTime() > 0)
        {
            bytes.task = scheduler.schedule(bytes,policy.getMaxIdleTime(),TimeUnit.MILLISECONDS);
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
        LOG.debug("Writing {} frame bytes of {}",buffer.remaining(),frameBytes);
        getEndPoint().write(frameBytes.context,frameBytes,buffer);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <C> void write(C context, Callback<C> callback, byte buf[], int offset, int len) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("write(context,{},byte[],{},{})",callback,offset,len);
        }
        if (len == 0)
        {
            // nothing to write
            return;
        }

        WebSocketFrame frame = WebSocketFrame.binary().setPayload(buf,offset,len);
        DataFrameBytes<C> bytes = new DataFrameBytes<C>(this,callback,context,frame);
        scheduleTimeout(bytes);
        queue.append(bytes);
        flush();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <C> void write(C context, Callback<C> callback, ByteBuffer buffer) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("write(context,{},ByteBuffer->{})",callback,BufferUtil.toDetailString(buffer));
        }

        WebSocketFrame frame = WebSocketFrame.binary().setPayload(buffer);
        DataFrameBytes<C> bytes = new DataFrameBytes<C>(this,callback,context,frame);
        scheduleTimeout(bytes);
        queue.append(bytes);
        flush();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <C> void write(C context, Callback<C> callback, String message) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("write(context,{},message.length:{})",callback,message.length());
        }

        WebSocketFrame frame = WebSocketFrame.text(message);
        DataFrameBytes<C> bytes = new DataFrameBytes<C>(this,callback,context,frame);
        scheduleTimeout(bytes);
        queue.append(bytes);
        flush();
    }

    @Override
    public <C> void write(C context, Callback<C> callback, WebSocketFrame frame)
    {
        if (frame.getOpCode().isControlFrame())
        {
            ControlFrameBytes<C> bytes = new ControlFrameBytes<C>(this,callback,context,frame);
            scheduleTimeout(bytes);
            queue.prepend(bytes);
        }
        else
        {
            DataFrameBytes<C> bytes = new DataFrameBytes<C>(this,callback,context,frame);
            scheduleTimeout(bytes);
            queue.append(bytes);
        }
    }
}
