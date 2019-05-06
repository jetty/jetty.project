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
import java.nio.channels.ClosedChannelException;
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
import org.eclipse.jetty.websocket.core.Extension;
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

import static org.eclipse.jetty.util.Callback.NOOP;

/**
 * The Core WebSocket Session.
 */
public class WebSocketChannel implements IncomingFrames, FrameHandler.CoreSession, Dumpable
{
    private Logger LOG = Log.getLogger(this.getClass());
    private final static CloseStatus NO_CODE = new CloseStatus(CloseStatus.NO_CODE);

    private final Behavior behavior;
    private final WebSocketChannelState channelState = new WebSocketChannelState();
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
    private Duration idleTimeout;
    private Duration idleWriteTimeout;

    public WebSocketChannel(FrameHandler handler,
        Behavior behavior,
        Negotiated negotiated)
    {
        this.handler = handler;
        this.behavior = behavior;
        this.negotiated = negotiated;
        this.demanding = handler.isDemanding();
        negotiated.getExtensions().initialize(new IncomingAdaptor(), new OutgoingAdaptor(), this);
    }

    /**
     * @return True if the channels handling is demanding.
     */
    public boolean isDemanding()
    {
        return demanding;
    }

    public void assertValidIncoming(Frame frame)
    {
        assertValid(frame);

        // Assert Behavior Required by RFC-6455 / Section 5.1
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
        }
    }

    public void assertValidOutgoing(Frame frame) throws CloseException
    {
        // TODO check that it is not masked, since masking is done later

        if (!OpCode.isKnown(frame.getOpCode()))
            throw new ProtocolException("Unknown opcode: " + frame.getOpCode());

        assertValid(frame);

        int payloadLength = (frame.getPayload() == null)?0:frame.getPayload().remaining();

        if (frame.isControlFrame())
        {
            if (!frame.isFin())
                throw new ProtocolException("Fragmented Control Frame [" + OpCode.name(frame.getOpCode()) + "]");

            if (payloadLength > Frame.MAX_CONTROL_PAYLOAD)
                throw new ProtocolException("Invalid control frame payload length, [" + payloadLength + "] cannot exceed [" + Frame.MAX_CONTROL_PAYLOAD + "]");
        }
    }

    private void assertValid(Frame frame)
    {

        // Control Frame Validation
        if (frame.isControlFrame())
        {
            if (frame.isRsv1())
                throw new ProtocolException("Cannot have RSV1==true on Control frames");

            if (frame.isRsv2())
                throw new ProtocolException("Cannot have RSV2==true on Control frames");

            if (frame.isRsv3())
                throw new ProtocolException("Cannot have RSV3==true on Control frames");


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
        else
        {
            // TODO should we validate UTF-8 for text frames
            if (frame.getOpCode() == OpCode.TEXT)
            {
            }
        }

        /*
         * RFC 6455 Section 5.2
         *
         * MUST be 0 unless an extension is negotiated that defines meanings for non-zero values. If a nonzero value is received and none of the negotiated
         * extensions defines the meaning of such a nonzero value, the receiving endpoint MUST _Fail the WebSocket Connection_.
         */
        //TODO save these values to not iterate through extensions every frame
        List<? extends Extension> exts = getExtensionStack().getExtensions();

        boolean isRsv1InUse = false;
        boolean isRsv2InUse = false;
        boolean isRsv3InUse = false;
        for (Extension ext : exts)
        {
            if (ext.isRsv1User())
                isRsv1InUse = true;
            if (ext.isRsv2User())
                isRsv2InUse = true;
            if (ext.isRsv3User())
                isRsv3InUse = true;
        }

        if (frame.isRsv1() && !isRsv1InUse)
            throw new ProtocolException("RSV1 not allowed to be set");

        if (frame.isRsv2() && !isRsv2InUse)
            throw new ProtocolException("RSV2 not allowed to be set");

        if (frame.isRsv3() && !isRsv3InUse)
            throw new ProtocolException("RSV3 not allowed to be set");
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
        if (getConnection() == null)
            return idleTimeout;
        else
            return Duration.ofMillis(getConnection().getEndPoint().getIdleTimeout());
    }

    @Override
    public void setIdleTimeout(Duration timeout)
    {
        if (getConnection() == null)
            idleTimeout = timeout;
        else
            getConnection().getEndPoint().setIdleTimeout(timeout.toMillis());
    }

    @Override
    public Duration getWriteTimeout()
    {
        if (getConnection() == null)
            return idleWriteTimeout;
        else
            return Duration.ofMillis(getConnection().getFrameFlusher().getIdleTimeout());
    }

    @Override
    public void setWriteTimeout(Duration timeout)
    {
        if (getConnection() == null)
            idleWriteTimeout = timeout;
        else
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
        return channelState.isOutputOpen();
    }

    public boolean isClosed()
    {
        return channelState.isClosed();
    }

    public void setWebSocketConnection(WebSocketConnection connection)
    {
        this.connection = connection;

        if (idleTimeout != null)
        {
            getConnection().getEndPoint().setIdleTimeout(idleTimeout.toMillis());
            idleTimeout = null;
        }

        if (idleWriteTimeout != null)
        {
            getConnection().getFrameFlusher().setIdleTimeout(idleWriteTimeout.toMillis());
            idleWriteTimeout = null;
        }
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
     * @param reason     an optional reason phrase
     * @param callback   the callback on successful send of close frame
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

        if (channelState.onEof())
            closeConnection(new ClosedChannelException(), channelState.getCloseStatus(), Callback.NOOP);
    }

    public void closeConnection(Throwable cause, CloseStatus closeStatus, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("closeConnection() {} {} {}", closeStatus, this, cause);

        connection.cancelDemand();
        if (connection.getEndPoint().isOpen())
            connection.close();

        // Forward Errors to Local WebSocket EndPoint
        if (cause!=null)
        {
            Callback errorCallback = Callback.from(()->
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

            try
            {
                handler.onError(cause,errorCallback);
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

        AbnormalCloseStatus closeStatus = new AbnormalCloseStatus(code, cause);
        if (CloseStatus.isTransmittableStatusCode(code))
            close(closeStatus, callback);
        else
        {
            if (channelState.onClosed(closeStatus))
                closeConnection(cause, closeStatus, callback);
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

        close(new AbnormalCloseStatus(code, cause), callback);
    }

    /**
     * Open/Activate the session.
     */
    public void onOpen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onOpen() {}", this);

        // Upgrade success
        channelState.onConnected();
        if (LOG.isDebugEnabled())
            LOG.debug("ConnectionState: Transition to CONNECTED");

        Callback openCallback = Callback.from(()->
                {
                    channelState.onOpen();
                    if (!demanding)
                        connection.demand(1);
                    if (LOG.isDebugEnabled())
                        LOG.debug("ConnectionState: Transition to OPEN");
                },
                x->
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
        if (!channelState.isInputOpen())
            throw new IllegalStateException("FrameHandler input not open: " + this); // TODO Perhaps this is a NOOP?
        connection.demand(n);
    }

    public WebSocketConnection getConnection()
    {
        return this.connection;
    }

    public Executor getExecutor()
    {
        return this.connection.getExecutor();
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
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
                LOG.warn("Invalid outgoing frame: {}", frame);

            callback.failed(t);
            return;
        }

        try
        {
            synchronized(flusher)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("sendFrame({}, {}, {})", frame, callback, batch);

                boolean closeConnection = channelState.onOutgoingFrame(frame);
                if (closeConnection)
                {
                    Throwable cause = AbnormalCloseStatus.getCause(CloseStatus.getCloseStatus(frame));

                    Callback closeConnectionCallback = Callback.from(
                            ()->closeConnection(cause, channelState.getCloseStatus(), callback),
                            t->closeConnection(cause, channelState.getCloseStatus(), Callback.from(callback, t)));

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
                if (closeStatus instanceof AbnormalCloseStatus && channelState.onClosed(closeStatus))
                    closeConnection(AbnormalCloseStatus.getCause(closeStatus), closeStatus, Callback.from(callback, t));
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
        synchronized(flusher)
        {
            flusher.queue.offer(new FrameEntry(FrameFlusher.FLUSH_FRAME, callback, false));
        }
        flusher.iterate();
    }

    @Override
    public void abort()
    {
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
                    LOG.debug("receiveFrame({}, {}) - connectionState={}, handler={}",
                            frame, callback, channelState, handler);

                boolean closeConnection = channelState.onIncomingFrame(frame);

                // Handle inbound frame
                if (frame.getOpCode() != OpCode.CLOSE)
                {
                    handler.onFrame(frame, callback);
                    return;
                }

                // Handle inbound CLOSE
                connection.cancelDemand();
                Callback closeCallback ;

                if (closeConnection)
                {
                    closeCallback = Callback.from(()-> closeConnection(null, channelState.getCloseStatus(), callback));
                }
                else
                {
                    closeCallback = Callback.from(()->
                    {
                        if (channelState.isOutputOpen())
                        {
                            CloseStatus closeStatus = CloseStatus.getCloseStatus(frame);
                            if (LOG.isDebugEnabled())
                                LOG.debug("ConnectionState: sending close response {}", closeStatus);
                            close(closeStatus==null ? CloseStatus.NO_CODE_STATUS : closeStatus, callback);
                        }
                        else
                        {
                            callback.succeeded();
                        }
                    },
                    x->processHandlerError(x,callback));
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
            "subprotocol="+negotiated.getSubProtocol(),
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
        return String.format("WSChannel@%x{%s,%s,%s,af=%b,i/o=%d/%d,fs=%d}->%s",
            hashCode(),
            behavior,
            channelState,
            negotiated,
            autoFragment,
            inputBufferSize,
            outputBufferSize,
            maxFrameSize,
            handler);
    }

    static class AbnormalCloseStatus extends CloseStatus
    {
        final Throwable cause;
        public AbnormalCloseStatus(int statusCode, Throwable cause)
        {
            super(statusCode, cause.getMessage());
            this.cause = cause;
        }

        public Throwable getCause()
        {
            return cause;
        }

        public static Throwable getCause(CloseStatus status)
        {
            if (status instanceof AbnormalCloseStatus)
                return ((AbnormalCloseStatus)status).getCause();
            return null;
        }

        @Override
        public String toString()
        {
            return "Abnormal" + super.toString() + ":" + cause;
        }
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
            if (entry==null)
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
            entries.forEach(e-> failEntry(cause, e));
        }

        private void failEntry(Throwable cause, FrameEntry e)
        {
            try
            {
                e.callback.failed(cause);
            }
            catch(Throwable x)
            {
                if (cause != x)
                    cause.addSuppressed(x);
                LOG.warn(cause);
            }
        }
    }

}
