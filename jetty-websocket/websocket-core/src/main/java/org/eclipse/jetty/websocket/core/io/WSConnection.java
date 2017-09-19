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

package org.eclipse.jetty.websocket.core.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.Generator;
import org.eclipse.jetty.websocket.core.IncomingFrames;
import org.eclipse.jetty.websocket.core.OutgoingFrames;
import org.eclipse.jetty.websocket.core.Parser;
import org.eclipse.jetty.websocket.core.WSCoreSession;
import org.eclipse.jetty.websocket.core.WSException;
import org.eclipse.jetty.websocket.core.WSPolicy;
import org.eclipse.jetty.websocket.core.WSTimeoutException;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.handshake.UpgradeRequest;
import org.eclipse.jetty.websocket.core.handshake.UpgradeResponse;

/**
 * Provides the implementation of {@link org.eclipse.jetty.io.Connection} that is suitable for WebSocket
 */
public class WSConnection extends AbstractConnection implements Parser.Handler, IncomingFrames, OutgoingFrames, SuspendToken, Connection.UpgradeTo, Dumpable
{
    private class Flusher extends FrameFlusher
    {
        private Flusher(int bufferSize, Generator generator, EndPoint endpoint)
        {
            super(generator, endpoint, bufferSize, 8);
        }

        @Override
        protected void onFailure(Throwable x)
        {
            session.onError(x);
        }
    }

    /**
     * Minimum size of a buffer is the determined to be what would be the maximum framing header size (not including payload)
     */
    private static final int MIN_BUFFER_SIZE = Generator.MAX_HEADER_LENGTH;

    private final Logger LOG;
    private final ByteBufferPool bufferPool;
    private final Generator generator;
    private final Parser parser;
    // Connection level policy (before the session and local endpoint has been created)
    private final WSPolicy policy;
    private final AtomicBoolean suspendToken;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final FrameFlusher flusher;
    private final ExtensionStack extensionStack;
    private final String id;
    private final UpgradeRequest upgradeRequest;
    private final UpgradeResponse upgradeResponse;
    private final WSConnectionState connectionState = new WSConnectionState();
    private final AtomicBoolean closeSent = new AtomicBoolean(false);
    private final DecoratedObjectFactory objectFactory;

    private WSCoreSession session;
    // Path for frames in/out of the connection
    protected OutgoingFrames outgoingFrames;
    protected IncomingFrames incomingFrames;
    // Read / Parse variables
    private AtomicBoolean fillAndParseScope = new AtomicBoolean(false);
    private ByteBuffer networkBuffer;

    /**
     * Create a WSConnection.
     * <p>
     * It is assumed that the WebSocket Upgrade Handshake has already
     * completed successfully before creating this connection.
     * </p>
     */
    public WSConnection(EndPoint endp, Executor executor, ByteBufferPool bufferPool,
                        DecoratedObjectFactory decoratedObjectFactory,
                        WSPolicy policy, ExtensionStack extensionStack,
                        UpgradeRequest upgradeRequest, UpgradeResponse upgradeResponse)
    {
        super(endp, executor);

        Objects.requireNonNull(endp, "EndPoint");
        Objects.requireNonNull(executor, "Executor");
        Objects.requireNonNull(bufferPool, "ByteBufferPool");
        Objects.requireNonNull(decoratedObjectFactory, "DecoratedObjectFactory");
        Objects.requireNonNull(policy, "WSPolicy");
        Objects.requireNonNull(extensionStack, "ExtensionStack");
        Objects.requireNonNull(upgradeRequest, "UpgradeRequest");
        Objects.requireNonNull(upgradeResponse, "UpgradeResponse");

        LOG = Log.getLogger(this.getClass());
        this.bufferPool = bufferPool;
        this.objectFactory = decoratedObjectFactory;

        this.id = String.format("%s:%d->%s:%d",
                endp.getLocalAddress().getAddress().getHostAddress(),
                endp.getLocalAddress().getPort(),
                endp.getRemoteAddress().getAddress().getHostAddress(),
                endp.getRemoteAddress().getPort());
        this.upgradeRequest = upgradeRequest;
        this.upgradeResponse = upgradeResponse;

        this.policy = policy;
        this.extensionStack = extensionStack;

        this.generator = new Generator(policy, bufferPool);
        this.parser = new Parser(policy, bufferPool, this);
        this.suspendToken = new AtomicBoolean(false);
        this.flusher = new Flusher(policy.getOutputBufferSize(), generator, endp);
        this.setInputBufferSize(policy.getInputBufferSize());
        this.setMaxIdleTimeout(policy.getIdleTimeout());

        this.extensionStack.setPolicy(this.policy);
        this.extensionStack.configure(this.parser);
        this.extensionStack.configure(this.generator);

        this.extensionStack.setNextIncoming(this);
        this.extensionStack.setNextOutgoing(flusher);

        this.outgoingFrames = extensionStack;
    }

    public void setSession(WSCoreSession session)
    {
        this.session = session;
        this.incomingFrames = session;
    }

    @Override
    public Executor getExecutor()
    {
        return super.getExecutor();
    }

    public void close(CloseStatus closeStatus, Callback callback)
    {
        connectionState.onClosing(); // always move to (at least) the CLOSING state (might already be past it, which is ok)

        if (closeSent.compareAndSet(false, true))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Sending Close Frame");
            CloseFrame closeFrame = new CloseFrame().setPayload(closeStatus);
            outgoingFrames.outgoingFrame(closeFrame, callback, BatchMode.OFF);
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Close Frame Previously Sent: ignoring: {} [{}]", closeStatus, callback);
            callback.failed(new WSException("Already closed"));
        }
    }

    public void disconnect()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnect()");

        // close FrameFlusher, we cannot write anymore at this point.
        flusher.close();

        closed.set(true);
        close();
    }

    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    public Generator getGenerator()
    {
        return generator;
    }

    public String getId()
    {
        return id;
    }

    public long getIdleTimeout()
    {
        return getEndPoint().getIdleTimeout();
    }

    public DecoratedObjectFactory getObjectFactory()
    {
        return objectFactory;
    }

    public Parser getParser()
    {
        return parser;
    }

    public WSPolicy getPolicy()
    {
        return policy;
    }

    public InetSocketAddress getLocalAddress()
    {
        return getEndPoint().getLocalAddress();
    }

    public InetSocketAddress getRemoteAddress()
    {
        return getEndPoint().getRemoteAddress();
    }

    public UpgradeRequest getUpgradeRequest()
    {
        return upgradeRequest;
    }

    public UpgradeResponse getUpgradeResponse()
    {
        return upgradeResponse;
    }

    public boolean isOpen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("isOpen() = {}", !closed.get());
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
            LOG.debug("onClose() of physical connection");

        closed.set(true);

        flusher.close();
        super.onClose();
    }

    @Override
    public boolean onIdleExpired()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onIdleExpired()");

        // TODO: notifyError ??
        session.onError(new WSTimeoutException("Connection Idle Timeout"));
        return true;
    }

    @Override
    public boolean onFrame(Frame frame)
    {
        AtomicBoolean result = new AtomicBoolean(false);

        if (LOG.isDebugEnabled())
            LOG.debug("onFrame({})", frame);

        extensionStack.incomingFrame(frame, new Callback()
        {
            @Override
            public void succeeded()
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("onFrame({}).succeed()", frame);

                parser.release(frame);
                if (!result.compareAndSet(false, true))
                {
                    // callback has been notified asynchronously
                    fillAndParse();
                }
            }

            @Override
            public void failed(Throwable cause)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("onFrame(" + frame + ").fail()", cause);
                parser.release(frame);

                // notify session & endpoint
                session.onError(cause);
            }
        });

        if (result.compareAndSet(false, true))
        {
            // callback hasn't been notified yet
            return false;
        }

        return true;
    }

    public boolean isSecure()
    {
        if (upgradeRequest == null)
        {
            throw new IllegalStateException("No valid UpgradeRequest yet");
        }

        URI requestURI = upgradeRequest.getRequestURI();

        return "wss".equalsIgnoreCase(requestURI.getScheme());
    }

    public WSConnectionState getState()
    {
        return connectionState;
    }

    /**
     * Incoming Raw Frames from Parser (after ExtensionStack)
     */
    @Override
    public void incomingFrame(Frame frame, Callback callback)
    {
        if(LOG.isDebugEnabled())
        {
            LOG.debug("incomingFrame({}, {})", frame, callback);
        }

        incomingFrames.incomingFrame(frame, callback);
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
            assert (!buffer.hasRemaining());
            bufferPool.release(buffer);
            networkBuffer = null;
        }
    }

    @Override
    public void onFillable()
    {
        if(LOG.isDebugEnabled())
        {
            LOG.debug("onFillable()");
        }
        getNetworkBuffer();
        fillAndParse();
    }

    @Override
    public void fillInterested()
    {
        // Handle situation where prefill buffer (from upgrade) has created network buffer,
        // but there is no actual read interest (yet)
        if (BufferUtil.hasContent(networkBuffer))
        {
            fillAndParse();
        }
        else
        {
            super.fillInterested();
        }
    }

    private void fillAndParse()
    {
        try
        {
            fillAndParseScope.set(true);
            while (isOpen())
            {
                if (suspendToken.get())
                {
                    return;
                }

                ByteBuffer nBuffer = getNetworkBuffer();

                if (!parser.parse(nBuffer)) return;

                // Shouldn't reach this point if buffer has un-parsed bytes
                assert (!nBuffer.hasRemaining());

                int filled = getEndPoint().fill(nBuffer);

                if (LOG.isDebugEnabled())
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
            session.onError(t);
        }
        finally
        {
            fillAndParseScope.set(false);
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
            networkBuffer = bufferPool.acquire(prefilled.remaining(), true);
            BufferUtil.clearToFill(networkBuffer);
            BufferUtil.put(prefilled, networkBuffer);
            BufferUtil.flipToFlush(networkBuffer, 0);
        }
    }

    /**
     * Physical connection Open.
     */
    @Override
    public void onOpen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onOpen() of physical connection");

        if(connectionState.onConnecting())
        {
            session.open();
        }
        super.onOpen();
    }

    /**
     * Event for no activity on connection (read or write)
     */
    @Override
    protected boolean onReadTimeout()
    {
        session.onError(new SocketTimeoutException("Timeout on Read"));
        return false;
    }

    /**
     * Frame from API, User, or Internal implementation destined for network.
     */
    public void outgoingFrame(Frame frame, Callback callback, BatchMode batchMode)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("outgoingFrame({}, {})", frame, callback);
        }

        outgoingFrames.outgoingFrame(frame, callback, batchMode);
    }

    @Override
    public void resume()
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("resume()");
        }

        if (suspendToken.compareAndSet(true, false))
        {
            // Do not fillAndParse again, if we are actively in a fillAndParse
            if (!fillAndParseScope.get())
            {
                fillAndParse();
            }
        }
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

    public void setMaxIdleTimeout(long ms)
    {
        if (ms >= 0)
        {
            getEndPoint().setIdleTimeout(ms);
        }
    }

    public SuspendToken suspend()
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("suspend()");
        }

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
        return String.format("%s@%x[%s,%s,f=%s,g=%s,p=%s]",
                getClass().getSimpleName(),
                hashCode(),
                getPolicy().getBehavior(),
                isOpen() ? "OPEN" : "CLOSED",
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
        if (endp != null)
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
        WSConnection other = (WSConnection) obj;
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
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onUpgradeTo({})", BufferUtil.toDetailString(prefilled));
        }

        setInitialBuffer(prefilled);
    }
}
