//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.internal;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.WebSocketTimeoutException;

/**
 * Provides the implementation of {@link org.eclipse.jetty.io.Connection} that is suitable for WebSocket
 */
public class WebSocketConnection extends AbstractConnection implements Connection.UpgradeTo, Dumpable, Runnable
{
    private static final Logger LOG = Log.getLogger(WebSocketConnection.class);

    /**
     * Minimum size of a buffer is the determined to be what would be the maximum framing header size (not including payload)
     */
    private static final int MIN_BUFFER_SIZE = Generator.MAX_HEADER_LENGTH;

    private final ByteBufferPool bufferPool;
    private final Generator generator;
    private final Parser parser;
    private final WebSocketCoreSession coreSession;

    private final Flusher flusher;
    private final Random random;

    private long demand;
    private boolean fillingAndParsing;
    private LongAdder messagesIn = new LongAdder();
    private LongAdder bytesIn = new LongAdder();

    // Read / Parse variables
    private RetainableByteBuffer networkBuffer;

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
                               ByteBufferPool bufferPool,
                               WebSocketCoreSession coreSession)
    {
        super(endp, executor);

        Objects.requireNonNull(endp, "EndPoint");
        Objects.requireNonNull(coreSession, "Session");
        Objects.requireNonNull(executor, "Executor");
        Objects.requireNonNull(bufferPool, "ByteBufferPool");

        this.bufferPool = bufferPool;

        this.coreSession = coreSession;

        this.generator = new Generator(bufferPool);
        this.parser = new Parser(bufferPool, coreSession);
        this.flusher = new Flusher(scheduler, coreSession.getOutputBufferSize(), generator, endp);
        this.setInputBufferSize(coreSession.getInputBufferSize());

        this.random = this.coreSession.getBehavior() == Behavior.CLIENT ? new Random(endp.hashCode()) : null;
    }

    @Override
    public Executor getExecutor()
    {
        return super.getExecutor();
    }

    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    public Generator getGenerator()
    {
        return generator;
    }

    public Parser getParser()
    {
        return parser;
    }

    public InetSocketAddress getLocalAddress()
    {
        return getEndPoint().getLocalAddress();
    }

    public InetSocketAddress getRemoteAddress()
    {
        return getEndPoint().getRemoteAddress();
    }

    /**
     * Physical connection disconnect.
     * <p>
     * Not related to WebSocket close handshake.
     */
    @Override
    public void onClose(Throwable cause)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onClose() of physical connection");

        if (!coreSession.isClosed())
            coreSession.onEof();
        flusher.onClose(cause);
        super.onClose(cause);
    }

    @Override
    public boolean onIdleExpired()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onIdleExpired()");

        // treat as a handler error because socket is still open
        coreSession.processHandlerError(new WebSocketTimeoutException("Connection Idle Timeout"), Callback.NOOP);
        return true;
    }

    /**
     * Event for no activity on connection (read or write)
     *
     * @return true to signal that the endpoint must be closed, false to keep the endpoint open
     */
    @Override
    protected boolean onReadTimeout(Throwable timeout)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onReadTimeout()");

        // treat as a handler error because socket is still open
        coreSession.processHandlerError(new WebSocketTimeoutException("Timeout on Read", timeout), Callback.NOOP);
        return false;
    }

    protected void onFrame(Parser.ParsedFrame frame)
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

                if (!coreSession.isDemanding())
                    demand(1);
            }

            @Override
            public void failed(Throwable cause)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failed onFrame(" + frame + ")", cause);

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
        synchronized (this)
        {
            if (networkBuffer == null)
                networkBuffer = new RetainableByteBuffer(bufferPool, getInputBufferSize());
        }
    }

    private void reacquireNetworkBuffer()
    {
        synchronized (this)
        {
            if (networkBuffer == null)
                throw new IllegalStateException();

            if (networkBuffer.getBuffer().hasRemaining())
                throw new IllegalStateException();

            networkBuffer.release();
            networkBuffer = new RetainableByteBuffer(bufferPool, getInputBufferSize());
        }
    }

    private void releaseNetworkBuffer()
    {
        synchronized (this)
        {
            if (networkBuffer == null)
                throw new IllegalStateException();

            if (networkBuffer.hasRemaining())
                throw new IllegalStateException();

            networkBuffer.release();
            networkBuffer = null;
        }
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

    public void demand(long n)
    {
        if (n <= 0)
            throw new IllegalArgumentException("Demand must be positive");

        boolean fillAndParse = false;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("demand {} d={} fp={} {} {}", n, demand, fillingAndParsing, networkBuffer, this);

            if (demand < 0)
                return;

            try
            {
                demand = Math.addExact(demand, n);
            }
            catch (ArithmeticException e)
            {
                demand = Long.MAX_VALUE;
            }

            if (!fillingAndParsing)
            {
                fillingAndParsing = true;
                fillAndParse = true;
            }
        }

        if (fillAndParse)
        {
            // TODO can we just fillAndParse();
            getExecutor().execute(this);
        }
    }

    public boolean moreDemand()
    {
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("moreDemand? d={} fp={} {} {}", demand, fillingAndParsing, networkBuffer, this);

            if (!fillingAndParsing)
                throw new IllegalStateException();
            if (demand != 0) //if demand was canceled, this creates synthetic demand in order to read until EOF
                return true;

            fillingAndParsing = false;
            if (networkBuffer.isEmpty())
                releaseNetworkBuffer();

            return false;
        }
    }

    public boolean meetDemand()
    {
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("meetDemand d={} fp={} {} {}", demand, fillingAndParsing, networkBuffer, this);

            if (demand == 0)
                throw new IllegalStateException();
            if (!fillingAndParsing)
                throw new IllegalStateException();

            if (demand > 0)
                demand--;

            return true;
        }
    }

    public void cancelDemand()
    {
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("cancelDemand d={} fp={} {} {}", demand, fillingAndParsing, networkBuffer, this);

            demand = -1;
        }
    }

    private void fillAndParse()
    {
        acquireNetworkBuffer();

        try
        {
            while (true)
            {
                // Parse and handle frames
                while (!networkBuffer.isEmpty())
                {
                    Parser.ParsedFrame frame = parser.parse(networkBuffer.getBuffer());
                    if (frame == null)
                        break;

                    messagesIn.increment();

                    if (meetDemand())
                        onFrame(frame);

                    if (!moreDemand())
                        return;
                }

                // buffer must be empty here because parser is fully consuming
                assert (networkBuffer.isEmpty());

                if (!getEndPoint().isOpen())
                {
                    releaseNetworkBuffer();
                    return;
                }

                // If more references that 1(us), don't refill into buffer and risk compaction.
                if (networkBuffer.getReferences() > 1)
                    reacquireNetworkBuffer();

                int filled = getEndPoint().fill(networkBuffer.getBuffer()); // TODO check if compact is possible.

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
                    fillInterested();
                    return;
                }

                bytesIn.add(filled);
            }
        }
        catch (Throwable t)
        {
            LOG.warn(t.toString());
            BufferUtil.clear(networkBuffer.getBuffer());
            releaseNetworkBuffer();
            coreSession.processConnectionError(t, Callback.NOOP);
        }
    }

    /**
     * Extra bytes from the initial HTTP upgrade that need to
     * be processed by the websocket parser before starting
     * to read bytes from the connection
     *
     * @param prefilled the bytes of prefilled content encountered during upgrade
     */
    protected void setInitialBuffer(ByteBuffer prefilled)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("set Initial Buffer - {}", BufferUtil.toDetailString(prefilled));
        }

        if ((prefilled != null) && (prefilled.hasRemaining()))
        {
            synchronized (this)
            {
                networkBuffer = new RetainableByteBuffer(bufferPool, prefilled.remaining());
            }
            ByteBuffer buffer = networkBuffer.getBuffer();
            BufferUtil.clearToFill(buffer);
            BufferUtil.put(prefilled, buffer);
            BufferUtil.flipToFlush(buffer, 0);
        }
    }

    /**
     * Physical connection Open.
     */
    @Override
    public void onOpen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onOpen() {}", this);

        // Open Session
        super.onOpen();
        coreSession.onOpen();
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
     */
    @Override
    public void onUpgradeTo(ByteBuffer prefilled)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onUpgradeTo({})", BufferUtil.toDetailString(prefilled));
        }

        setInitialBuffer(prefilled);
    }

    public FrameFlusher getFrameFlusher()
    {
        return flusher;
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
    void enqueueFrame(Frame frame, Callback callback, boolean batch)
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
            super(bufferPool, scheduler, generator, endpoint, bufferSize, 8);
        }

        @Override
        public void onCompleteFailure(Throwable x)
        {
            coreSession.processConnectionError(x, NOOP);
            super.onCompleteFailure(x);
        }
    }
}
