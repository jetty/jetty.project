//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8Appendable;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.Parser.ParsedFrame;
import org.eclipse.jetty.websocket.core.extensions.Extension;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.frames.Frame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.io.BatchMode;
import org.eclipse.jetty.websocket.core.io.FrameFlusher;
import org.eclipse.jetty.websocket.core.io.WebSocketConnection;

/**
 * The Core WebSocket Session.
 *
 */
public class WebSocketChannel implements IncomingFrames, FrameHandler.CoreSession, Dumpable
{
    private Logger LOG = Log.getLogger(this.getClass());
    private final static CloseStatus NO_CODE = new CloseStatus(CloseStatus.NO_CODE);

    private final WebSocketChannelState state = new WebSocketChannelState();
    private final WebSocketPolicy policy;
    private final FrameHandler handler;
    private final ExtensionStack extensionStack;
    private final String subprotocol;
    private final AttributesMap attributes = new AttributesMap();
    private final boolean demanding;
    OpCode.Sequence outgoingSequence = new OpCode.Sequence();

    private WebSocketConnection connection;

    public WebSocketChannel(FrameHandler handler,
    		WebSocketPolicy policy,
    		ExtensionStack extensionStack,
    		String subprotocol)
    {
        this.handler = handler;
        this.policy = policy;
        this.extensionStack = extensionStack;
        this.subprotocol = subprotocol;
        this.demanding = handler.isDemanding();
        extensionStack.connect(new IncomingState(),new OutgoingState());
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
        if (policy.getBehavior() == WebSocketBehavior.SERVER)
        {
            if (!frame.isMasked())
                throw new ProtocolException("Client MUST mask all frames (RFC-6455: Section 5.1)");
        }
        else if (policy.getBehavior() == WebSocketBehavior.CLIENT)
        {
            if (frame.isMasked())
                throw new ProtocolException("Server MUST NOT mask any frames (RFC-6455: Section 5.1)");
        }
    }

    public void assertValidOutgoing(Frame frame) throws CloseException
    {
        if (!OpCode.isKnown(frame.getOpCode()))
            throw new ProtocolException("Unknown opcode: " + frame.getOpCode());

        assertValid(frame);

        int payloadLength = (frame.getPayload()==null) ? 0 : frame.getPayload().remaining(); // TODO is this accurately getting length of payload

        // Sane Payload Length
        if (payloadLength > policy.getMaxAllowedFrameSize())
            throw new MessageTooLargeException("Cannot handle payload lengths larger than " + policy.getMaxAllowedFrameSize());

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
                    new CloseStatus(frame.getPayload());
            }
        }

        /*
         * RFC 6455 Section 5.2
         *
         * MUST be 0 unless an extension is negotiated that defines meanings for non-zero values. If a nonzero value is received and none of the negotiated
         * extensions defines the meaning of such a nonzero value, the receiving endpoint MUST _Fail the WebSocket Connection_.
         */
        List<? extends Extension > exts = getExtensionStack().getExtensions();

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
        return extensionStack;
    }

    public FrameHandler getHandler()
    {
        return handler;
    }

    @Override
    public String getSubprotocol()
    {
        return subprotocol;
    }

    @Override
    public long getIdleTimeout(TimeUnit units)
    {
        return TimeUnit.MILLISECONDS.convert(getPolicy().getIdleTimeout(),units);
    }
    
    @Override
    public void setIdleTimeout(long timeout, TimeUnit units)
    {
        getConnection().getEndPoint().setIdleTimeout(units.toMillis(timeout));
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
    public boolean isOpen()
    {
        return state.isOutOpen();
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
        close(NO_CODE, callback, BatchMode.OFF);
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
        close(new CloseStatus(statusCode, reason), callback, BatchMode.OFF);
    }

    private void close(CloseStatus closeStatus, Callback callback, BatchMode batchMode)
    {
        if (state.onCloseOut(closeStatus))
        {
            callback = new Callback.Nested(callback)
            {
                @Override
                public void completed()
                {
                    try
                    {
                        handler.onClosed(state.getCloseStatus());
                    }
                    catch(Throwable e)
                    {
                        try
                        {
                            handler.onError(e);
                        }
                        catch(Throwable e2)
                        {
                            e.addSuppressed(e2);
                            LOG.warn(e);
                        }
                    }
                    finally
                    {
                        connection.close();
                    }
                }
            };
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("close({}, {}, {})", closeStatus, callback, batchMode);
        }

        Frame frame = closeStatus.toFrame();
        extensionStack.sendFrame(frame,callback,batchMode);
    }
    
    public WebSocketPolicy getPolicy()
    {
        return policy;
    }


    public void onClosed(Throwable cause)
    {
        onClosed(cause, new CloseStatus(CloseStatus.NO_CLOSE, cause==null?null:cause.toString()));
    }

    public void onClosed(Throwable cause, CloseStatus closeStatus)
    {
        if (state.onClosed(closeStatus))
        {
            // Forward Errors to Local WebSocket EndPoint
            try
            {
                handler.onError(cause);
            }
            catch(Throwable e)
            {
                cause.addSuppressed(e);
                LOG.warn(cause);
            }

            try
            {
                handler.onClosed(closeStatus);
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
        }
    }


    /**
     * Process an Error event seen by the Session and/or Connection
     *
     * @param cause the cause
     */
    public void processError(Throwable cause)
    {
        CloseStatus closeStatus;

        //TODO review everything below (at the moment we have no non terminal error handling)
        if (cause instanceof Utf8Appendable.NotUtf8Exception)
        {
            closeStatus = new CloseStatus(WebSocketConstants.BAD_PAYLOAD, cause.getMessage());
        }
        else if (cause instanceof SocketTimeoutException)
        {
            // A path often seen in Windows
            closeStatus = new CloseStatus(WebSocketConstants.SHUTDOWN, cause.getMessage());
        }
        else if (cause instanceof IOException)
        {
            closeStatus = new CloseStatus(WebSocketConstants.PROTOCOL, cause.getMessage());
        }
        else if (cause instanceof SocketException)
        {
            // A path unique to Unix
            closeStatus = new CloseStatus(WebSocketConstants.SHUTDOWN, cause.getMessage());
        }
        else if (cause instanceof CloseException)
        {
            CloseException ce = (CloseException) cause;
            Callback callback = Callback.NOOP;

            // Force disconnect for protocol breaking status codes
            switch (ce.getStatusCode())
            {
                case WebSocketConstants.PROTOCOL:
                case WebSocketConstants.BAD_DATA:
                case WebSocketConstants.BAD_PAYLOAD:
                case WebSocketConstants.MESSAGE_TOO_LARGE:
                case WebSocketConstants.POLICY_VIOLATION:
                case WebSocketConstants.SERVER_ERROR:
                {
                    callback = Callback.NOOP; // TODO remove?
                }
            }

            closeStatus = new CloseStatus(ce.getStatusCode(), ce.getMessage());

        }
        else if (cause instanceof WebSocketTimeoutException)
        {
            closeStatus = new CloseStatus(WebSocketConstants.SHUTDOWN, cause.getMessage());
        }
        else
        {
            LOG.warn("Unhandled Error (closing connection)", cause);

            // Exception on end-user WS-Endpoint.
            // Fast-fail & close connection with reason.
            int statusCode = WebSocketConstants.SERVER_ERROR;
            if (getPolicy().getBehavior() == WebSocketBehavior.CLIENT)
            {
                statusCode = WebSocketConstants.POLICY_VIOLATION;
            }

            closeStatus = new CloseStatus(statusCode, cause.getMessage());
        }

        // TODO can we avoid the illegal state exception in outClosed
        try
        {
            close(closeStatus, Callback.NOOP, BatchMode.OFF);
        }
        catch(IllegalStateException e)
        {
            if (cause==null)
                cause=e;
            else
                cause.addSuppressed(e);
        }
        onClosed(cause, closeStatus);
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
            state.onConnected();

            if (LOG.isDebugEnabled())
                LOG.debug("ConnectionState: Transition to CONNECTED");

            try
            {
                // Open connection and handler
                state.onOpen();
                handler.onOpen(this);
                if (!demanding)
                    connection.demand(1);
                    
                if (LOG.isDebugEnabled())
                    LOG.debug("ConnectionState: Transition to OPEN");
            }
            catch (Throwable t)
            {
                LOG.warn("Error during OPEN", t);
                processError(new CloseException(WebSocketConstants.SERVER_ERROR, t));
            }
        }
        catch (Throwable t)
        {
            processError(t); // Handle error
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

    public ByteBufferPool getBufferPool()
    {
        return this.connection.getBufferPool();
    }

    @Override
    public void onReceiveFrame(Frame frame, Callback callback)
    {
        try
        {
            assertValidIncoming(frame);
        }
        catch (CloseException ce)
        {
            callback.failed(ce);
        }

        if (frame.getOpCode() == OpCode.PING)
        {
            // TODO remember we have received this ping and need to send a pong
        }

        extensionStack.onReceiveFrame(frame, callback);
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, BatchMode batchMode) 
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendFrame({}, {}, {})", frame, callback, batchMode);
        }

        if (frame.getOpCode() == OpCode.PONG)
        {
            // TODO try to mark one of our ping frames as responded to if payload matches
        }
        // TODO do we also need to verify that outgoing ping frames are responded too

        try
        {
            assertValidOutgoing(frame);
            outgoingSequence.check(frame.getOpCode(),frame.isFin());
        }
        catch (CloseException ce)
        {
            callback.failed(ce);
        }

        if (frame.getOpCode() == OpCode.CLOSE)
        {
            close(new CloseStatus(frame.getPayload()), callback, batchMode);
        }
        else
        {
            extensionStack.sendFrame(frame, callback, batchMode);
        }
    }


    @Override
    public void flushBatch(Callback callback)
    {
        extensionStack.sendFrame(FrameFlusher.FLUSH_FRAME,callback,BatchMode.OFF);
    }
    
    @Override
    public void abort()
    {
        connection.cancelDemand();
        connection.getEndPoint().close();
    }
    
    private class IncomingState extends OpCode.Sequence implements IncomingFrames
    {
        @Override
        public void onReceiveFrame(Frame frame, Callback callback)
        {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("receiveFrame({}, {}) - connectionState={}, handler={}",
                              frame, callback, state, handler);

                check(frame.getOpCode(),frame.isFin());
                if (state.isInOpen())
                {   
                    // Handle inbound close
                    if (frame.getOpCode() == OpCode.CLOSE)
                    {
                        
                        CloseStatus closeStatus = ((Parser.ParsedFrame)frame).getCloseStatus();
                        if (state.onCloseIn(closeStatus))
                        {
                            handler.onReceiveFrame(frame, callback); // handler should know about received frame
                            handler.onClosed(state.getCloseStatus());
                            connection.close();
                            return;
                        }

                        callback = new Callback.Nested(callback)
                        {
                            @Override
                            public void completed()
                            {
                                // was a close sent by the handler?
                                if (state.isOutOpen())
                                {
                                    // No!
                                    if (LOG.isDebugEnabled())
                                        LOG.debug("ConnectionState: sending close response {}",closeStatus);

                                    close(closeStatus.getCode(), closeStatus.getReason(), Callback.NOOP);
                                    return;
                                }
                            }
                        };
                    }
                    
                    // Handle the frame
                    handler.onReceiveFrame(frame, callback);
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Discarding post EOF frame - {}", frame);
                    callback.failed(new EofException());
                }
            }
            catch (Throwable t)
            {
                callback.failed(t);
            }
        }
    }

    private class OutgoingState implements OutgoingFrames
    {
        @Override
        public void sendFrame(Frame frame, Callback callback, BatchMode batchMode)
        {
            try
            {
                connection.sendFrame(frame,callback,batchMode);
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
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        ContainerLifeCycle.dumpObject(out,this);
        ContainerLifeCycle.dump(out,indent,Arrays.asList(subprotocol,policy,extensionStack,handler));
    }


    @Override
    public List<ExtensionConfig> getExtensionConfig()
    {
        return extensionStack.getNegotiatedExtensions();
    }


    @Override
    public WebSocketBehavior getBehavior()
    {
        return policy.getBehavior();
    }
    
    @Override
    public void removeAttribute(String name)
    {
        attributes.removeAttribute(name);
    }

    @Override
    public void setAttribute(String name, Object attribute)
    {
        attributes.setAttribute(name,attribute);
    }

    @Override
    public Object getAttribute(String name)
    {
        return attributes.getAttribute(name);
    }

    @Override
    public Enumeration<String> getAttributeNames()
    {
        return attributes.getAttributeNames();
    }

    @Override
    public void clearAttributes()
    {
        attributes.clearAttributes();
    }
    
    @Override
    public String toString()
    {
        return String.format("WSChannel@%x{%s,x=%d}->%s",hashCode(),state,extensionStack.getExtensions().size(),handler);
    }
}
