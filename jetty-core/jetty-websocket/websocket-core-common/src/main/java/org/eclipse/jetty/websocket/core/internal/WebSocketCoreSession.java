//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.internal;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritePendingException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8Appendable;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.IncomingFrames;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.OutgoingFrames;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.exception.CloseException;
import org.eclipse.jetty.websocket.core.exception.ProtocolException;
import org.eclipse.jetty.websocket.core.exception.WebSocketTimeoutException;
import org.eclipse.jetty.websocket.core.exception.WebSocketWriteTimeoutException;
import org.eclipse.jetty.websocket.core.internal.util.FrameValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.util.Callback.NOOP;

/**
 * The Core WebSocket Session.
 */
public class WebSocketCoreSession implements IncomingFrames, CoreSession, Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketCoreSession.class);
    private static final CloseStatus NO_CODE = new CloseStatus(CloseStatus.NO_CODE);

    private final WebSocketComponents components;
    private final Behavior behavior;
    private final WebSocketSessionState sessionState = new WebSocketSessionState();
    private final FrameHandler handler;
    private final Negotiated negotiated;
    private final boolean demanding;
    private final Flusher flusher = new Flusher(this);
    private final ExtensionStack extensionStack;

    private int maxOutgoingFrames = -1;
    private final AtomicInteger numOutgoingFrames = new AtomicInteger();

    private WebSocketConnection connection;
    private boolean autoFragment = WebSocketConstants.DEFAULT_AUTO_FRAGMENT;
    private long maxFrameSize = WebSocketConstants.DEFAULT_MAX_FRAME_SIZE;
    private int inputBufferSize = WebSocketConstants.DEFAULT_INPUT_BUFFER_SIZE;
    private int outputBufferSize = WebSocketConstants.DEFAULT_OUTPUT_BUFFER_SIZE;
    private long maxBinaryMessageSize = WebSocketConstants.DEFAULT_MAX_BINARY_MESSAGE_SIZE;
    private long maxTextMessageSize = WebSocketConstants.DEFAULT_MAX_TEXT_MESSAGE_SIZE;
    private Duration idleTimeout = WebSocketConstants.DEFAULT_IDLE_TIMEOUT;
    private Duration writeTimeout = WebSocketConstants.DEFAULT_WRITE_TIMEOUT;
    private ClassLoader classLoader;

    public WebSocketCoreSession(FrameHandler handler, Behavior behavior, Negotiated negotiated, WebSocketComponents components)
    {
        this.classLoader = Thread.currentThread().getContextClassLoader();
        this.components = components;
        this.handler = handler;
        this.behavior = behavior;
        this.negotiated = negotiated;
        this.demanding = handler.isDemanding();
        extensionStack = negotiated.getExtensions();
        extensionStack.initialize(new IncomingAdaptor(), new OutgoingAdaptor(), this);
    }

    public ClassLoader getClassLoader()
    {
        return classLoader;
    }

    public void setClassLoader(ClassLoader classLoader)
    {
        this.classLoader = classLoader;
    }

    /**
     * Can be overridden to scope into the correct classloader before calling application code.
     * @param runnable the runnable to execute.
     */
    protected void handle(Runnable runnable)
    {
        ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(classLoader);
            runnable.run();
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(oldClassLoader);
        }
    }

    /**
     * @return True if the sessions handling is demanding.
     */
    public boolean isDemanding()
    {
        return demanding;
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
        return getConnection().getEndPoint().getLocalSocketAddress();
    }

    public SocketAddress getRemoteAddress()
    {
        return getConnection().getEndPoint().getRemoteSocketAddress();
    }

    @Override
    public boolean isInputOpen()
    {
        return sessionState.isInputOpen();
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
        extensionStack.setLastDemand(connection::demand);
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
            LOG.debug("closeConnection() {} {}", closeStatus, this);

        abort();

        // Forward Errors to Local WebSocket EndPoint
        if (closeStatus.isAbnormal() && closeStatus.getCause() != null)
        {
            Callback errorCallback = Callback.from(() ->
            {
                try
                {
                    handle(() -> handler.onClosed(closeStatus, callback));
                }
                catch (Throwable e)
                {
                    LOG.warn("Failure from onClosed on handler {}", handler, e);
                    callback.failed(e);
                }
            });

            Throwable cause = closeStatus.getCause();
            try
            {
                handle(() -> handler.onError(cause, errorCallback));
            }
            catch (Throwable e)
            {
                if (e != cause)
                    cause.addSuppressed(e);
                LOG.warn("Failure from onError on handler {}", handler, cause);
                errorCallback.failed(cause);
            }
        }
        else
        {
            try
            {
                handle(() -> handler.onClosed(closeStatus, callback));
            }
            catch (Throwable e)
            {
                LOG.warn("Failure from onClosed on handler {}", handler, e);
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
            LOG.debug("processConnectionError {}", this, cause);

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
            LOG.debug("processHandlerError {}", this, cause);

        int code;
        if (cause instanceof CloseException)
            code = ((CloseException)cause).getStatusCode();
        else if (cause instanceof ClosedChannelException)
            code = CloseStatus.NO_CLOSE;
        else if (cause instanceof Utf8Appendable.NotUtf8Exception)
            code = CloseStatus.BAD_PAYLOAD;
        else if (cause instanceof WebSocketTimeoutException || cause instanceof TimeoutException || cause instanceof SocketTimeoutException)
            code = CloseStatus.SHUTDOWN;
        else if (behavior == Behavior.CLIENT)
            code = CloseStatus.POLICY_VIOLATION;
        else
            code = CloseStatus.SERVER_ERROR;

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
                if (LOG.isDebugEnabled())
                    LOG.debug("ConnectionState: Transition to OPEN");
                if (!demanding)
                    autoDemand();
            },
            x ->
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Error during OPEN", x);
                processHandlerError(new CloseException(CloseStatus.SERVER_ERROR, x), NOOP);
            });

        try
        {
            // Open connection and handler
            handle(() -> handler.onOpen(this, openCallback));
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
        getExtensionStack().demand(n);
    }

    public void autoDemand()
    {
        getExtensionStack().demand(1);
    }

    @Override
    public boolean isRsv1Used()
    {
        return getExtensionStack().isRsv1Used();
    }

    @Override
    public boolean isRsv2Used()
    {
        return getExtensionStack().isRsv2Used();
    }

    @Override
    public boolean isRsv3Used()
    {
        return getExtensionStack().isRsv3Used();
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
            FrameValidation.assertValidIncoming(frame, this);
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
        if (maxOutgoingFrames > 0 && frame.isDataFrame())
        {
            // Increase the number of outgoing frames, will be decremented when callback is completed.
            callback = Callback.from(callback, numOutgoingFrames::decrementAndGet);
            if (numOutgoingFrames.incrementAndGet() > maxOutgoingFrames)
            {
                callback.failed(new WritePendingException());
                return;
            }
        }

        try
        {
            FrameValidation.assertValidOutgoing(frame, this);
        }
        catch (Throwable t)
        {
            LOG.warn("Invalid outgoing frame: {}", frame, t);
            callback.failed(t);
            return;
        }

        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("sendFrame({}, {}, {})", frame, callback, batch);

            boolean closeConnection = sessionState.onOutgoingFrame(frame);
            if (closeConnection)
            {
                Callback c = callback;
                Callback closeConnectionCallback = Callback.from(
                    () -> closeConnection(sessionState.getCloseStatus(), c),
                    t -> closeConnection(sessionState.getCloseStatus(), Callback.from(c, t)));

                flusher.sendFrame(frame, closeConnectionCallback, false);
            }
            else
            {
                flusher.sendFrame(frame, callback, batch);
            }
        }
        catch (Throwable t)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Failed sendFrame() {}", t.toString());

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
        flusher.sendFrame(FrameFlusher.FLUSH_FRAME, callback, false);
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

    @Override
    public int getMaxOutgoingFrames()
    {
        return maxOutgoingFrames;
    }

    @Override
    public void setMaxOutgoingFrames(int maxOutgoingFrames)
    {
        this.maxOutgoingFrames = maxOutgoingFrames;
    }

    private class IncomingAdaptor implements IncomingFrames
    {
        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            Callback closeCallback = null;
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("receiveFrame({}, {}) - connectionState={}, handler={}", frame, callback, sessionState, handler);

                boolean closeConnection = sessionState.onIncomingFrame(frame);

                // Handle inbound frame
                if (frame.getOpCode() != OpCode.CLOSE)
                {
                    Callback handlerCallback = isDemanding() ? callback : Callback.from(() ->
                    {
                        callback.succeeded();
                        autoDemand();
                    }, callback::failed);

                    handle(() -> handler.onFrame(frame, handlerCallback));
                    return;
                }

                // Handle inbound CLOSE
                connection.cancelDemand();
                if (closeConnection)
                {
                    closeCallback = Callback.from(() -> closeConnection(sessionState.getCloseStatus(), callback), t ->
                    {
                        sessionState.onError(t);
                        closeConnection(sessionState.getCloseStatus(), callback);
                    });
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
                if (closeCallback != null)
                    closeCallback.failed(t);
                else
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
    public WebSocketComponents getWebSocketComponents()
    {
        return components;
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

    private class Flusher extends FragmentingFlusher
    {
        public Flusher(Configuration configuration)
        {
            super(configuration);
        }

        @Override
        void forwardFrame(Frame frame, Callback callback, boolean batch)
        {
            negotiated.getExtensions().sendFrame(frame, callback, batch);
        }
    }
}
