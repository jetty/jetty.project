//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.io.Connection;
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
import org.eclipse.jetty.websocket.common.io.IOState.ConnectionStateListener;

/**
 * Provides the implementation of {@link LogicalConnection} within the framework of the new {@link org.eclipse.jetty.io.Connection} framework of {@code jetty-io}.
 */
public abstract class AbstractWebSocketConnection extends AbstractConnection implements LogicalConnection, Connection.UpgradeTo, ConnectionStateListener, Dumpable
{
    private final AtomicBoolean closed = new AtomicBoolean();

    private class Flusher extends FrameFlusher
    {
        private Flusher(ByteBufferPool bufferPool, Generator generator, EndPoint endpoint)
        {
            super(bufferPool,generator,endpoint,getPolicy().getMaxBinaryMessageBufferSize(),8);
        }

        @Override
        protected void onFailure(Throwable x)
        {
            notifyError(x);

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
            if (LOG_CLOSE.isDebugEnabled())
                LOG_CLOSE.debug("Local Close Confirmed {}",close);
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
    private static final Logger LOG_OPEN = Log.getLogger(AbstractWebSocketConnection.class.getName() + "_OPEN");
    private static final Logger LOG_CLOSE = Log.getLogger(AbstractWebSocketConnection.class.getName() + "_CLOSE");

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
    private final String id;
    private List<ExtensionConfig> extensions;
    private boolean isFilling;
    private ByteBuffer prefillBuffer;
    private ReadMode readMode = ReadMode.PARSE;
    private IOState ioState;
    private Stats stats = new Stats();

    public AbstractWebSocketConnection(EndPoint endp, Executor executor, Scheduler scheduler, WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        super(endp,executor);
        this.id = String.format("%s:%d->%s:%d",
                endp.getLocalAddress().getAddress().getHostAddress(),
                endp.getLocalAddress().getPort(),
                endp.getRemoteAddress().getAddress().getHostAddress(),
                endp.getRemoteAddress().getPort());
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

    /**
     * Close without a close code or reason
     */
    @Override
    public void close()
    {
        if (LOG_CLOSE.isDebugEnabled())
            LOG_CLOSE.debug("close()");
        close(new CloseInfo());
    }

    /**
     * Close the connection.
     * <p>                    fillInterested();

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
        if (LOG_CLOSE.isDebugEnabled())
            LOG_CLOSE.debug("close({},{})", statusCode, reason);
        close(new CloseInfo(statusCode, reason));
    }

    private void close(CloseInfo closeInfo)
    {
        if (closed.compareAndSet(false, true))
            outgoingFrame(closeInfo.asFrame(), new OnCloseLocalCallback(closeInfo), BatchMode.OFF);
    }

    @Override
    public void disconnect()
    {
        if (LOG_CLOSE.isDebugEnabled())
            LOG_CLOSE.debug("{} disconnect()",policy.getBehavior());
        disconnect(false);
    }

    private void disconnect(boolean onlyOutput)
    {
        if (LOG_CLOSE.isDebugEnabled())
            LOG_CLOSE.debug("{} disconnect({})",policy.getBehavior(),onlyOutput?"outputOnly":"both");
        // close FrameFlusher, we cannot write anymore at this point.
        flusher.close();
        EndPoint endPoint = getEndPoint();
        // We need to gently close first, to allow
        // SSL close alerts to be sent by Jetty
        if (LOG_CLOSE.isDebugEnabled())
            LOG_CLOSE.debug("Shutting down output {}",endPoint);
        endPoint.shutdownOutput();
        if (!onlyOutput)
        {
            if (LOG_CLOSE.isDebugEnabled())
                LOG_CLOSE.debug("Closing {}",endPoint);
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

    public Stats getStats()
    {
        return stats;
    }

    @Override
    public boolean isOpen()
    {
        return !closed.get();
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
        ioState.onDisconnected();
        flusher.close();
    }

    @Override
    public void onConnectionStateChange(ConnectionState state)
    {
        if (LOG_CLOSE.isDebugEnabled())
            LOG_CLOSE.debug("{} Connection State Change: {}",policy.getBehavior(),state);

        switch (state)
        {
            case OPEN:
                if (BufferUtil.hasContent(prefillBuffer))
                {
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("Parsing Upgrade prefill buffer ({} remaining)",prefillBuffer.remaining());
                    }
                    parser.parse(prefillBuffer);
                }
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("OPEN: normal fillInterested");
                }
                // TODO: investigate what happens if a failure occurs during prefill, and an attempt to write close fails,
                // should a fill interested occur? or just a quick disconnect?
                fillInterested();
                break;
            case CLOSED:
                if (LOG_CLOSE.isDebugEnabled())
                    LOG_CLOSE.debug("CLOSED - wasAbnormalClose: {}", ioState.wasAbnormalClose());
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
                if (LOG_CLOSE.isDebugEnabled())
                    LOG_CLOSE.debug("CLOSING - wasRemoteCloseInitiated: {}", ioState.wasRemoteCloseInitiated());
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
            }
            else
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
        prefillBuffer = prefilled;
    }

    private void notifyError(Throwable t)
    {
        getParser().getIncomingFramesHandler().incomingError(t);
    }

    @Override
    public void onOpen()
    {
        if(LOG_OPEN.isDebugEnabled())
            LOG_OPEN.debug("[{}] {}.onOpened()",policy.getBehavior(),this.getClass().getSimpleName());
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
        if (LOG_CLOSE.isDebugEnabled())
            LOG_CLOSE.debug("{} Read Timeout - {}",policy.getBehavior(),cstate);

        if (cstate == ConnectionState.CLOSED)
        {
            if (LOG_CLOSE.isDebugEnabled())
                LOG_CLOSE.debug("onReadTimeout - Connection Already CLOSED");
            // close already completed, extra timeouts not relevant
            // allow underlying connection and endpoint to disconnect on its own
            return true;
        }

        try
        {
            notifyError(new SocketTimeoutException("Timeout on Read"));
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
                    if (LOG_CLOSE.isDebugEnabled())
                        LOG_CLOSE.debug("read - EOF Reached (remote: {})",getRemoteAddress());
                    return ReadMode.EOF;
                }
                else
                {
                    if (LOG_CLOSE.isDebugEnabled())
                        LOG_CLOSE.debug("Discarded {} bytes - {}",filled,BufferUtil.toDetailString(buffer));
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
            // Process the content from the Endpoint next
            while(true)  // TODO: should this honor the LogicalConnection.suspend() ?
            {
                int filled = endPoint.fill(buffer);
                if (filled < 0)
                {
                    LOG.debug("read - EOF Reached (remote: {})",getRemoteAddress());
                    ioState.onReadFailure(new EOFException("Remote Read EOF"));
                    return ReadMode.EOF;
                }
                else if (filled == 0)
                {
                    // Done reading, wait for next onFillable
                    return ReadMode.PARSE;
                }

                if (LOG.isDebugEnabled())
                {
                    LOG.debug("Filled {} bytes - {}",filled,BufferUtil.toDetailString(buffer));
                }
                parser.parse(buffer);
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
        return String.format("%s@%x[ios=%s,f=%s,g=%s,p=%s]",
                getClass().getSimpleName(),
                hashCode(),
                ioState,flusher,generator,parser);
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
