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

package org.eclipse.jetty.websocket.common.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.FrameCallback;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.extensions.ExtensionStack;

/**
 * Provides the implementation of {@link LogicalConnection} within the framework of the new {@link org.eclipse.jetty.io.Connection} framework of {@code jetty-io}.
 */
public abstract class AbstractWebSocketConnection extends AbstractConnection implements LogicalConnection, Connection.UpgradeTo, Dumpable, Parser.Handler
{
    private class Flusher extends FrameFlusher
    {
        private Flusher(int bufferSize, Generator generator, EndPoint endpoint)
        {
            super(generator,endpoint,bufferSize,8);
        }

        @Override
        protected void onFailure(Throwable x)
        {
            notifyError(x);
        }
    }

    /**
     * Minimum size of a buffer is the determined to be what would be the maximum framing header size (not including payload)
     */
    private static final int MIN_BUFFER_SIZE = Generator.MAX_HEADER_LENGTH;
    
    private final Logger LOG;
    private final ByteBufferPool bufferPool;
    private final Scheduler scheduler;
    private final Generator generator;
    private final Parser parser;
    private final WebSocketPolicy policy;
    private final AtomicBoolean suspendToken;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final FrameFlusher flusher;
    private final String id;
    private final ExtensionStack extensionStack;
    private final List<LogicalConnection.Listener> listeners = new CopyOnWriteArrayList<>();
    private List<ExtensionConfig> extensions;
    private ByteBuffer networkBuffer;
    
    public AbstractWebSocketConnection(EndPoint endp, Executor executor, Scheduler scheduler, WebSocketPolicy policy, ByteBufferPool bufferPool, ExtensionStack extensionStack)
    {
        super(endp,executor);
    
        Objects.requireNonNull(endp, "EndPoint");
        Objects.requireNonNull(executor, "Executor");
        Objects.requireNonNull(scheduler, "Scheduler");
        Objects.requireNonNull(policy, "WebSocketPolicy");
        Objects.requireNonNull(bufferPool, "ByteBufferPool");
    
        LOG = Log.getLogger(AbstractWebSocketConnection.class.getName() + "." + policy.getBehavior());
        
        this.id = String.format("%s:%d->%s:%d",
                endp.getLocalAddress().getAddress().getHostAddress(),
                endp.getLocalAddress().getPort(),
                endp.getRemoteAddress().getAddress().getHostAddress(),
                endp.getRemoteAddress().getPort());
        this.policy = policy;
        this.bufferPool = bufferPool;
        this.extensionStack = extensionStack;
    
        this.generator = new Generator(policy,bufferPool);
        this.parser = new Parser(policy,bufferPool,this);
        this.scheduler = scheduler;
        this.extensions = new ArrayList<>();
        this.suspendToken = new AtomicBoolean(false);
        this.flusher = new Flusher(policy.getOutputBufferSize(),generator,endp);
        this.setInputBufferSize(policy.getInputBufferSize());
        this.setMaxIdleTimeout(policy.getIdleTimeout());
        
        this.extensionStack.setPolicy(this.policy);
        this.extensionStack.configure(this.parser);
        this.extensionStack.configure(this.generator);
    }
    
    @Override
    public Executor getExecutor()
    {
        return super.getExecutor();
    }

    @Override
    public void disconnect()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnect()");
        
        // close FrameFlusher, we cannot write anymore at this point.
        flusher.close();
        
        // We need to gently close first, to allow
        // SSL close alerts to be sent by Jetty
        getEndPoint().close();
        closed.set(true);
    }
    
    protected void execute(Runnable task)
    {
        try
        {
            getExecutor().execute(task);
        }
        catch (RejectedExecutionException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Job not dispatched: {}",task);
        }
    }

    @Override
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
    public String getId()
    {
        return id;
    }

    @Override
    public long getIdleTimeout()
    {
        return getEndPoint().getIdleTimeout();
    }

    @Override
    public long getMaxIdleTimeout()
    {
        return getEndPoint().getIdleTimeout();
    }

    public Parser getParser()
    {
        return parser;
    }
    
    public WebSocketPolicy getPolicy()
    {
        return policy;
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
    public boolean isOpen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug(".isOpen() = {}", !closed.get());
        return !closed.get();
    }

    /**
     * Physical connection disconnect.
     * <p>
     * Not related to WebSocket close handshake.
     */
    @Override
    public void onClose()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onClose()");
        
        closed.set(true);
        
        flusher.close();
        super.onClose();
    }

    @Override
    public boolean onFrame(Frame frame)
    {
        AtomicBoolean result = new AtomicBoolean(false);
        
        if(LOG.isDebugEnabled())
            LOG.debug("onFrame({})", frame);
        
        extensionStack.incomingFrame(frame, new FrameCallback()
        {
            @Override
            public void succeed()
            {
                if(LOG.isDebugEnabled())
                    LOG.debug("onFrame({}).succeed()", frame);
                parser.release(frame);
                if(!result.compareAndSet(false,true))
                {
                    // callback has been notified asynchronously
                    fillAndParse();
                    // fillInterested();
                }
            }
            
            @Override
            public void fail(Throwable cause)
            {
                if(LOG.isDebugEnabled())
                    LOG.debug("onFrame("+ frame + ").fail()", cause);
                parser.release(frame);
                
                // notify session & endpoint
                notifyError(cause);
            }
        });
        
        if(result.compareAndSet(false, true))
        {
            // callback hasn't been notified yet
            return false;
        }
        
        return true;
    }
    
    private ByteBuffer getNetworkBuffer()
    {
        synchronized (this)
        {
            if (networkBuffer == null)
            {
                networkBuffer = bufferPool.acquire(getInputBufferSize(), true);
            }
            return networkBuffer;
        }
    }
    
    private void releaseNetworkBuffer(ByteBuffer buffer)
    {
        synchronized (this)
        {
            assert(!buffer.hasRemaining());
            bufferPool.release(buffer);
            networkBuffer = null;
        }
    }
    
    @Override
    public void onFillable()
    {
        getNetworkBuffer();
        fillAndParse();
    }
    
    private void fillAndParse()
    {
        try
        {
            while (isOpen())
            {
                if (suspendToken.get())
                {
                    return;
                }
                
                ByteBuffer nBuffer = getNetworkBuffer();
    
                if (!parser.parse(nBuffer)) return;
                
                // Shouldn't reach this point if buffer has un-parsed bytes
                assert(!nBuffer.hasRemaining());
    
                int filled = getEndPoint().fill(nBuffer);
                
                if(LOG.isDebugEnabled())
                    LOG.debug("endpointFill() filled={}: {}", filled, BufferUtil.toDetailString(nBuffer));
    
                if (filled < 0)
                {
                    releaseNetworkBuffer(nBuffer);
                    return;
                }
    
                if (filled == 0)
                {
                    releaseNetworkBuffer(nBuffer);
                    fillInterested();
                    return;
                }
            }
        }
        catch (Throwable t)
        {
            notifyError(t);
        }
    }
    
    
    /**
     * Extra bytes from the initial HTTP upgrade that need to
     * be processed by the websocket parser before starting
     * to read bytes from the connection
     * @param prefilled the bytes of prefilled content encountered during upgrade
     */
    protected void setInitialBuffer(ByteBuffer prefilled)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("set Initial Buffer - {}",BufferUtil.toDetailString(prefilled));
        }
    
        if ((prefilled != null) && (prefilled.hasRemaining()))
        {
            networkBuffer = bufferPool.acquire(prefilled.remaining(), true);
            BufferUtil.clearToFill(networkBuffer);
            BufferUtil.put(prefilled, networkBuffer);
            BufferUtil.flipToFlush(networkBuffer, 0);
        }
    }

    private void notifyError(Throwable cause)
    {
        if (listeners.isEmpty())
        {
            LOG.warn("Unhandled Connection Error", cause);
        }
    
        for (LogicalConnection.Listener listener : listeners)
        {
            try
            {
                listener.onError(cause);
            }
            catch (Exception e)
            {
                cause.addSuppressed(e);
                LOG.warn("Bad onError() call", cause);
            }
        }
    }
    
    /**
     * Physical connection Open.
     */
    @Override
    public void onOpen()
    {
        if(LOG.isDebugEnabled())
            LOG.debug("{}.onOpened()",this.getClass().getSimpleName());
        super.onOpen();
    }

    /**
     * Event for no activity on connection (read or write)
     */
    @Override
    protected boolean onReadTimeout()
    {
        notifyError(new SocketTimeoutException("Timeout on Read"));
        return false;
    }

    /**
     * Frame from API, User, or Internal implementation destined for network.
     */
    @Override
    public void outgoingFrame(Frame frame, FrameCallback callback, BatchMode batchMode)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("outgoingFrame({}, {})",frame,callback);
        }

        flusher.enqueue(frame,callback,batchMode);
    }
    
    @Override
    public void resume()
    {
        if (suspendToken.compareAndSet(true, false))
        {
            fillAndParse();
        }
    }
    
    public boolean addListener(LogicalConnection.Listener listener)
    {
        super.addListener(listener);
        return this.listeners.add(listener);
    }
    
    public boolean removeListener(LogicalConnection.Listener listener)
    {
        super.removeListener(listener);
        return this.listeners.remove(listener);
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
        if (inputBufferSize < MIN_BUFFER_SIZE)
        {
            throw new IllegalArgumentException("Cannot have buffer size less than " + MIN_BUFFER_SIZE);
        }
        super.setInputBufferSize(inputBufferSize);
    }

    @Override
    public void setMaxIdleTimeout(long ms)
    {
        if(ms >= 0)
        {
            getEndPoint().setIdleTimeout(ms);
        }
    }
    
    @Override
    public SuspendToken suspend()
    {
        suspendToken.set(true);
        return this;
    }

    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        out.append(toString()).append(System.lineSeparator());
    }

    @Override
    public String toConnectionString()
    {
        return String.format("%s@%x[f=%s,g=%s,p=%s]",
                getClass().getSimpleName(),
                hashCode(),
                flusher,
                generator,
                parser);
    }
    
    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;

        EndPoint endp = getEndPoint();
        if(endp != null)
        {
            result = prime * result + endp.getLocalAddress().hashCode();
            result = prime * result + endp.getRemoteAddress().hashCode();
        }
        return result;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        AbstractWebSocketConnection other = (AbstractWebSocketConnection)obj;
        EndPoint endp = getEndPoint();
        EndPoint otherEndp = other.getEndPoint();
        if (endp == null)
        {
            if (otherEndp != null)
                return false;
        }
        else if (!endp.equals(otherEndp))
            return false;
        return true;
    }

    /**
     * Extra bytes from the initial HTTP upgrade that need to
     * be processed by the websocket parser before starting
     * to read bytes from the connection
     */
    @Override
    public void onUpgradeTo(ByteBuffer prefilled)
    {
        if(LOG.isDebugEnabled())
        {
            LOG.debug("onUpgradeTo({})", BufferUtil.toDetailString(prefilled));
        }
    
        setInitialBuffer(prefilled);
    }
}
