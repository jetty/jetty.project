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

package org.eclipse.jetty.websocket.common.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.CloseException;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteResult;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.ConnectionState;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketSession;

/**
 * Provides the implementation of {@link WebSocketConnection} within the framework of the new {@link Connection} framework of jetty-io
 */
public abstract class AbstractWebSocketConnection extends AbstractConnection implements LogicalConnection
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
    private ConnectionState connectionState;
    private final AtomicBoolean inputClosed;
    private final AtomicBoolean outputClosed;

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
        this.connectionState = ConnectionState.CONNECTING;

        this.inputClosed = new AtomicBoolean(false);
        this.outputClosed = new AtomicBoolean(false);
    }

    @Override
    public void assertInputOpen() throws IOException
    {
        if (isInputClosed())
        {
            throw new IOException("Connection input is closed");
        }
    }

    @Override
    public void assertOutputOpen() throws IOException
    {
        if (isOutputClosed())
        {
            throw new IOException("Connection output is closed");
        }
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

    public <C> void complete(FrameBytes frameBytes)
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
        connectionState = ConnectionState.CLOSED;
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
        try
        {
            outgoingFrame(close.asFrame());
        }
        catch (IOException e)
        {
            LOG.info("Unable to enque close frame",e);
            // TODO: now what?
            disconnect();
        }
    }

    public void flush()
    {
        FrameBytes frameBytes = null;
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

            if (connectionState != ConnectionState.CLOSED)
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

    @Override
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

    @Override
    public WebSocketSession getSession()
    {
        return session;
    }

    @Override
    public ConnectionState getState()
    {
        return connectionState;
    }

    @Override
    public boolean isInputClosed()
    {
        return inputClosed.get();
    }

    @Override
    public boolean isOpen()
    {
        return (getState() != ConnectionState.CLOSED) && getEndPoint().isOpen();
    }

    @Override
    public boolean isOutputClosed()
    {
        return outputClosed.get();
    }

    @Override
    public boolean isReading()
    {
        return isFilling;
    }

    @Override
    public void onClose()
    {
        super.onClose();
        this.connectionState = ConnectionState.CLOSED;
    }

    @Override
    public void onCloseHandshake(boolean incoming, CloseInfo close)
    {
        boolean in = inputClosed.get();
        boolean out = outputClosed.get();
        if (incoming)
        {
            in = true;
            this.inputClosed.set(true);
        }
        else
        {
            out = true;
            this.outputClosed.set(true);
        }

        LOG.debug("onCloseHandshake({},{}), input={}, output={}",incoming,close,in,out);

        if (in && out)
        {
            LOG.debug("Close Handshake satisfied, disconnecting");
            this.disconnect(false);
        }

        if (close.isHarsh())
        {
            LOG.debug("Close status code was harsh, disconnecting");
            this.disconnect(false);
        }
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
        this.connectionState = ConnectionState.OPEN;
        LOG.debug("fillInterested");
        fillInterested();
    }

    @Override
    protected boolean onReadTimeout()
    {
        LOG.debug("Read Timeout. disconnecting connection");
        // TODO: notify end user websocket of read timeout?
        return true;
    }

    @Override
    public Future<WriteResult> outgoingFrame(Frame frame) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("outgoingFrame({})",frame);
        }

        Future<WriteResult> future = null;

        synchronized (queue)
        {
            FrameBytes bytes = null;

            if (frame.getType().isControl())
            {
                bytes = new ControlFrameBytes(this,frame);
            }
            else
            {
                bytes = new DataFrameBytes(this,frame);
            }

            future = new WriteResultFuture(bytes);

            scheduleTimeout(bytes);

            if (isOpen())
            {
                if (frame.getType().getOpCode() == OpCode.PING)
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

        return future;
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

    private void scheduleTimeout(FrameBytes bytes)
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

    @Override
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

    private <C> void write(ByteBuffer buffer, FrameBytes frameBytes)
    {
        EndPoint endpoint = getEndPoint();

        if (LOG_FRAMES.isDebugEnabled())
        {
            LOG_FRAMES.debug("{} Writing {} frame bytes of {}",policy.getBehavior(),buffer.remaining(),frameBytes);
        }

        if (connectionState == ConnectionState.CLOSED)
        {
            // connection is closed, STOP WRITING, geez.
            return;
        }

        try
        {
            endpoint.write(frameBytes,buffer);
        }
        catch (Throwable t)
        {
            frameBytes.failed(t);
        }
    }
}
