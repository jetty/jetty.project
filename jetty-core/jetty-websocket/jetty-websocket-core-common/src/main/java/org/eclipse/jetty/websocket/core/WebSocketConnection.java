//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.core.exception.WebSocketTimeoutException;
import org.eclipse.jetty.websocket.core.internal.FrameFlusher;
import org.eclipse.jetty.websocket.core.internal.Generator;
import org.eclipse.jetty.websocket.core.internal.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides the implementation of {@link org.eclipse.jetty.io.Connection} that is suitable for WebSocket
 */
public class WebSocketConnection extends AbstractConnection implements Connection.UpgradeTo, Dumpable, Runnable
{
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketConnection.class);

    /**
     * Minimum size of a buffer is the determined to be what would be the maximum framing header size (not including payload)
     */
    private static final int MIN_BUFFER_SIZE = Generator.MAX_HEADER_LENGTH;

    private enum DemandState
    {
        DEMANDING,
        NOT_DEMANDING,
        CANCELLED
    }

    /*
     * The state of the WebSocketConnection, used to serialize fillingAndParsing with connection close events.
     * <pre>
     *
     *   OPENING <-----> IDLE   <------> FILLING_AND_PARSING  <------> MORE_FILLING_AND_PARSING
     *      |            |                 |                           |
     *      |            |                 |                           |
     *      |            v                 v                           |
     *      |          CLOSED <-------- CLOSING <----------------------+
     *      |                              ^
     *      |                              |
     *      +------------------------------+
     * </pre>
     */
    private enum State
    {
        OPENING,
        IDLE,
        FILLING_AND_PARSING,
        MORE_FILLING_AND_PARSING,
        CLOSING,
        CLOSED
    }

    private final AutoLock lock = new AutoLock();
    private final ByteBufferPool byteBufferPool;
    private final Generator generator;
    private final Parser parser;
    private final WebSocketCoreSession coreSession;
    private final Flusher flusher;
    private final Random random;
    private DemandState demand = DemandState.NOT_DEMANDING;
    private boolean fillingAndParsing = true;
    private final LongAdder messagesIn = new LongAdder();
    private final LongAdder bytesIn = new LongAdder();
    // Read / Parse variables
    private RetainableByteBuffer networkBuffer;
    private boolean useInputDirectByteBuffers;
    private boolean useOutputDirectByteBuffers;
    private State state = State.IDLE;
    private Throwable closeCause;

    /**
     * Create a WSConnection.
     * <p>
     * It is assumed that the WebSocket Upgrade Handshake has already
     * completed successfully before creating this connection.
     * </p>
     */
    public WebSocketConnection(EndPoint endp,
                               Executor executor,
                               Scheduler scheduler,
                               ByteBufferPool byteBufferPool,
                               WebSocketCoreSession coreSession)
    {
        this(endp, executor, scheduler, byteBufferPool, coreSession, null);
    }

    /**
     * Create a WSConnection.
     * <p>
     * It is assumed that the WebSocket Upgrade Handshake has already
     * completed successfully before creating this connection.
     * </p>
     * @param endp The endpoint ever which Websockot is sent/received
     * @param executor A thread executor to use for WS callbacks.
     * @param scheduler A scheduler to use for timeouts
     * @param byteBufferPool A pool of retainable buffers to use.
     * @param coreSession The WC core session to which frames are delivered.
     * @param randomMask A Random used to mask frames. If null then SecureRandom will be created if needed.
     */
    public WebSocketConnection(EndPoint endp,
                               Executor executor,
                               Scheduler scheduler,
                               ByteBufferPool byteBufferPool,
                               WebSocketCoreSession coreSession,
                               Random randomMask)
    {
        super(endp, executor);

        Objects.requireNonNull(endp, "EndPoint");
        Objects.requireNonNull(coreSession, "Session");
        Objects.requireNonNull(executor, "Executor");
        Objects.requireNonNull(byteBufferPool, "ByteBufferPool");

        this.byteBufferPool = byteBufferPool;
        this.coreSession = coreSession;
        this.generator = new Generator();
        this.parser = new Parser(byteBufferPool, coreSession);
        this.flusher = new Flusher(scheduler, coreSession.getOutputBufferSize(), generator, endp);
        this.setInputBufferSize(coreSession.getInputBufferSize());

        if (this.coreSession.getBehavior() == Behavior.CLIENT && randomMask == null)
            randomMask = new SecureRandom();
        this.random = randomMask;
    }

    @Override
    public Executor getExecutor()
    {
        return super.getExecutor();
    }

    /**
     * @return the local InetSocketAddress
     * @deprecated use {@link #getLocalSocketAddress()} instead
     */
    @Deprecated
    public InetSocketAddress getLocalAddress()
    {
        SocketAddress local = getLocalSocketAddress();
        if (local instanceof InetSocketAddress)
            return (InetSocketAddress)local;
        return null;
    }

    public SocketAddress getLocalSocketAddress()
    {
        return getEndPoint().getLocalSocketAddress();
    }

    /**
     * @return the remote InetSocketAddress
     * @deprecated use {@link #getRemoteSocketAddress()} instead
     */
    @Deprecated
    public InetSocketAddress getRemoteAddress()
    {
        SocketAddress remote = getRemoteSocketAddress();
        if (remote instanceof InetSocketAddress)
            return (InetSocketAddress)remote;
        return null;
    }

    public SocketAddress getRemoteSocketAddress()
    {
        return getEndPoint().getRemoteSocketAddress();
    }

    public boolean isUseInputDirectByteBuffers()
    {
        return useInputDirectByteBuffers;
    }

    @Deprecated(since = "12.0.21", forRemoval = true)
    public void setWriteTimeout(long writeTimeout)
    {
        flusher.setFrameWriteTimeout(writeTimeout);
    }

    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        this.useInputDirectByteBuffers = useInputDirectByteBuffers;
    }

    public boolean isUseOutputDirectByteBuffers()
    {
        return useOutputDirectByteBuffers;
    }

    public void setUseOutputDirectByteBuffers(boolean useOutputDirectByteBuffers)
    {
        this.useOutputDirectByteBuffers = useOutputDirectByteBuffers;
    }

    @Override
    public void onClose(Throwable cause)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onClose() of physical connection");
        super.onClose(cause);

        boolean close = false;
        try (AutoLock ignored = lock.lock())
        {
            closeCause = cause;
            switch (state)
            {
                case IDLE:
                {
                    state = State.CLOSED;
                    close = true;
                    break;
                }
                case OPENING:
                case FILLING_AND_PARSING:
                case MORE_FILLING_AND_PARSING:
                {
                    state = State.CLOSING;
                    break;
                }
                default:
                    throw new IllegalStateException(state.name());
            }
        }

        if (close)
            doOnClose(cause);
    }

    private void doOnClose(Throwable cause)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("doOnClose() {}", String.valueOf(cause));

        if (!coreSession.isClosed())
            coreSession.onEof();
        flusher.onClose(cause);

        if (networkBuffer != null)
        {
            networkBuffer.clear();
            releaseNetworkBuffer();
        }
    }

    @Override
    public boolean onIdleExpired(TimeoutException timeoutException)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onIdleExpired()");

        // treat as a handler error because socket is still open
        coreSession.processHandlerError(new WebSocketTimeoutException("Connection Idle Timeout", timeoutException), Callback.NOOP);
        return true;
    }

    @Override
    protected boolean onReadTimeout(TimeoutException timeout)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onReadTimeout()");

        // treat as a handler error because socket is still open
        coreSession.processHandlerError(new WebSocketTimeoutException("Timeout on Read", timeout), Callback.NOOP);
        return false;
    }

    protected void onFrame(Frame.Parsed frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onFrame({})", frame);

        final RetainableByteBuffer referenced = frame.hasPayload() && !frame.isReleaseable() ? networkBuffer : null;
        if (referenced != null)
            referenced.retain();

        coreSession.onFrame(frame, new Callback()
        {
            @Override
            public void succeeded()
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("succeeded onFrame({})", frame);

                frame.close();
                if (referenced != null)
                    referenced.release();
            }

            @Override
            public void failed(Throwable cause)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failed onFrame({}) {}", frame, cause.toString());

                frame.close();
                if (referenced != null)
                    referenced.release();

                // notify session & endpoint
                coreSession.processHandlerError(cause, NOOP);
            }
        });
    }

    private void acquireNetworkBuffer()
    {
        if (networkBuffer == null)
            networkBuffer = newNetworkBuffer(getInputBufferSize());
    }

    private void reacquireNetworkBuffer()
    {
        if (networkBuffer == null)
            throw new IllegalStateException();

        if (networkBuffer.getByteBuffer().hasRemaining())
            throw new IllegalStateException();

        networkBuffer.release();
        networkBuffer = newNetworkBuffer(getInputBufferSize());
    }

    private RetainableByteBuffer newNetworkBuffer(int capacity)
    {
        return byteBufferPool.acquire(capacity, isUseInputDirectByteBuffers());
    }

    private void releaseNetworkBuffer()
    {
        if (networkBuffer == null)
            throw new IllegalStateException();

        if (networkBuffer.hasRemaining())
            throw new IllegalStateException();

        networkBuffer.release();
        networkBuffer = null;
    }

    @Override
    public void onFillable()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onFillable()");
        fillAndParse();
    }

    @Override
    public void run()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("run()");
        fillAndParse();
    }

    public void demand()
    {
        boolean fillAndParse = false;
        try (AutoLock ignored = lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("demand {} fp={} {}", demand, fillingAndParsing, this);

            if (demand != DemandState.CANCELLED)
            {
                if (demand == DemandState.DEMANDING)
                    throw new ReadPendingException();
                demand = DemandState.DEMANDING;
            }

            if (!fillingAndParsing)
            {
                fillingAndParsing = true;
                fillAndParse = true;
            }
        }

        if (fillAndParse)
            getExecutor().execute(this);
    }

    public boolean moreDemand()
    {
        try (AutoLock ignored = lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("moreDemand? d={} fp={} {} {}", demand, fillingAndParsing, networkBuffer, this);

            if (!fillingAndParsing)
                throw new IllegalStateException();

            switch (demand)
            {
                case NOT_DEMANDING ->
                {
                    fillingAndParsing = false;
                    if (networkBuffer != null && !networkBuffer.hasRemaining())
                        releaseNetworkBuffer();
                    return false;
                }
                case DEMANDING, CANCELLED ->
                {
                    // If demand was canceled, this creates synthetic demand in order to read until EOF.
                    return true;
                }
                default -> throw new IllegalStateException(demand.name());
            }
        }
    }

    public boolean meetDemand()
    {
        try (AutoLock ignored = lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("meetDemand d={} fp={} {} {}", demand, fillingAndParsing, networkBuffer, this);

            if (demand == DemandState.NOT_DEMANDING)
                throw new IllegalStateException();
            if (!fillingAndParsing)
                throw new IllegalStateException();

            if (demand != DemandState.CANCELLED)
                demand = DemandState.NOT_DEMANDING;
            return true;
        }
    }

    public void cancelDemand()
    {
        try (AutoLock ignored = lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("cancelDemand d={} fp={} {} {}", demand, fillingAndParsing, networkBuffer, this);
            demand = DemandState.CANCELLED;
        }
    }

    private void fillAndParse()
    {
        try (AutoLock ignored = lock.lock())
        {
            switch (state)
            {
                case IDLE -> state = State.FILLING_AND_PARSING;
                case FILLING_AND_PARSING ->
                {
                    state = State.MORE_FILLING_AND_PARSING;
                    return;
                }
                case CLOSED, CLOSING ->
                {
                    return;
                }
                default -> throw new IllegalStateException(state.name());
            }
        }

        boolean fillingAndParsing = true;
        while (fillingAndParsing)
        {
            acquireNetworkBuffer();

            boolean registerFillInterested = false;
            try
            {
                while (true)
                {
                    // Parse and handle frames
                    while (networkBuffer.hasRemaining())
                    {
                        Frame.Parsed frame = parser.parse(networkBuffer.getByteBuffer());
                        if (frame == null)
                            break;

                        messagesIn.increment();

                        if (meetDemand())
                            onFrame(frame);

                        if (!moreDemand())
                            return;
                    }

                    // buffer must be empty here because parser is fully consuming
                    assert (!networkBuffer.hasRemaining());

                    if (!getEndPoint().isOpen())
                    {
                        releaseNetworkBuffer();
                        return;
                    }

                    // If more references that 1(us), don't refill into buffer and risk compaction.
                    if (networkBuffer.isRetained())
                        reacquireNetworkBuffer();

                    // The parser is fully consuming so we can clear the network buffer before filling.
                    networkBuffer.clear();
                    int filled = getEndPoint().fill(networkBuffer.getByteBuffer());

                    if (LOG.isDebugEnabled())
                        LOG.debug("endpointFill() filled={}: {}", filled, networkBuffer);

                    if (filled < 0)
                    {
                        releaseNetworkBuffer();
                        coreSession.onEof();
                        return;
                    }

                    if (filled == 0)
                    {
                        releaseNetworkBuffer();
                        registerFillInterested = true;
                        return;
                    }

                    bytesIn.add(filled);
                }
            }
            catch (Throwable t)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Error during fillAndParse() {}", t.toString());

                if (networkBuffer != null)
                {
                    BufferUtil.clear(networkBuffer.getByteBuffer());
                    releaseNetworkBuffer();
                }
                coreSession.processConnectionError(t, Callback.NOOP);
            }
            finally
            {
                fillingAndParsing = false;
                boolean close = false;
                Throwable closeCause = null;
                try (AutoLock ignored = lock.lock())
                {
                    switch (state)
                    {
                        case MORE_FILLING_AND_PARSING ->
                        {
                            if (registerFillInterested)
                            {
                                // We had no content to read, so no point looping around again.
                                state = State.IDLE;
                            }
                            else
                            {
                                fillingAndParsing = true;
                                state = State.FILLING_AND_PARSING;
                            }
                        }
                        case FILLING_AND_PARSING -> state = State.IDLE;
                        case CLOSING ->
                        {
                            state = State.CLOSED;
                            close = true;
                            closeCause = this.closeCause;
                        }
                        default -> throw new IllegalStateException(state.name());
                    }
                }
                if (close)
                    doOnClose(closeCause);
                else if (registerFillInterested)
                    fillInterested();
            }
        }
    }

    /**
     * Extra bytes from the initial HTTP upgrade that need to
     * be processed by the websocket parser before starting
     * to read bytes from the connection
     *
     * @param initialBuffer the bytes of extra content encountered during upgrade
     */
    protected void setInitialBuffer(ByteBuffer initialBuffer)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Set initial buffer - {}", BufferUtil.toDetailString(initialBuffer));
        networkBuffer = newNetworkBuffer(initialBuffer.remaining());
        ByteBuffer buffer = networkBuffer.getByteBuffer();
        BufferUtil.clearToFill(buffer);
        BufferUtil.put(initialBuffer, buffer);
        BufferUtil.flipToFlush(buffer, 0);
    }

    @Override
    public void onOpen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onOpen() {}", this);

        try (AutoLock ignored = lock.lock())
        {
            switch (state)
            {
                case IDLE -> state = State.OPENING;
                case CLOSED ->
                {
                    // Nothing to do.
                    return;
                }
                default -> throw new IllegalStateException(state.name());
            }
        }

        super.onOpen();
        coreSession.onOpen();

        boolean close = false;
        Throwable closeCause = null;
        try (AutoLock ignored = lock.lock())
        {
            switch (state)
            {
                case OPENING -> state = State.IDLE;
                case CLOSING ->
                {
                    state = State.CLOSED;
                    close = true;
                    closeCause = this.closeCause;
                }
                default -> throw new IllegalStateException(state.name());
            }
        }

        if (close)
            doOnClose(closeCause);
        else if (moreDemand())
            fillAndParse();
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
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this);
    }

    @Override
    public String toConnectionString()
    {
        return String.format("%s@%x[%s,p=%s,f=%s,g=%s]",
            getClass().getSimpleName(),
            hashCode(),
            coreSession.getBehavior(),
            parser,
            flusher,
            generator);
    }

    /**
     * Extra bytes from the initial HTTP upgrade that need to
     * be processed by the websocket parser before starting
     * to read bytes from the connection
     *
     * @param buffer a non-null buffer of extra bytes
     */
    @Override
    public void onUpgradeTo(ByteBuffer buffer)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onUpgradeTo({})", BufferUtil.toDetailString(buffer));
        setInitialBuffer(buffer);
    }

    @Override
    public long getMessagesIn()
    {
        return messagesIn.longValue();
    }

    @Override
    public long getBytesIn()
    {
        return bytesIn.longValue();
    }

    @Override
    public long getMessagesOut()
    {
        return flusher.getMessagesOut();
    }

    @Override
    public long getBytesOut()
    {
        return flusher.getBytesOut();
    }

    /**
     * Enqueue a Frame to be sent.
     *
     * @param frame The frame to queue
     * @param callback The callback to call once the frame is sent
     * @param batch True if batch mode is to be used
     */
    public void enqueueFrame(Frame frame, Callback callback, boolean batch)
    {
        if (coreSession.getBehavior() == Behavior.CLIENT)
        {
            byte[] mask = new byte[4];
            random.nextBytes(mask);
            frame.setMask(mask);
        }

        if (flusher.enqueue(frame, callback, batch))
            flusher.iterate();
    }

    private class Flusher extends FrameFlusher
    {
        private Flusher(Scheduler scheduler, int bufferSize, Generator generator, EndPoint endpoint)
        {
            super(byteBufferPool, scheduler, generator, endpoint, bufferSize, 8);
            setUseDirectByteBuffers(isUseOutputDirectByteBuffers());
        }

        @Override
        public void onCompleteFailure(Throwable x)
        {
            coreSession.processConnectionError(x, NOOP);
            super.onCompleteFailure(x);
        }
    }
}

