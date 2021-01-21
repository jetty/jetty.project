//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.CloseException;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.frames.CloseFrame;

import static org.eclipse.jetty.websocket.api.WebSocketBehavior.SERVER;

/**
 * Provides the implementation of {@link LogicalConnection} within the framework of the new {@link org.eclipse.jetty.io.Connection} framework of {@code jetty-io}.
 */
public abstract class AbstractWebSocketConnection extends AbstractConnection implements LogicalConnection, Connection.UpgradeTo, Dumpable
{

    private static class CallbackBridge implements WriteCallback
    {
        private final Callback callback;

        public CallbackBridge(Callback callback)
        {
            this.callback = callback != null ? callback : Callback.NOOP;
        }

        @Override
        public void writeFailed(Throwable x)
        {
            callback.failed(x);
        }

        @Override
        public void writeSuccess()
        {
            callback.succeeded();
        }
    }

    private class Flusher extends FrameFlusher
    {
        private Flusher(ByteBufferPool bufferPool, Generator generator, EndPoint endpoint)
        {
            super(bufferPool, generator, endpoint, getPolicy().getMaxBinaryMessageBufferSize(), 8);
        }

        @Override
        public void onCompleteFailure(Throwable failure)
        {
            AbstractWebSocketConnection.this.close(failure);
            super.onCompleteFailure(failure);
        }
    }

    @Deprecated
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

    private static final Logger LOG = Log.getLogger(AbstractWebSocketConnection.class);
    private static final AtomicLong ID_GEN = new AtomicLong(0);

    /**
     * Minimum size of a buffer is the determined to be what would be the maximum framing header size (not including payload)
     */
    private static final int MIN_BUFFER_SIZE = Generator.MAX_HEADER_LENGTH;

    private final ByteBufferPool bufferPool;
    private final Scheduler scheduler;
    private final Generator generator;
    private final Parser parser;
    private final WebSocketPolicy policy;
    private final ReadState readState = new ReadState();
    private final ConnectionState connectionState = new ConnectionState();
    private final FrameFlusher flusher;
    private final String id;
    private final LongAdder bytesIn = new LongAdder();
    private WebSocketSession session;
    private List<ExtensionConfig> extensions = new ArrayList<>();
    private ByteBuffer initialBuffer;
    private Stats stats = new Stats();
    private CloseInfo fatalCloseInfo;

    public AbstractWebSocketConnection(EndPoint endp, Executor executor, Scheduler scheduler, WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        super(endp, executor);

        this.id = Long.toString(ID_GEN.incrementAndGet());
        this.policy = policy;
        this.bufferPool = bufferPool;
        this.generator = new Generator(policy, bufferPool);
        this.parser = new Parser(policy, bufferPool);
        this.scheduler = scheduler;
        this.flusher = new Flusher(bufferPool, generator, endp);
        this.setInputBufferSize(policy.getInputBufferSize());
        this.setMaxIdleTimeout(policy.getIdleTimeout());
    }

    @Override
    public Executor getExecutor()
    {
        return super.getExecutor();
    }

    public void close(CloseInfo close, final Callback callback)
    {
        if (connectionState.closing())
        {
            boolean transmit = close.getStatusCode() == StatusCode.NO_CODE || StatusCode.isTransmittable(close.getStatusCode());
            if (transmit)
            {
                CloseFrame frame = close.asFrame();
                outgoingFrame(frame, new CallbackBridge(callback), BatchMode.OFF);

                if (StatusCode.isFatal(close.getStatusCode()))
                {
                    fatalCloseInfo = close;
                }
            }
            else
            {
                disconnect();
            }
        }
        else
        {
            if (callback != null)
            {
                callback.failed(new IllegalStateException("Local Close already called"));
            }
        }
    }

    /**
     * Close the connection based on the throwable
     *
     * @param cause the cause
     */
    public void close(Throwable cause)
    {
        session.callApplicationOnError(cause);

        int statusCode = policy.getBehavior() == SERVER ? StatusCode.SERVER_ERROR : StatusCode.ABNORMAL;

        if (cause instanceof CloseException)
        {
            statusCode = ((CloseException)cause).getStatusCode();
        }
        String reason = cause.getMessage();
        if (StringUtil.isBlank(reason))
        {
            // an exception without a message.
            reason = cause.getClass().getSimpleName();
        }

        CloseInfo closeInfo = new CloseInfo(statusCode, reason);
        session.callApplicationOnClose(closeInfo);
        close(closeInfo, new DisconnectCallback(this));
    }

    @Override
    public boolean canWriteWebSocketFrames()
    {
        return connectionState.canWriteWebSocketFrames();
    }

    @Override
    public boolean canReadWebSocketFrames()
    {
        return connectionState.canReadWebSocketFrames();
    }

    @Override
    public String toStateString()
    {
        return connectionState.toString();
    }

    @Override
    public boolean opening()
    {
        return connectionState.opening();
    }

    @Override
    public boolean opened()
    {
        if (connectionState.opened())
        {
            if (BufferUtil.hasContent(initialBuffer))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Parsing upgrade initial buffer ({} remaining)", initialBuffer.remaining());
                parser.parse(initialBuffer);
                initialBuffer = null;
            }
            fillInterested();
            return true;
        }
        return false;
    }

    @Override
    public void remoteClose(CloseInfo close)
    {
        session.callApplicationOnClose(close);
        close(close, new DisconnectCallback(this));
    }

    @Override
    public void setSession(WebSocketSession session)
    {
        this.session = session;
    }

    @Override
    public boolean onIdleExpired()
    {
        // TODO: handle closing handshake (see HTTP2Connection).
        return super.onIdleExpired();
    }

    /**
     * Jetty Connection Close
     */
    @Override
    public void close()
    {
        close(new CloseInfo(), Callback.NOOP);
    }

    @Override
    public void disconnect()
    {
        if (connectionState.disconnected())
        {
            if (connectionState.wasOpened())
            {
                /* Use prior Fatal Close Info if present, otherwise
                 * because if could be from a failed close handshake where
                 * the local initiated, but the remote never responded.
                 */
                CloseInfo closeInfo = fatalCloseInfo;
                if (closeInfo == null)
                {
                    closeInfo = new CloseInfo(StatusCode.ABNORMAL, "Disconnected");
                }
                session.callApplicationOnClose(closeInfo);
            }
            else
            {
                session.callApplicationOnError(new WebSocketException("Shutdown"));
            }

            if (LOG.isDebugEnabled())
            {
                LOG.debug("{} disconnect()", policy.getBehavior());
            }
            flusher.terminate(new EOFException("Disconnected"));
            EndPoint endPoint = getEndPoint();
            // We need to gently close first, to allow
            // SSL close alerts to be sent by Jetty
            endPoint.shutdownOutput();
            endPoint.close();
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
    public InetSocketAddress getLocalAddress()
    {
        return getEndPoint().getLocalAddress();
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

    @Deprecated()
    public Stats getStats()
    {
        return stats;
    }

    @Override
    public boolean isOpen()
    {
        return getEndPoint().isOpen();
    }

    @Override
    public boolean isReading()
    {
        return readState.isReading();
    }

    @Override
    public void onFillable()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onFillable()", policy.getBehavior());

        stats.countOnFillableEvents.incrementAndGet();
        if (readState.getBuffer() != null)
            throw new IllegalStateException();
        ByteBuffer buffer = bufferPool.acquire(getInputBufferSize(), true);
        onFillable(buffer);
    }

    private void onFillable(ByteBuffer buffer)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onFillable(ByteBuffer): {}", policy.getBehavior(), buffer);

        while (true)
        {
            ReadState.Action action = readState.getAction(buffer);
            if (LOG.isDebugEnabled())
                LOG.debug("ReadState Action: {}", action);

            switch (action)
            {
                case PARSE:
                    try
                    {
                        parser.parseSingleFrame(buffer);
                    }
                    catch (Throwable t)
                    {
                        close(t);
                        readState.discard();
                    }
                    break;

                case FILL:
                    try
                    {
                        int filled = getEndPoint().fill(buffer);
                        if (filled < 0)
                        {
                            readState.eof();
                            break;
                        }
                        if (filled == 0)
                        {
                            // Done reading, wait for next onFillable
                            bufferPool.release(buffer);
                            fillInterested();
                            return;
                        }

                        bytesIn.add(filled);
                        if (LOG.isDebugEnabled())
                            LOG.debug("Filled {} bytes - {}", filled, BufferUtil.toDetailString(buffer));
                    }
                    catch (IOException e)
                    {
                        close(e);
                        readState.eof();
                    }
                    break;

                case DISCARD:
                    if (LOG.isDebugEnabled())
                        LOG.debug("Discarded buffer - {}", BufferUtil.toDetailString(buffer));
                    BufferUtil.clear(buffer);
                    break;

                case SUSPEND:
                    return;

                case EOF:
                    bufferPool.release(buffer);

                    // Handle case where the remote connection was abruptly terminated without a close frame
                    CloseInfo close = new CloseInfo(StatusCode.SHUTDOWN);
                    close(close, new DisconnectCallback(this));
                    return;

                default:
                    throw new IllegalStateException(action.name());
            }
        }
    }

    @Override
    public void resume()
    {
        ByteBuffer resume = readState.resume();
        if (resume != null)
            getExecutor().execute(() -> onFillable(resume));
    }

    @Override
    public SuspendToken suspend()
    {
        readState.suspending();
        return this;
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
     *
     * @param initialBuffer the bytes of unconsumed content encountered during upgrade
     */
    protected void setInitialBuffer(ByteBuffer initialBuffer)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Set initial buffer - {}", BufferUtil.toDetailString(initialBuffer));
        this.initialBuffer = initialBuffer;
    }

    /**
     * Event for no activity on connection (read or write)
     *
     * @return true to signal that the endpoint must be closed, false to keep the endpoint open
     */
    @Override
    protected boolean onReadTimeout(Throwable timeout)
    {
        close(new CloseException(StatusCode.SHUTDOWN, timeout));
        return false; // let websocket perform close handshake
    }

    /**
     * Frame from API, User, or Internal implementation destined for network.
     */
    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback, BatchMode batchMode)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("outgoingFrame({}, {})", frame, callback);
        }

        if (flusher.enqueue(frame, callback, batchMode))
        {
            flusher.iterate();
        }
    }

    /**
     * Get the list of extensions in use.
     * <p>
     * This list is negotiated during the WebSocket Upgrade Request/Response handshake.
     *
     * @param extensions the list of negotiated extensions in use.
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
    public String dumpSelf()
    {
        return String.format("%s@%x", this.getClass().getSimpleName(), hashCode());
    }

    public void dump(Appendable out, String indent) throws IOException
    {
        EndPoint endp = getEndPoint();
        Object endpRef = endp.toString();
        if (endp instanceof AbstractEndPoint)
        {
            endpRef = ((AbstractEndPoint)endp).toEndPointString();
        }
        Dumpable.dumpObjects(out, indent, this, endpRef, flusher, generator, parser);
    }

    @Override
    public String toConnectionString()
    {
        return String.format("%s@%x[s=%s,f=%s,g=%s,p=%s]",
            getClass().getSimpleName(),
            hashCode(),
            connectionState,
            flusher, generator, parser);
    }

    /**
     * Extra bytes from the initial HTTP upgrade that need to
     * be processed by the websocket parser before starting
     * to read bytes from the connection
     */
    @Override
    public void onUpgradeTo(ByteBuffer buffer)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onUpgradeTo({})", BufferUtil.toDetailString(buffer));
        setInitialBuffer(buffer);
    }

    @Override
    public void setNextIncomingFrames(IncomingFrames incoming)
    {
        getParser().setIncomingFramesHandler(incoming);
    }

    /**
     * @return the number of WebSocket frames received over this connection
     */
    @Override
    public long getMessagesIn()
    {
        return parser.getMessagesIn();
    }

    /**
     * @return the number of WebSocket frames sent over this connection
     */
    @Override
    public long getMessagesOut()
    {
        return flusher.getMessagesOut();
    }

    /**
     * @return the number of bytes received over this connection
     */
    @Override
    public long getBytesIn()
    {
        return bytesIn.longValue();
    }

    /**
     * @return the number of bytes frames sent over this connection
     */
    @Override
    public long getBytesOut()
    {
        return flusher.getBytesOut();
    }
}
