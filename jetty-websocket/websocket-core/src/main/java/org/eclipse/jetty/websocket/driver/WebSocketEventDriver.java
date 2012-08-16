// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.driver;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Utf8Appendable.NotUtf8Exception;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.CloseException;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.io.IncomingFrames;
import org.eclipse.jetty.websocket.io.WebSocketSession;
import org.eclipse.jetty.websocket.io.message.MessageAppender;
import org.eclipse.jetty.websocket.io.message.MessageInputStream;
import org.eclipse.jetty.websocket.io.message.MessageReader;
import org.eclipse.jetty.websocket.io.message.SimpleBinaryMessage;
import org.eclipse.jetty.websocket.io.message.SimpleTextMessage;
import org.eclipse.jetty.websocket.protocol.CloseInfo;
import org.eclipse.jetty.websocket.protocol.Frame;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

/**
 * Responsible for routing the internally generated events destined for a specific WebSocket instance to whatever choice of development style the developer has
 * used to wireup their specific WebSocket implementation.
 * <p>
 * Supports WebSocket instances that either implement {@link WebSocketListener} or have used the {@link WebSocket &#064;WebSocket} annotation.
 * <p>
 * There will be an instance of the WebSocketEventDriver per connection.
 */
public class WebSocketEventDriver implements IncomingFrames
{
    private static final Logger LOG = Log.getLogger(WebSocketEventDriver.class);
    private final Logger socketLog;
    private final Object websocket;
    private final WebSocketPolicy policy;
    private final EventMethods events;
    private final ByteBufferPool bufferPool;
    private WebSocketSession session;
    private MessageAppender activeMessage;

    /**
     * Establish the driver for the Websocket POJO
     * 
     * @param websocket
     */
    public WebSocketEventDriver(Object websocket, EventMethodsCache methodsCache, WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        this.policy = policy;
        this.websocket = websocket;
        this.events = methodsCache.getMethods(websocket.getClass());
        this.bufferPool = bufferPool;

        this.socketLog = Log.getLogger(websocket.getClass());

        if (events.isAnnotated())
        {
            WebSocket anno = websocket.getClass().getAnnotation(WebSocket.class);
            // Setup the policy
            if (anno.maxBufferSize() > 0)
            {
                this.policy.setBufferSize(anno.maxBufferSize());
            }
            if (anno.maxBinarySize() > 0)
            {
                this.policy.setMaxBinaryMessageSize(anno.maxBinarySize());
            }
            if (anno.maxTextSize() > 0)
            {
                this.policy.setMaxTextMessageSize(anno.maxTextSize());
            }
            if (anno.maxIdleTime() > 0)
            {
                this.policy.setIdleTimeout(anno.maxIdleTime());
            }
        }
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    /**
     * Get the Websocket POJO in use
     * 
     * @return the Websocket POJO
     */
    public Object getWebSocketObject()
    {
        return websocket;
    }

    @Override
    public void incoming(WebSocketException e)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("{}.incoming({})",websocket.getClass().getSimpleName(),e);
        }

        if (e instanceof CloseException)
        {
            CloseException close = (CloseException)e;
            terminateConnection(close.getStatusCode(),close.getMessage());
        }

        if (events.onException != null)
        {
            events.onException.call(websocket,session,e);
        }
    }

    /**
     * Internal entry point for incoming frames
     * 
     * @param frame
     *            the frame that appeared
     */
    @Override
    public void incoming(WebSocketFrame frame)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("{}.onFrame({})",websocket.getClass().getSimpleName(),frame);
        }

        // Generic Read-Only Frame version
        if ((frame instanceof Frame) && (events.onFrame != null))
        {
            events.onFrame.call(websocket,session,frame);
            // DO NOT return; - as this is just a read-only notification.
        }

        try
        {
            switch (frame.getOpCode())
            {
                case OpCode.CLOSE:
                {
                    boolean validate = true;
                    CloseInfo close = new CloseInfo(frame,validate);
                    if (events.onClose != null)
                    {
                        events.onClose.call(websocket,session,close.getStatusCode(),close.getReason());
                    }
                    throw new CloseException(close.getStatusCode(),close.getReason());
                }
                case OpCode.PING:
                {
                    WebSocketFrame pong = new WebSocketFrame(OpCode.PONG);
                    if (frame.getPayloadLength() > 0)
                    {
                        // Copy payload
                        ByteBuffer pongBuf = ByteBuffer.allocate(frame.getPayloadLength());
                        BufferUtil.clearToFill(pongBuf);
                        BufferUtil.put(frame.getPayload(),pongBuf);
                        BufferUtil.flipToFlush(pongBuf,0);
                        pong.setPayload(pongBuf);
                        if (LOG.isDebugEnabled())
                        {
                            LOG.debug("Pong with {}",BufferUtil.toDetailString(pongBuf));
                        }
                    }
                    session.output("pong",new FutureCallback<String>(),pong);
                    break;
                }
                case OpCode.BINARY:
                {
                    if (events.onBinary == null)
                    {
                        // not interested in binary events
                        return;
                    }

                    if (activeMessage == null)
                    {
                        if (events.onBinary.isStreaming())
                        {
                            activeMessage = new MessageInputStream(websocket,events.onBinary,session,bufferPool,policy);
                        }
                        else
                        {
                            activeMessage = new SimpleBinaryMessage(websocket,events.onBinary,session,bufferPool,policy);
                        }
                    }

                    activeMessage.appendMessage(frame.getPayload());

                    if (frame.isFin())
                    {
                        activeMessage.messageComplete();
                        activeMessage = null;
                    }
                    return;
                }
                case OpCode.TEXT:
                {
                    if (events.onText == null)
                    {
                        // not interested in text events
                        return;
                    }

                    if (activeMessage == null)
                    {
                        if (events.onText.isStreaming())
                        {
                            activeMessage = new MessageReader(websocket,events.onBinary,session,policy);
                        }
                        else
                        {
                            activeMessage = new SimpleTextMessage(websocket,events.onText,session,policy);
                        }
                    }

                    activeMessage.appendMessage(frame.getPayload());

                    if (frame.isFin())
                    {
                        activeMessage.messageComplete();
                        activeMessage = null;
                    }
                    return;
                }
            }
        }
        catch (NotUtf8Exception e)
        {
            terminateConnection(StatusCode.BAD_PAYLOAD,e.getMessage());
        }
        catch (CloseException e)
        {
            terminateConnection(e.getStatusCode(),e.getMessage());
        }
        catch (Throwable t)
        {
            unhandled(t);
        }
    }

    /**
     * Internal entry point for connection established
     */
    public void onConnect()
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("{}.onConnect()",websocket.getClass().getSimpleName());
        }
        events.onConnect.call(websocket,session);
    }

    /**
     * Set the connection to use for this driver
     * 
     * @param conn
     *            the connection
     */
    public void setSession(WebSocketSession conn)
    {
        this.session = conn;
    }

    private void terminateConnection(int statusCode, String rawreason)
    {
        String reason = rawreason;
        if (StringUtil.isNotBlank(reason))
        {
            // Trim big exception messages here.
            if (reason.length() > (WebSocketFrame.MAX_CONTROL_PAYLOAD - 2))
            {
                reason = reason.substring(0,WebSocketFrame.MAX_CONTROL_PAYLOAD - 2);
            }
        }
        LOG.debug("terminateConnection({},{})",statusCode,rawreason);
        session.close(statusCode,reason);
    }

    @Override
    public String toString()
    {
        return websocket.getClass().getName();
    }

    private void unhandled(Throwable t)
    {
        socketLog.warn("Unhandled Error (closing connection)",t);

        // Unhandled Error, close the connection.
        switch (policy.getBehavior())
        {
            case SERVER:
                terminateConnection(StatusCode.SERVER_ERROR,t.getClass().getSimpleName());
                break;
            case CLIENT:
                terminateConnection(StatusCode.POLICY_VIOLATION,t.getClass().getSimpleName());
                break;
        }
    }
}
