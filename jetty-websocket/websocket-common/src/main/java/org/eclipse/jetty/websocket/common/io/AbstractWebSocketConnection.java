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

package org.eclipse.jetty.websocket.common.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ForkInvoker;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.CloseException;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.ConnectionState;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketSession;

/**
 * Provides the implementation of {@link WebSocketConnection} within the framework of the new {@link Connection} framework of jetty-io
 */
public abstract class AbstractWebSocketConnection extends AbstractConnection implements LogicalConnection
{
    private class FlushCallback implements Callback
    {
        @Override
        public void failed(Throwable x)
        {
            LOG.warn("Write flush failure",x);
        }

        @Override
        public void succeeded()
        {
            // Lets process the next set of bytes...
            AbstractWebSocketConnection.this.complete(writeBytes);
        }
    }

    private class FlushInvoker extends ForkInvoker<Callback>
    {
        private FlushInvoker()
        {
            super(4);
        }

        @Override
        public void call(Callback callback)
        {
            flush();
        }

        @Override
        public void fork(final Callback callback)
        {
            execute(new Runnable()
            {
                @Override
                public void run()
                {
                    flush();
                }
            });
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x",FlushInvoker.class.getSimpleName(),hashCode());
        }
    }

    private class OnCloseCallback implements WriteCallback
    {
        @Override
        public void writeFailed(Throwable x)
        {
            disconnect();
        }

        @Override
        public void writeSuccess()
        {
            onWriteWebSocketClose();
        }
    }

    private static final Logger LOG = Log.getLogger(AbstractWebSocketConnection.class);

    /**
     * Minimum size of a buffer is the determined to be what would be the maximum framing header size (not including payload)
     */
    private static final int MIN_BUFFER_SIZE = Generator.OVERHEAD;

    private final ForkInvoker<Callback> invoker = new FlushInvoker();
    private final ByteBufferPool bufferPool;
    private final Scheduler scheduler;
    private final Generator generator;
    private final Parser parser;
    private final WebSocketPolicy policy;
    private final WriteBytesProvider writeBytes;
    private final AtomicBoolean suspendToken;
    private WebSocketSession session;
    private List<ExtensionConfig> extensions;
    private boolean flushing;
    private boolean isFilling;
    private IOState ioState;

    public AbstractWebSocketConnection(EndPoint endp, Executor executor, Scheduler scheduler, WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        super(endp,executor,EXECUTE_ONFILLABLE); // TODO review if this is best. Specifically with MUX
        this.policy = policy;
        this.bufferPool = bufferPool;
        this.generator = new Generator(policy,bufferPool);
        this.parser = new Parser(policy,bufferPool);
        this.scheduler = scheduler;
        this.extensions = new ArrayList<>();
        this.suspendToken = new AtomicBoolean(false);
        this.ioState = new IOState();
        this.ioState.setState(ConnectionState.CONNECTING);
        this.writeBytes = new WriteBytesProvider(generator,new FlushCallback());
        this.setInputBufferSize(policy.getInputBufferSize());
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

    public void complete(final Callback callback)
    {
        LOG.debug("complete({})",callback);
        synchronized (writeBytes)
        {
            flushing = false;
        }

        if (!ioState.isOpen() || (callback == null))
        {
            return;
        }

        invoker.invoke(callback);
    }

    @Override
    public void disconnect()
    {
        disconnect(false);
    }

    public void disconnect(boolean onlyOutput)
    {
        ioState.setState(ConnectionState.CLOSED);
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
        synchronized (writeBytes)
        {
            // It is possible to get close events from many different sources.
            // Make sure we only sent 1 over the network.
            if (writeBytes.isClosed())
            {
                // already sent the close
                return;
            }
        }
        CloseInfo close = new CloseInfo(statusCode,reason);
        // TODO: create DisconnectCallback?
        outgoingFrame(close.asFrame(),new OnCloseCallback());
    }

    private void execute(Runnable task)
    {
        try
        {
            getExecutor().execute(task);
        }
        catch (RejectedExecutionException e)
        {
            LOG.debug("Job not dispatched: {}",task);
        }
    }

    public void flush()
    {
        ByteBuffer buffer = null;

        synchronized (writeBytes)
        {
            if (writeBytes.isFailed())
            {
                LOG.debug(".flush() - queue is in failed state");
                return;
            }

            if (flushing)
            {
                return;
            }

            if (LOG.isDebugEnabled())
            {
                LOG.debug(".flush() - flushing={} - writeBytes={}",flushing,writeBytes);
            }

            if (!isOpen())
            {
                // No longer have an open connection, drop them all.
                writeBytes.failAll(new WebSocketException("Connection closed"));
                return;
            }

            buffer = writeBytes.getByteBuffer();

            if (buffer == null)
            {
                return;
            }

            flushing = true;

            if (LOG.isDebugEnabled())
            {
                LOG.debug("Flushing {} - {}",BufferUtil.toDetailString(buffer),writeBytes);
            }
        }

        write(buffer);
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

    @Override
    public IOState getIOState()
    {
        return ioState;
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
    public boolean isOpen()
    {
        return getIOState().isOpen() && getEndPoint().isOpen();
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
        this.getIOState().setState(ConnectionState.CLOSED);
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
        this.ioState.setState(ConnectionState.OPEN);
        LOG.debug("fillInterested");
        fillInterested();
    }

    @Override
    protected boolean onReadTimeout()
    {
        LOG.warn("Read Timeout");

        if ((ioState.getState() == ConnectionState.CLOSING) || (ioState.getState() == ConnectionState.CLOSED))
        {
            // close already initiated, extra timeouts not relevant
            // allow udnerlying connection and endpoint to disconnect on its own
            return true;
        }

        // Initiate close - politely send close frame.
        // Note: it is not possible in 100% of cases during read timeout to send this close frame.
        session.close(StatusCode.NORMAL,"Idle Timeout");

        return false;
    }

    public void onWriteWebSocketClose()
    {
        if (ioState.onCloseHandshake(false))
        {
            disconnect();
        }
    }

    /**
     * Frame from API, User, or Internal implementation destined for network.
     */
    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("outgoingFrame({}, {})",frame,callback);
        }

        if (!isOpen())
        {
            return;
        }

        synchronized (writeBytes)
        {
            writeBytes.enqueue(frame,WriteCallbackWrapper.wrap(callback));
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
    public void setInputBufferSize(int inputBufferSize)
    {
        if(inputBufferSize < MIN_BUFFER_SIZE) {
            throw new IllegalArgumentException("Cannot have buffer size less than " + MIN_BUFFER_SIZE);
        }
        super.setInputBufferSize(inputBufferSize);
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

    private <C> void write(ByteBuffer buffer)
    {
        EndPoint endpoint = getEndPoint();

        if (!isOpen())
        {
            writeBytes.failAll(new IOException("Connection closed"));
            return;
        }

        try
        {
            endpoint.write(writeBytes,buffer);
        }
        catch (Throwable t)
        {
            writeBytes.failed(t);
        }
    }
}
