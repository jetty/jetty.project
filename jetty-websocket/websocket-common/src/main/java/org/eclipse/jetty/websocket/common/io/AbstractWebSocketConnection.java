//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.CloseException;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.SuspendToken;
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
import org.eclipse.jetty.websocket.common.io.IOState.ConnectionStateListener;

/**
 * Provides the implementation of {@link LogicalConnection} within the framework of the new {@link Connection} framework of {@code jetty-io}.
 */
public abstract class AbstractWebSocketConnection extends AbstractConnection implements LogicalConnection, ConnectionStateListener, Dumpable
{
    private class Flusher extends FrameFlusher
    {
        private Flusher(ByteBufferPool bufferPool, Generator generator, EndPoint endpoint)
        {
            super(bufferPool,generator,endpoint,getPolicy().getMaxBinaryMessageBufferSize(),8);
        }

        @Override
        protected void onFailure(Throwable x)
        {
            session.notifyError(x);

            if (ioState.wasAbnormalClose())
            {
                LOG.ignore(x);
                return;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Write flush failure",x);
            ioState.onWriteFailure(x);
        }
    }

    public class OnDisconnectCallback implements WriteCallback
    {
        private final boolean outputOnly;

        public OnDisconnectCallback(boolean outputOnly)
        {
            this.outputOnly = outputOnly;
        }

        @Override
        public void writeFailed(Throwable x)
        {
            disconnect(outputOnly);
        }

        @Override
        public void writeSuccess()
        {
            disconnect(outputOnly);
        }
    }

    public class OnCloseLocalCallback implements WriteCallback
    {
        private final WriteCallback callback;
        private final CloseInfo close;

        public OnCloseLocalCallback(WriteCallback callback, CloseInfo close)
        {
            this.callback = callback;
            this.close = close;
        }

        public OnCloseLocalCallback(CloseInfo close)
        {
            this(null,close);
        }

        @Override
        public void writeFailed(Throwable x)
        {
            try
            {
                if (callback != null)
                {
                    callback.writeFailed(x);
                }
            }
            finally
            {
                onLocalClose();
            }
        }

        @Override
        public void writeSuccess()
        {
            try
            {
                if (callback != null)
                {
                    callback.writeSuccess();
                }
            }
            finally
            {
                onLocalClose();
            }
        }

        private void onLocalClose()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Local Close Confirmed {}",close);
            if (close.isAbnormal())
            {
                ioState.onAbnormalClose(close);
            }
            else
            {
                ioState.onCloseLocal(close);
            }
        }
    }

    public static class Stats
    {
        private AtomicLong countFillInterestedEvents = new AtomicLong(0);
        private AtomicLong countOnFillableEvents = new AtomicLong(0);
        private AtomicLong countFillableErrors = new AtomicLong(0);

        public long getFillableErrorCount()
        {
            return countFillableErrors.get();
        }

        public long getFillInterestedCount()
        {
            return countFillInterestedEvents.get();
        }

        public long getOnFillableCount()
        {
            return countOnFillableEvents.get();
        }
    }
    
    private static enum ReadMode
    {
        PARSE,
        DISCARD,
        EOF
    }

    private static final Logger LOG = Log.getLogger(AbstractWebSocketConnection.class);

    /**
     * Minimum size of a buffer is the determined to be what would be the maximum framing header size (not including payload)
     */
    private static final int MIN_BUFFER_SIZE = Generator.MAX_HEADER_LENGTH;

    private final ByteBufferPool bufferPool;
    private final Scheduler scheduler;
    private final Generator generator;
    private final Parser parser;
    private final WebSocketPolicy policy;
    private final AtomicBoolean suspendToken;
    private final FrameFlusher flusher;
    private WebSocketSession session;
    private List<ExtensionConfig> extensions;
    private boolean isFilling;
    private ReadMode readMode = ReadMode.PARSE;
    private IOState ioState;
    private Stats stats = new Stats();

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
        this.ioState.addListener(this);
        this.flusher = new Flusher(bufferPool,generator,endp);
        this.setInputBufferSize(policy.getInputBufferSize());
        this.setMaxIdleTimeout(policy.getIdleTimeout());
    }

    @Override
    public Executor getExecutor()
    {
        return super.getExecutor();
    }

    @Override
    public void close()
    {
        close(StatusCode.NORMAL,null);
    }

    /**
     * Close the connection.
     * <p>
     * This can result in a close handshake over the network, or a simple local abnormal close
     * 
     * @param statusCode
     *            the WebSocket status code.
     * @param reason
     *            the (optional) reason string. (null is allowed)
     * @see StatusCode
     */
    @Override
    public void close(int statusCode, String reason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("close({},{})",statusCode,reason);
        CloseInfo close = new CloseInfo(statusCode,reason);
        this.outgoingFrame(close.asFrame(),new OnCloseLocalCallback(close),BatchMode.OFF);
    }

    @Override
    public void disconnect()
    {
        disconnect(false);
    }

    private void disconnect(boolean onlyOutput)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} disconnect({})",policy.getBehavior(),onlyOutput?"outputOnly":"both");
        // close FrameFlusher, we cannot write anymore at this point.
        flusher.close();
        EndPoint endPoint = getEndPoint();
        // We need to gently close first, to allow
        // SSL close alerts to be sent by Jetty
        if (LOG.isDebugEnabled())
            LOG.debug("Shutting down output {}",endPoint);
        endPoint.shutdownOutput();
        if (!onlyOutput)
        {
            LOG.debug("Closing {}",endPoint);
            endPoint.close();
        }
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
    public void fillInterested()
    {
        stats.countFillInterestedEvents.incrementAndGet();
        super.fillInterested();
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
    public long getIdleTimeout()
    {
        return getEndPoint().getIdleTimeout();
    }

    @Override
    public IOState getIOState()
    {
        return ioState;
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

    public Stats getStats()
    {
        return stats;
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

    /**
     * Physical connection disconnect.
     * <p>
     * Not related to WebSocket close handshake.
     */
    @Override
    public void onClose()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onClose()",policy.getBehavior());
        super.onClose();
        // ioState.onDisconnected();
        flusher.close();
    }

    @Override
    public void onConnectionStateChange(ConnectionState state)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} Connection State Change: {}",policy.getBehavior(),state);
        switch (state)
        {
            case OPEN:
                if (LOG.isDebugEnabled())
                    LOG.debug("fillInterested");
                fillInterested();
                break;
            case CLOSED:
                if (ioState.wasAbnormalClose())
                {
                    // Fire out a close frame, indicating abnormal shutdown, then disconnect
                    CloseInfo abnormal = new CloseInfo(StatusCode.SHUTDOWN,"Abnormal Close - " + ioState.getCloseInfo().getReason());
                    outgoingFrame(abnormal.asFrame(),new OnDisconnectCallback(false),BatchMode.OFF);
                }
                else
                {
                    // Just disconnect
                    this.disconnect(false);
                }
                break;
            case CLOSING:
                // First occurrence of .onCloseLocal or .onCloseRemote use
                if (ioState.wasRemoteCloseInitiated())
                {
                    CloseInfo close = ioState.getCloseInfo();
                    // reply to close handshake from remote
                    outgoingFrame(close.asFrame(),new OnCloseLocalCallback(new OnDisconnectCallback(true),close),BatchMode.OFF);
                }
            default:
                break;
        }
    }

    @Override
    public void onFillable()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onFillable()",policy.getBehavior());
        stats.countOnFillableEvents.incrementAndGet();
        ByteBuffer buffer = bufferPool.acquire(getInputBufferSize(),true);
        try
        {
            isFilling = true;

            if(readMode == ReadMode.PARSE)
            {
                readMode = readParse(buffer);
            } else
            {
                readMode = readDiscard(buffer);
            }
        }
        finally
        {
            bufferPool.release(buffer);
        }

        if ((readMode != ReadMode.EOF) && (suspendToken.get() == false))
        {
            fillInterested();
        }
        else
        {
            isFilling = false;
        }
    }

    

    @Override
    protected void onFillInterestedFailed(Throwable cause)
    {
        LOG.ignore(cause);
        stats.countFillInterestedEvents.incrementAndGet();
        super.onFillInterestedFailed(cause);
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        this.ioState.onOpened();
    }

    /**
     * Event for no activity on connection (read or write)
     */
    @Override
    protected boolean onReadTimeout()
    {
        IOState state = getIOState();
        ConnectionState cstate = state.getConnectionState();
        if (LOG.isDebugEnabled())
            LOG.debug("{} Read Timeout - {}",policy.getBehavior(),cstate);

        if (cstate == ConnectionState.CLOSED)
        {
            // close already completed, extra timeouts not relevant
            // allow underlying connection and endpoint to disconnect on its own
            return true;
        }

        try
        {
            session.notifyError(new SocketTimeoutException("Timeout on Read"));
        }
        finally
        {
            // This is an Abnormal Close condition
            close(StatusCode.SHUTDOWN,"Idle Timeout");
        }

        return false;
    }

    /**
     * Frame from API, User, or Internal implementation destined for network.
     */
    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback, BatchMode batchMode)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("outgoingFrame({}, {})",frame,callback);
        }

        flusher.enqueue(frame,callback,batchMode);
    }

    private ReadMode readDiscard(ByteBuffer buffer)
    {
        EndPoint endPoint = getEndPoint();
        try
        {
            while (true)
            {
                int filled = endPoint.fill(buffer);
                if (filled == 0)
                {
                    return ReadMode.DISCARD;
                }
                else if (filled < 0)
                {
                    LOG.debug("read - EOF Reached (remote: {})",getRemoteAddress());
                    return ReadMode.EOF;
                }
                else
                {
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("Discarded {} bytes - {}",filled,BufferUtil.toDetailString(buffer));
                    }
                }
            }
        }
        catch (IOException e)
        {
            LOG.ignore(e);
            return ReadMode.EOF;
        }
        catch (Throwable t)
        {
            LOG.ignore(t);
            return ReadMode.DISCARD;
        }
    }
    
    private ReadMode readParse(ByteBuffer buffer)
    {
        EndPoint endPoint = getEndPoint();
        try
        {
            while (true) // TODO: should this honor the LogicalConnection.suspend() ?
            {
                int filled = endPoint.fill(buffer);
                if (filled == 0)
                {
                    return ReadMode.PARSE;
                }
                else if (filled < 0)
                {
                    LOG.debug("read - EOF Reached (remote: {})",getRemoteAddress());
                    ioState.onReadFailure(new EOFException("Remote Read EOF"));
                    return ReadMode.EOF;
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
            close(StatusCode.PROTOCOL,e.getMessage());
            return ReadMode.DISCARD;
        }
        catch (CloseException e)
        {
            LOG.debug(e);
            close(e.getStatusCode(),e.getMessage());
            return ReadMode.DISCARD;
        }
        catch (Throwable t)
        {
            LOG.warn(t);
            close(StatusCode.ABNORMAL,t.getMessage());
            // TODO: should probably only switch to discard if a non-ws-endpoint error
            return ReadMode.DISCARD;
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
        if (inputBufferSize < MIN_BUFFER_SIZE)
        {
            throw new IllegalArgumentException("Cannot have buffer size less than " + MIN_BUFFER_SIZE);
        }
        super.setInputBufferSize(inputBufferSize);
    }

    @Override
    public void setMaxIdleTimeout(long ms)
    {
        getEndPoint().setIdleTimeout(ms);
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
    public String toString()
    {
        return String.format("%s{f=%s,g=%s,p=%s}",super.toString(),flusher,generator,parser);
    }

}
