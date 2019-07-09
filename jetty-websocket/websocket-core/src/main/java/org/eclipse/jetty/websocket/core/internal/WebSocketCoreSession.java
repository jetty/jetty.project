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
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.Utf8Appendable;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.CloseException;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.IncomingFrames;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.OutgoingFrames;
import org.eclipse.jetty.websocket.core.ProtocolException;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.WebSocketTimeoutException;
import org.eclipse.jetty.websocket.core.WebSocketWriteTimeoutException;
import org.eclipse.jetty.websocket.core.internal.Parser.ParsedFrame;
import org.eclipse.jetty.websocket.core.internal.compress.DeflateFrameExtension;

import static org.eclipse.jetty.util.Callback.NOOP;

/**
 * The Core WebSocket Session.
 */
public class WebSocketCoreSession implements IncomingFrames, FrameHandler.CoreSession, Dumpable
{
    private static final Logger LOG = Log.getLogger(WebSocketCoreSession.class);
    private static final CloseStatus NO_CODE = new CloseStatus(CloseStatus.NO_CODE);

    private final Behavior behavior;
    private final WebSocketSessionState sessionState = new WebSocketSessionState();
    private final FrameHandler handler;
    private final Negotiated negotiated;
    private final boolean demanding;
    private final Flusher flusher = new Flusher();

    private WebSocketConnection connection;
    private boolean autoFragment = WebSocketConstants.DEFAULT_AUTO_FRAGMENT;
    private long maxFrameSize = WebSocketConstants.DEFAULT_MAX_FRAME_SIZE;
    private int inputBufferSize = WebSocketConstants.DEFAULT_INPUT_BUFFER_SIZE;
    private int outputBufferSize = WebSocketConstants.DEFAULT_OUTPUT_BUFFER_SIZE;
    private long maxBinaryMessageSize = WebSocketConstants.DEFAULT_MAX_BINARY_MESSAGE_SIZE;
    private long maxTextMessageSize = WebSocketConstants.DEFAULT_MAX_TEXT_MESSAGE_SIZE;
    private Duration idleTimeout = WebSocketConstants.DEFAULT_IDLE_TIMEOUT;
    private Duration writeTimeout = WebSocketConstants.DEFAULT_WRITE_TIMEOUT;

    public WebSocketCoreSession(FrameHandler handler, Behavior behavior, Negotiated negotiated)
    {
        this.handler = handler;
        this.behavior = behavior;
        this.negotiated = negotiated;
        this.demanding = handler.isDemanding();
        negotiated.getExtensions().initialize(new IncomingAdaptor(), new OutgoingAdaptor(), this);
    }

    /**
     * @return True if the sessions handling is demanding.
     */
    public boolean isDemanding()
    {
        return demanding;
    }

    public void assertValidIncoming(Frame frame)
    {
        assertValidFrame(frame);

        // Assert Incoming Frame Behavior Required by RFC-6455 / Section 5.1
        switch (behavior)
        {
            case SERVER:
                if (!frame.isMasked())
                    throw new ProtocolException("Client MUST mask all frames (RFC-6455: Section 5.1)");
                break;

            case CLIENT:
                if (frame.isMasked())
                    throw new ProtocolException("Server MUST NOT mask any frames (RFC-6455: Section 5.1)");
                break;

            default:
                throw new IllegalStateException(behavior.toString());
        }

        /*
         * RFC 6455 Section 5.5.1
         * close frame payload is specially formatted which is checked in CloseStatus
         */
        if (frame.getOpCode() == OpCode.CLOSE)
        {
            if (!(frame instanceof ParsedFrame)) // already check in parser
                CloseStatus.getCloseStatus(frame); // return ignored as get used to validate there is a closeStatus
        }
    }

    public void assertValidOutgoing(Frame frame) throws CloseException
    {
        assertValidFrame(frame);

        /*
         * RFC 6455 Section 5.5.1
         * close frame payload is specially formatted which is checked in CloseStatus
         */
        if (frame.getOpCode() == OpCode.CLOSE)
        {
            if (!(frame instanceof ParsedFrame)) // already check in parser
            {
                CloseStatus closeStatus = CloseStatus.getCloseStatus(frame);
                if (!CloseStatus.isTransmittableStatusCode(closeStatus.getCode()) && (closeStatus.getCode() != CloseStatus.NO_CODE))
                {
                    throw new ProtocolException("Frame has non-transmittable status code");
                }
            }

        }
    }

    public void assertValidFrame(Frame frame)
    {
        if (!OpCode.isKnown(frame.getOpCode()))
            throw new ProtocolException("Unknown opcode: " + frame.getOpCode());

        int payloadLength = (frame.getPayload() == null) ? 0 : frame.getPayload().remaining();
        if (frame.isControlFrame())
        {
            if (!frame.isFin())
                throw new ProtocolException("Fragmented Control Frame [" + OpCode.name(frame.getOpCode()) + "]");

            if (payloadLength > Frame.MAX_CONTROL_PAYLOAD)
                throw new ProtocolException("Invalid control frame payload length, [" + payloadLength + "] cannot exceed [" + Frame.MAX_CONTROL_PAYLOAD + "]");

            if (frame.isRsv1())
                throw new ProtocolException("Cannot have RSV1==true on Control frames");
            if (frame.isRsv2())
                throw new ProtocolException("Cannot have RSV2==true on Control frames");
            if (frame.isRsv3())
                throw new ProtocolException("Cannot have RSV3==true on Control frames");
        }
        else
        {
            /*
             * RFC 6455 Section 5.2
             *
             * MUST be 0 unless an extension is negotiated that defines meanings for non-zero values. If a nonzero value is received and none of the negotiated
             * extensions defines the meaning of such a nonzero value, the receiving endpoint MUST _Fail the WebSocket Connection_.
             */
            ExtensionStack extensionStack = negotiated.getExtensions();
            if (frame.isRsv1() && !extensionStack.isRsv1Used())
                throw new ProtocolException("RSV1 not allowed to be set");
            if (frame.isRsv2() && !extensionStack.isRsv2Used())
                throw new ProtocolException("RSV2 not allowed to be set");
            if (frame.isRsv3() && !extensionStack.isRsv3Used())
                throw new ProtocolException("RSV3 not allowed to be set");
        }
    }

    public ExtensionStack getExtensionStack()
    {
        return negotiated.getExtensions();
    }

    public FrameHandler getHandler()
    {
        return handler;
    }

    @Override
    public String getNegotiatedSubProtocol()
    {
        return negotiated.getSubProtocol();
    }

    @Override
    public Duration getIdleTimeout()
    {
        return idleTimeout;
    }

    @Override
    public void setIdleTimeout(Duration timeout)
    {
        idleTimeout = timeout;
        if (connection != null)
            connection.getEndPoint().setIdleTimeout(timeout.toMillis());
    }

    @Override
    public Duration getWriteTimeout()
    {
        return writeTimeout;
    }

    @Override
    public void setWriteTimeout(Duration timeout)
    {
        writeTimeout = timeout;
        if (getConnection() != null)
            getConnection().getFrameFlusher().setIdleTimeout(timeout.toMillis());
    }

    public SocketAddress getLocalAddress()
    {
        return getConnection().getEndPoint().getLocalAddress();
    }

    public SocketAddress getRemoteAddress()
    {
        return getConnection().getEndPoint().getRemoteAddress();
    }

    @Override
    public boolean isOutputOpen()
    {
        return sessionState.isOutputOpen();
    }

    public boolean isClosed()
    {
        return sessionState.isClosed();
    }

    public void setWebSocketConnection(WebSocketConnection connection)
    {
        connection.getEndPoint().setIdleTimeout(idleTimeout.toMillis());
        connection.getFrameFlusher().setIdleTimeout(writeTimeout.toMillis());
        this.connection = connection;
    }

    /**
     * Send Close Frame with no payload.
     *
     * @param callback the callback on successful send of close frame
     */
    @Override
    public void close(Callback callback)
    {
        close(NO_CODE, callback);
    }

    /**
     * Send Close Frame with specified Status Code and optional Reason
     *
     * @param statusCode a valid WebSocket status code
     * @param reason an optional reason phrase
     * @param callback the callback on successful send of close frame
     */
    @Override
    public void close(int statusCode, String reason, Callback callback)
    {
        close(new CloseStatus(statusCode, reason), callback);
    }

    private void close(CloseStatus closeStatus, Callback callback)
    {
        sendFrame(closeStatus.toFrame(), callback, false);
    }

    @Override
    public ByteBufferPool getByteBufferPool()
    {
        return this.connection.getBufferPool();
    }

    public void onEof()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onEof() {}", this);

        if (sessionState.onEof())
            closeConnection(sessionState.getCloseStatus(), Callback.NOOP);
    }

    public void closeConnection(CloseStatus closeStatus, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("closeConnection() {} {} {}", closeStatus, this);

        connection.cancelDemand();
        if (connection.getEndPoint().isOpen())
            connection.close();

        // Forward Errors to Local WebSocket EndPoint
        if (closeStatus.isAbnormal() && closeStatus.getCause() != null)
        {
            Callback errorCallback = Callback.from(() ->
            {
                try
                {
                    handler.onClosed(closeStatus, callback);
                }
                catch (Throwable e)
                {
                    LOG.warn(e);
                    callback.failed(e);
                }
            });

            Throwable cause = closeStatus.getCause();
            try
            {
                handler.onError(cause, errorCallback);
            }
            catch (Throwable e)
            {
                if (e != cause)
                    cause.addSuppressed(e);
                LOG.warn(cause);
                errorCallback.failed(cause);
            }
        }
        else
        {
            try
            {
                handler.onClosed(closeStatus, callback);
            }
            catch (Throwable e)
            {
                LOG.warn(e);
                callback.failed(e);
            }
        }
    }

    /**
     * Process an Error that originated from the connection.
     * For protocol causes, send and abnormal close frame
     * otherwise just close the connection.
     *
     * @param cause the cause
     * @param callback the callback on completion of error handling
     */
    public void processConnectionError(Throwable cause, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processConnectionError {} {}", this, cause);

        int code;
        if (cause instanceof CloseException)
            code = ((CloseException)cause).getStatusCode();
        else if (cause instanceof Utf8Appendable.NotUtf8Exception)
            code = CloseStatus.BAD_PAYLOAD;
        else if (cause instanceof WebSocketWriteTimeoutException)
            code = CloseStatus.NO_CLOSE;
        else if (cause instanceof WebSocketTimeoutException || cause instanceof TimeoutException || cause instanceof SocketTimeoutException)
            code = CloseStatus.SHUTDOWN;
        else
            code = CloseStatus.NO_CLOSE;

        CloseStatus closeStatus = new CloseStatus(code, cause);
        if (CloseStatus.isTransmittableStatusCode(code))
            close(closeStatus, callback);
        else
        {
            if (sessionState.onClosed(closeStatus))
                closeConnection(closeStatus, callback);
        }
    }

    /**
     * Process an Error that originated from the handler.
     * Send an abnormal close frame to ensure connection is closed.
     *
     * @param cause the cause
     * @param callback the callback on completion of error handling
     */
    public void processHandlerError(Throwable cause, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processHandlerError {} {}", this, cause);

        int code;
        if (cause instanceof CloseException)
            code = ((CloseException)cause).getStatusCode();
        else if (cause instanceof Utf8Appendable.NotUtf8Exception)
            code = CloseStatus.BAD_PAYLOAD;
        else if (cause instanceof WebSocketTimeoutException || cause instanceof TimeoutException || cause instanceof SocketTimeoutException)
            code = CloseStatus.SHUTDOWN;
        else if (behavior == Behavior.CLIENT)
            code = CloseStatus.POLICY_VIOLATION;
        else
            code = CloseStatus.SERVER_ERROR;

        close(new CloseStatus(code, cause), callback);
    }

    /**
     * Open/Activate the session.
     */
    public void onOpen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onOpen() {}", this);

        // Upgrade success
        sessionState.onConnected();
        if (LOG.isDebugEnabled())
            LOG.debug("ConnectionState: Transition to CONNECTED");

        Callback openCallback = Callback.from(
            () ->
            {
                sessionState.onOpen();
                if (!demanding)
                    connection.demand(1);
                if (LOG.isDebugEnabled())
                    LOG.debug("ConnectionState: Transition to OPEN");
            },
            x ->
            {
                LOG.warn("Error during OPEN", x);
                processHandlerError(new CloseException(CloseStatus.SERVER_ERROR, x), NOOP);
            });

        try
        {
            // Open connection and handler
            handler.onOpen(this, openCallback);
        }
        catch (Throwable t)
        {
            openCallback.failed(t);

            /* This is double handling of the exception but we need to do this because we have two separate
            mechanisms for returning the CoreSession, onOpen and the CompletableFuture and both the onOpen callback
            and the CompletableFuture require the exception. */
            throw new RuntimeException(t);
        }
    }

    @Override
    public void demand(long n)
    {
        if (!demanding)
            throw new IllegalStateException("FrameHandler is not demanding: " + this);
        if (!sessionState.isInputOpen())
            throw new IllegalStateException("FrameHandler input not open: " + this); // TODO Perhaps this is a NOOP?
        connection.demand(n);
    }

    public WebSocketConnection getConnection()
    {
        return connection;
    }

    public Executor getExecutor()
    {
        return connection.getExecutor();
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onFrame({})", frame);

        try
        {
            assertValidIncoming(frame);
        }
        catch (Throwable t)
        {
            callback.failed(t);
            return;
        }

        negotiated.getExtensions().onFrame(frame, callback);
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, boolean batch)
    {
        try
        {
            assertValidOutgoing(frame);
        }
        catch (Throwable t)
        {
            if (LOG.isDebugEnabled())
                LOG.warn("Invalid outgoing frame: " + frame, t);

            callback.failed(t);
            return;
        }

        try
        {
            synchronized (flusher)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("sendFrame({}, {}, {})", frame, callback, batch);

                boolean closeConnection = sessionState.onOutgoingFrame(frame);
                if (closeConnection)
                {
                    Callback closeConnectionCallback = Callback.from(
                        () -> closeConnection(sessionState.getCloseStatus(), callback),
                        t -> closeConnection(sessionState.getCloseStatus(), Callback.from(callback, t)));

                    flusher.queue.offer(new FrameEntry(frame, closeConnectionCallback, false));
                }
                else
                {
                    flusher.queue.offer(new FrameEntry(frame, callback, batch));
                }
            }
            flusher.iterate();
        }
        catch (Throwable t)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Failed sendFrame()", t);

            if (frame.getOpCode() == OpCode.CLOSE)
            {
                CloseStatus closeStatus = CloseStatus.getCloseStatus(frame);
                if (closeStatus.isAbnormal() && sessionState.onClosed(closeStatus))
                    closeConnection(closeStatus, Callback.from(callback, t));
                else
                    callback.failed(t);
            }
            else
                callback.failed(t);
        }
    }

    @Override
    public void flush(Callback callback)
    {
        synchronized (flusher)
        {
            flusher.queue.offer(new FrameEntry(FrameFlusher.FLUSH_FRAME, callback, false));
        }
        flusher.iterate();
    }

    @Override
    public void abort()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("abort(): {}", this);

        connection.cancelDemand();
        connection.getEndPoint().close();
    }

    @Override
    public boolean isAutoFragment()
    {
        return autoFragment;
    }

    @Override
    public void setAutoFragment(boolean autoFragment)
    {
        // TODO: consider adding extensible/generic mechanism for extensions to validate configuration changes if more examples occur
        if (autoFragment && getExtensionStack().getRsv1User() instanceof DeflateFrameExtension)
            LOG.warn("Frame auto-fragmentation must not be used with DeflateFrameExtension");
        this.autoFragment = autoFragment;
    }

    @Override
    public long getMaxFrameSize()
    {
        return maxFrameSize;
    }

    @Override
    public void setMaxFrameSize(long maxFrameSize)
    {
        this.maxFrameSize = maxFrameSize;
    }

    @Override
    public int getOutputBufferSize()
    {
        return outputBufferSize;
    }

    @Override
    public void setOutputBufferSize(int outputBufferSize)
    {
        this.outputBufferSize = outputBufferSize;
    }

    @Override
    public int getInputBufferSize()
    {
        return inputBufferSize;
    }

    @Override
    public void setInputBufferSize(int inputBufferSize)
    {
        this.inputBufferSize = inputBufferSize;
        if (connection != null)
            connection.setInputBufferSize(inputBufferSize);
    }

    @Override
    public long getMaxBinaryMessageSize()
    {
        return maxBinaryMessageSize;
    }

    @Override
    public void setMaxBinaryMessageSize(long maxSize)
    {
        maxBinaryMessageSize = maxSize;
    }

    @Override
    public long getMaxTextMessageSize()
    {
        return maxTextMessageSize;
    }

    @Override
    public void setMaxTextMessageSize(long maxSize)
    {
        maxTextMessageSize = maxSize;
    }

    private class IncomingAdaptor implements IncomingFrames
    {
        @Override
        public void onFrame(Frame frame, final Callback callback)
        {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("receiveFrame({}, {}) - connectionState={}, handler={}", frame, callback, sessionState, handler);

                boolean closeConnection = sessionState.onIncomingFrame(frame);

                // Handle inbound frame
                if (frame.getOpCode() != OpCode.CLOSE)
                {
                    handler.onFrame(frame, callback);
                    return;
                }

                // Handle inbound CLOSE
                connection.cancelDemand();
                Callback closeCallback;

                if (closeConnection)
                {
                    closeCallback = Callback.from(() -> closeConnection(sessionState.getCloseStatus(), callback));
                }
                else
                {
                    closeCallback = Callback.from(
                        () ->
                        {
                            if (sessionState.isOutputOpen())
                            {
                                CloseStatus closeStatus = CloseStatus.getCloseStatus(frame);
                                if (LOG.isDebugEnabled())
                                    LOG.debug("ConnectionState: sending close response {}", closeStatus);
                                close(closeStatus == null ? CloseStatus.NO_CODE_STATUS : closeStatus, callback);
                            }
                            else
                            {
                                callback.succeeded();
                            }
                        },
                        x -> processHandlerError(x, callback));
                }

                handler.onFrame(frame, closeCallback);
            }
            catch (Throwable t)
            {
                callback.failed(t);
            }
        }
    }

    private class OutgoingAdaptor implements OutgoingFrames
    {
        @Override
        public void sendFrame(Frame frame, Callback callback, boolean batch)
        {
            try
            {
                connection.enqueueFrame(frame, callback, batch);
            }
            catch (ProtocolException e)
            {
                callback.failed(e);
            }
        }
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this,
            "subprotocol=" + negotiated.getSubProtocol(),
            negotiated.getExtensions(),
            handler);
    }

    @Override
    public List<ExtensionConfig> getNegotiatedExtensions()
    {
        return negotiated.getExtensions().getNegotiatedExtensions();
    }

    @Override
    public Map<String, List<String>> getParameterMap()
    {
        return negotiated.getParameterMap();
    }

    @Override
    public String getProtocolVersion()
    {
        return negotiated.getProtocolVersion();
    }

    @Override
    public URI getRequestURI()
    {
        return negotiated.getRequestURI();
    }

    @Override
    public boolean isSecure()
    {
        return negotiated.isSecure();
    }

    @Override
    public Behavior getBehavior()
    {
        return behavior;
    }

    @Override
    public String toString()
    {
        return String.format("WSCoreSession@%x{%s,%s,%s,af=%b,i/o=%d/%d,fs=%d}->%s",
            hashCode(),
            behavior,
            sessionState,
            negotiated,
            autoFragment,
            inputBufferSize,
            outputBufferSize,
            maxFrameSize,
            handler);
    }

    private class Flusher extends IteratingCallback
    {
        private final Queue<FrameEntry> queue = new ArrayDeque<>();
        FrameEntry entry;

        @Override
        protected Action process() throws Throwable
        {
            synchronized (this)
            {
                entry = queue.poll();
            }
            if (entry == null)
                return Action.IDLE;

            negotiated.getExtensions().sendFrame(entry.frame, this, entry.batch);
            return Action.SCHEDULED;
        }

        @Override
        public void succeeded()
        {
            entry.callback.succeeded();
            super.succeeded();
        }

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            entry.callback.failed(cause);
            Queue<FrameEntry> entries;
            synchronized (this)
            {
                entries = new ArrayDeque<>(queue);
                queue.clear();
            }
            entries.forEach(e -> failEntry(cause, e));
        }

        private void failEntry(Throwable cause, FrameEntry e)
        {
            try
            {
                e.callback.failed(cause);
            }
            catch (Throwable x)
            {
                if (cause != x)
                    cause.addSuppressed(x);
                LOG.warn(cause);
            }
        }
    }
}
