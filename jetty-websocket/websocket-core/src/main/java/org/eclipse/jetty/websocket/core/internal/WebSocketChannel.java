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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
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
import org.eclipse.jetty.websocket.core.internal.Parser.ParsedFrame;

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

    private WebSocketConnection connection;
    private boolean autoFragment = WebSocketConstants.DEFAULT_AUTO_FRAGMENT;
    private long maxFrameSize = WebSocketConstants.DEFAULT_MAX_FRAME_SIZE;
    private int inputBufferSize = WebSocketConstants.DEFAULT_INPUT_BUFFER_SIZE;
    private int outputBufferSize = WebSocketConstants.DEFAULT_OUTPUT_BUFFER_SIZE;
    private long maxBinaryMessageSize = WebSocketConstants.DEFAULT_MAX_BINARY_MESSAGE_SIZE;
    private long maxTextMessageSize = WebSocketConstants.DEFAULT_MAX_TEXT_MESSAGE_SIZE;

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
        return Duration.ofMillis(getConnection().getEndPoint().getIdleTimeout());
    }

    @Override
    public void setIdleTimeout(Duration timeout)
    {
        getConnection().getEndPoint().setIdleTimeout(timeout == null?0:timeout.toMillis());
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
    }

    /**
     * Send Close Frame with no payload.
     *
     * @param callback the callback on successful send of close frame
     */
    @Override
    public void close(Callback callback)
    {
        close(NO_CODE, callback, false);
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
        close(new CloseStatus(statusCode, reason), callback, false);
    }

    private void close(CloseStatus closeStatus, Callback callback, boolean batch)
    {
        sendFrame(closeStatus.toFrame(), callback, batch);
    }

    @Override
    public ByteBufferPool getByteBufferPool()
    {
        return this.connection.getBufferPool();
    }

    public void onClosed(Throwable cause)
    {

        CloseStatus closeStatus = new CloseStatus(CloseStatus.NO_CLOSE, cause == null?null:cause.toString());
        if (channelState.onClosed(closeStatus))
            onClosed(cause, closeStatus);
    }

    public void onClosed(Throwable cause, CloseStatus closeStatus)
    {
        connection.cancelDemand();

        // Forward Errors to Local WebSocket EndPoint
        if (cause!=null)
        {
            try
            {
                handler.onError(cause);
            }
            catch (Throwable e)
            {
                cause.addSuppressed(e);
                LOG.warn(cause);
            }
        }

        try
        {
            handler.onClosed(closeStatus);
        }
        catch (Throwable e)
        {
            LOG.warn(e);
        }

        if (connection.getEndPoint().isOpen())
            connection.close();
    }

    AbnormalCloseStatus abnormalCloseStatusFor(Throwable cause)
    {
        int code;
        if (cause instanceof ProtocolException)
            code = CloseStatus.PROTOCOL;
        else if (cause instanceof CloseException)
            code = ((CloseException)cause).getStatusCode();
        else if (cause instanceof Utf8Appendable.NotUtf8Exception)
            code = CloseStatus.BAD_PAYLOAD;
        else if (cause instanceof WebSocketTimeoutException || cause instanceof TimeoutException || cause instanceof SocketTimeoutException)
            code = CloseStatus.SHUTDOWN;
        else if (behavior == Behavior.CLIENT)
            code = CloseStatus.POLICY_VIOLATION;
        else
            code = CloseStatus.SERVER_ERROR;

        return new AbnormalCloseStatus(code, cause.getMessage());
    }

    /**
     * Process an Error that originated from the connection.
     * For protocol causes, send and abnormal close frame
     * otherwise just close the connection.
     *
     * @param cause the cause
     */
    public void processConnectionError(Throwable cause)
    {
        CloseStatus closeStatus = abnormalCloseStatusFor(cause);

        if (closeStatus.getCode() == CloseStatus.PROTOCOL)
            close(closeStatus, Callback.NOOP, false);
        else if (channelState.onClosed(closeStatus))
            onClosed(cause, closeStatus);
    }

    /**
     * Process an Error that originated from the handler.
     * Send an abnormal close frame to ensure connection is closed.
     *
     * @param cause the cause
     */
    public void processHandlerError(Throwable cause)
    {
        close(abnormalCloseStatusFor(cause), Callback.NOOP, false);
    }

    /**
     * Open/Activate the session.
     */
    public void onOpen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onOpen() {}", this);

        try
        {
            // Upgrade success
            channelState.onConnected();

            if (LOG.isDebugEnabled())
                LOG.debug("ConnectionState: Transition to CONNECTED");

            // Open connection and handler
            channelState.onOpen();
            handler.onOpen(this);
            if (!demanding)
                connection.demand(1);

            if (LOG.isDebugEnabled())
                LOG.debug("ConnectionState: Transition to OPEN");
        }
        catch (Throwable t)
        {
            LOG.warn("Error during OPEN", t);
            try
            {
                handler.onError(t);
            }
            catch (Exception e)
            {
                t.addSuppressed(e);
            }
            processHandlerError(new CloseException(CloseStatus.SERVER_ERROR, t));
        }
    }

    @Override
    public void demand(long n)
    {
        if (!demanding)
            throw new IllegalStateException();
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
        catch (Throwable ex)
        {
            callback.failed(ex);
            return;
        }

        negotiated.getExtensions().onFrame(frame, callback);
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, boolean batch)
    {
        synchronized(this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("sendFrame({}, {}, {})", frame, callback, batch);

            try
            {
                assertValidOutgoing(frame);
            }
            catch (Throwable ex)
            {
                callback.failed(ex);
                return;
            }

            boolean closeConnection;
            try
            {
                closeConnection = channelState.onOutgoingFrame(frame);
            }
            catch (Throwable ex)
            {
                try
                {
                    callback.failed(ex);
                }
                finally
                {
                    if (frame.getOpCode() == OpCode.CLOSE && CloseStatus.getCloseStatus(frame) instanceof AbnormalCloseStatus)
                    {
                        try
                        {
                            handler.onClosed(CloseStatus.getCloseStatus(frame));
                        }
                        finally
                        {
                            connection.close();
                        }
                    }
                }
                return;
            }


            if (frame.getOpCode() == OpCode.CLOSE)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("close({}, {}, {})", CloseStatus.getCloseStatus(frame), callback, batch);

                if (closeConnection)
                {
                    callback = new Callback.Nested(callback)
                    {
                        @Override
                        public void completed()
                        {
                            onClosed(null, channelState.getCloseStatus());
                        }
                    };
                }
            }

            negotiated.getExtensions().sendFrame(frame, callback, batch);
        }
        connection.sendFrameQueue();
    }

    @Override
    public void flush(Callback callback)
    {
        synchronized(this)
        {
            negotiated.getExtensions().sendFrame(FrameFlusher.FLUSH_FRAME, callback, false);
        }
        connection.sendFrameQueue();
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
        public void onFrame(Frame frame, Callback callback)
        {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("receiveFrame({}, {}) - connectionState={}, handler={}",
                            frame, callback, channelState, handler);

                boolean closeConnection = channelState.onIncomingFrame(frame);

                // Handle inbound close
                if (frame.getOpCode() == OpCode.CLOSE)
                {
                    connection.cancelDemand();
                    if (closeConnection)
                    {
                        callback = new Callback.Nested(callback)
                        {
                            @Override
                            public void completed()
                            {
                                handler.onClosed(channelState.getCloseStatus());
                                connection.close();
                            }
                        };
                        handler.onFrame(frame, callback);
                        return;
                    }

                    callback = new Callback.Nested(callback)
                    {
                        @Override
                        public void completed()
                        {
                            if (channelState.isOutputOpen())
                            {
                                CloseStatus closeStatus = CloseStatus.getCloseStatus(frame);

                                if (LOG.isDebugEnabled())
                                    LOG.debug("ConnectionState: sending close response {}", closeStatus);

                                // this may race with a rare application close but errors are ignored
                                if (closeStatus==null)
                                    closeStatus = CloseStatus.NO_CODE_STATUS;
                                close(closeStatus.getCode(), closeStatus.getReason(), Callback.NOOP);
                            }
                        }
                    };
                }

                // Handle the frame
                handler.onFrame(frame, callback);
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
        public AbnormalCloseStatus(int statusCode, String reasonPhrase)
        {
            super(statusCode, reasonPhrase);
        }
    }
}
