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
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.Utf8Appendable;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.CloseException;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.WSLocalEndpoint;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.StatusCode;
import org.eclipse.jetty.websocket.core.WSRemoteEndpoint;
import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.core.WebSocketException;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.WebSocketTimeoutException;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.generator.Generator;
import org.eclipse.jetty.websocket.core.handshake.UpgradeRequest;
import org.eclipse.jetty.websocket.core.handshake.UpgradeResponse;
import org.eclipse.jetty.websocket.core.parser.Parser;
import org.eclipse.jetty.websocket.core.util.CompletionCallback;

/**
 * Provides the implementation of {@link org.eclipse.jetty.io.Connection} that is suitable for WebSocket
 */
public class WSConnection extends AbstractConnection implements Parser.Handler, IncomingFrames, SuspendToken, Connection.UpgradeTo, Dumpable
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
            notifyError(x);
        }
    }

    // Callbacks
    private Callback onDisconnectCallback = new CompletionCallback()
    {
        @Override
        public void complete()
        {
            if (connectionState.onClosed())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("ConnectionState: Transition to CLOSED");
                WSConnection.this.disconnect();
            }
        }
    };

    /**
     * Minimum size of a buffer is the determined to be what would be the maximum framing header size (not including payload)
     */
    private static final int MIN_BUFFER_SIZE = Generator.MAX_HEADER_LENGTH;

    private final Logger LOG;
    private final ByteBufferPool bufferPool;
    private final Generator generator;
    private final Parser parser;
    private final WebSocketPolicy policy;
    private final AtomicBoolean suspendToken;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final FrameFlusher flusher;
    private final ExtensionStack extensionStack;
    private final String id;
    private final UpgradeRequest upgradeRequest;
    private final UpgradeResponse upgradeResponse;
    private final WSLocalEndpoint localEndpoint;
    private final WSRemoteEndpoint remoteEndpoint;
    private final OutgoingFrames outgoingFrames;
    private final IncomingFrames incomingFrames;
    private final WSConnectionState connectionState = new WSConnectionState();
    private final AtomicBoolean closeSent = new AtomicBoolean(false);
    private final AtomicBoolean closeNotified = new AtomicBoolean(false);
    private final DecoratedObjectFactory objectFactory;

    // Read / Parse variables
    private AtomicBoolean fillAndParseScope = new AtomicBoolean(false);
    private ByteBuffer networkBuffer;

    // Holder for errors during open that are reported in doStart later
    private AtomicReference<Throwable> pendingError = new AtomicReference<>();

    /* The websocket endpoint object itself.
     * Not declared final, as it can be decorated later by other libraries (CDI)
     */
    private Object wsEndpoint;

    /**
     * Create a WSConnection.
     * <p>
     * It is assumed that the WebSocket Upgrade Handshake has already
     * completed successfully before creating this connection.
     * </p>
     */
    public WSConnection(EndPoint endp, Executor executor, ByteBufferPool bufferPool,
                        DecoratedObjectFactory decoratedObjectFactory,
                        WebSocketPolicy policy, ExtensionStack extensionStack,
                        UpgradeRequest upgradeRequest, UpgradeResponse upgradeResponse,
                        Object wsEndpoint, WSLocalEndpoint localEndpoint)
    {
        super(endp, executor);

        Objects.requireNonNull(endp, "EndPoint");
        Objects.requireNonNull(executor, "Executor");
        Objects.requireNonNull(bufferPool, "ByteBufferPool");
        Objects.requireNonNull(decoratedObjectFactory, "DecoratedObjectFactory");
        Objects.requireNonNull(policy, "WebSocketPolicy");
        Objects.requireNonNull(extensionStack, "ExtensionStack");
        Objects.requireNonNull(upgradeRequest, "UpgradeRequest");
        Objects.requireNonNull(upgradeResponse, "UpgradeResponse");
        Objects.requireNonNull(wsEndpoint, "WebSocket Endpoint");
        Objects.requireNonNull(localEndpoint, "WSLocalEndpoint");

        LOG = Log.getLogger(WSConnection.class.getName() + "." + policy.getBehavior());
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

        this.wsEndpoint = wsEndpoint;
        this.localEndpoint = localEndpoint;

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

        this.outgoingFrames = extensionStack;
        this.incomingFrames = extensionStack;

        this.remoteEndpoint = new WSRemote(this.extensionStack);

    }

    private void close(int statusCode, String reason, Callback callback)
    {
        close(new CloseStatus(statusCode, reason), callback);
    }

    private void close(CloseStatus closeStatus, Callback callback)
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
            callback.failed(new WebSocketException("Already closed"));
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

    public WebSocketPolicy getPolicy()
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

    public WSRemoteEndpoint getRemoteEndpoint()
    {
        return remoteEndpoint;
    }

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
    public boolean onIdleExpired()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onIdleExpired()");

        notifyError(new WebSocketTimeoutException("Connection Idle Timeout"));
        return true;
    }

    @Override
    public boolean onFrame(Frame frame)
    {
        AtomicBoolean result = new AtomicBoolean(false);

        if (LOG.isDebugEnabled())
            LOG.debug("onFrame({})", frame);

        incomingFrames.incomingFrame(frame, new Callback()
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
                notifyError(cause);
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

    public void notifyClose(CloseStatus closeStatus)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("notifyClose({}) closeNotified={}", closeStatus, closeNotified.get());
        }

        // only notify once
        if (closeNotified.compareAndSet(false, true))
        {
            localEndpoint.onClose(closeStatus);
        }
    }

    /**
     * Error Event.
     * <p>
     * Can be seen from Session and Connection.
     * </p>
     *
     * @param t the raw cause
     */
    public void onError(Throwable t)
    {
        synchronized (pendingError)
        {
            if (!localEndpoint.isOpen())
            {
                // this is a *really* fast fail, before the Session has even started.
                pendingError.compareAndSet(null, t);
                return;
            }
        }

        Throwable cause = getInvokedCause(t);

        // Forward Errors to User WebSocket Object
        localEndpoint.onError(cause);

        if (cause instanceof Utf8Appendable.NotUtf8Exception)
        {
            close(StatusCode.BAD_PAYLOAD, cause.getMessage(), onDisconnectCallback);
        }
        else if (cause instanceof SocketTimeoutException)
        {
            // A path often seen in Windows
            close(StatusCode.SHUTDOWN, cause.getMessage(), onDisconnectCallback);
        }
        else if (cause instanceof IOException)
        {
            close(StatusCode.PROTOCOL, cause.getMessage(), onDisconnectCallback);
        }
        else if (cause instanceof SocketException)
        {
            // A path unique to Unix
            close(StatusCode.SHUTDOWN, cause.getMessage(), onDisconnectCallback);
        }
        else if (cause instanceof CloseException)
        {
            CloseException ce = (CloseException) cause;
            Callback callback = Callback.NOOP;

            // Force disconnect for protocol breaking status codes
            switch (ce.getStatusCode())
            {
                case StatusCode.PROTOCOL:
                case StatusCode.BAD_DATA:
                case StatusCode.BAD_PAYLOAD:
                case StatusCode.MESSAGE_TOO_LARGE:
                case StatusCode.POLICY_VIOLATION:
                case StatusCode.SERVER_ERROR:
                {
                    callback = onDisconnectCallback;
                }
            }

            close(ce.getStatusCode(), ce.getMessage(), callback);
        }
        else if (cause instanceof WebSocketTimeoutException)
        {
            close(StatusCode.SHUTDOWN, cause.getMessage(), onDisconnectCallback);
        }
        else
        {
            LOG.warn("Unhandled Error (closing connection)", cause);

            // Exception on end-user WS-Endpoint.
            // Fast-fail & close connection with reason.
            int statusCode = StatusCode.SERVER_ERROR;
            if (getPolicy().getBehavior() == WebSocketBehavior.CLIENT)
            {
                statusCode = StatusCode.POLICY_VIOLATION;
            }
            close(statusCode, cause.getMessage(), Callback.NOOP);
        }
    }

    protected Throwable getInvokedCause(Throwable t)
    {
        // Unwrap any invoker exceptions here.
        return t;
    }

    /**
     * Open/Activate the session
     */
    public void open()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}.open()", this.getClass().getSimpleName());

        if (remoteEndpoint != null)
        {
            // already opened
            return;
        }

        try
        {
            // Upgrade success
            if (connectionState.onConnected())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("ConnectionState: Transition to CONNECTED");

                // Connect remoteEndpoint
                if (LOG.isDebugEnabled())
                    LOG.debug("{}.open() remoteEndpoint={}", this.getClass().getSimpleName(), remoteEndpoint);

                try
                {
                    // Open WebSocket
                    localEndpoint.onOpen();

                    // Open connection
                    if (connectionState.onOpen())
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("ConnectionState: Transition to OPEN");
                    }
                }
                catch (Throwable t)
                {
                    localEndpoint.getLog().warn("Error during OPEN", t);
                    onError(new CloseException(StatusCode.SERVER_ERROR, t));
                }

                /* Perform fillInterested outside of onConnected / onOpen.
                 *
                 * This is to allow for 2 specific scenarios.
                 *
                 * 1) Fast Close
                 *    When an end users WSEndpoint.onOpen() calls
                 *    the Session.close() method.
                 *    This is a state transition of CONNECTING -> CONNECTED -> CLOSING
                 * 2) Fast Fail
                 *    When an end users WSEndpoint.onOpen() throws an Exception.
                 */
                fillInterested();
            }
            else
            {
                throw new IllegalStateException("Unexpected state [" + connectionState.get() + "] when attempting to transition to CONNECTED");
            }
        }
        catch (Throwable t)
        {
            LOG.warn(t);
            onError(t);
        }
    }

    /**
     * Incoming Raw Frames from Parser (after ExtensionStack)
     */
    @Override
    public void incomingFrame(Frame frame, Callback callback)
    {
        try
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("incomingFrame({}, {}) - connectionState={}, localEndpoint={}",
                        frame, callback, connectionState.get(), localEndpoint);
            }
            if (connectionState.get() != WSConnectionState.State.CLOSED)
            {
                // For endpoints that want to see raw frames.
                localEndpoint.onFrame(frame);

                byte opcode = frame.getOpCode();
                switch (opcode)
                {
                    case OpCode.CLOSE:
                    {

                        if (connectionState.onClosing())
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("ConnectionState: Transition to CLOSING");
                            CloseFrame closeframe = (CloseFrame) frame;
                            CloseStatus closeStatus = closeframe.getCloseStatus();
                            notifyClose(closeStatus);
                            close(closeStatus, onDisconnectCallback);
                        }
                        else if (connectionState.onClosed())
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("ConnectionState: Transition to CLOSED");
                            CloseFrame closeframe = (CloseFrame) frame;
                            CloseStatus closeStatus = closeframe.getCloseStatus();
                            notifyClose(closeStatus);
                            disconnect();
                        }
                        else
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("ConnectionState: {} - Close Frame Received", connectionState);
                        }

                        callback.succeeded();
                        return;
                    }
                    case OpCode.PING:
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("PING: {}", BufferUtil.toDetailString(frame.getPayload()));

                        ByteBuffer pongBuf;
                        if (frame.hasPayload())
                        {
                            pongBuf = ByteBuffer.allocate(frame.getPayload().remaining());
                            BufferUtil.put(frame.getPayload().slice(), pongBuf);
                            BufferUtil.flipToFlush(pongBuf, 0);
                        }
                        else
                        {
                            pongBuf = ByteBuffer.allocate(0);
                        }

                        localEndpoint.onPing(frame.getPayload());
                        callback.succeeded();

                        try
                        {
                            getRemoteEndpoint().sendPong(pongBuf, Callback.NOOP);
                        }
                        catch (Throwable t)
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("Unable to send pong", t);
                        }
                        break;
                    }
                    case OpCode.PONG:
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("PONG: {}", BufferUtil.toDetailString(frame.getPayload()));

                        localEndpoint.onPong(frame.getPayload());
                        callback.succeeded();
                        break;
                    }
                    case OpCode.BINARY:
                    {
                        localEndpoint.onBinary(frame, callback);
                        // Let endpoint method handle callback
                        return;
                    }
                    case OpCode.TEXT:
                    {
                        localEndpoint.onText(frame, callback);
                        // Let endpoint method handle callback
                        return;
                    }
                    case OpCode.CONTINUATION:
                    {
                        localEndpoint.onContinuation(frame, callback);
                        // Let endpoint method handle callback
                        return;
                    }
                    default:
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Unhandled OpCode: {}", opcode);
                    }
                }
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Discarding post EOF frame - {}", frame);
            }
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
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
            notifyError(t);
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

    private void notifyError(Throwable cause)
    {
        // TODO: notify endpoint
    }

    /**
     * Physical connection Open.
     */
    @Override
    public void onOpen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}.onOpened()", this.getClass().getSimpleName());
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
    public void outgoingFrame(Frame frame, Callback callback, BatchMode batchMode)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("outgoingFrame({}, {})", frame, callback);
        }

        flusher.enqueue(frame, callback, batchMode);
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
                isOpen()?"OPEN":"CLOSED",
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
