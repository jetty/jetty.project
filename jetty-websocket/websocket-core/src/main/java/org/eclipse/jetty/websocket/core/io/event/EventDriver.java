//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.io.event;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Utf8Appendable.NotUtf8Exception;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.api.BaseConnection;
import org.eclipse.jetty.websocket.core.api.CloseException;
import org.eclipse.jetty.websocket.core.api.StatusCode;
import org.eclipse.jetty.websocket.core.api.WebSocketException;
import org.eclipse.jetty.websocket.core.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.io.IncomingFrames;
import org.eclipse.jetty.websocket.core.io.WebSocketSession;
import org.eclipse.jetty.websocket.core.protocol.CloseInfo;
import org.eclipse.jetty.websocket.core.protocol.OpCode;
import org.eclipse.jetty.websocket.core.protocol.WebSocketFrame;

/**
 * EventDriver is the main interface between the User's WebSocket POJO and the internal jetty implementation of WebSocket.
 */
public abstract class EventDriver implements IncomingFrames
{
    protected final Logger LOG;
    protected final WebSocketPolicy policy;
    protected final Object websocket;
    protected WebSocketSession session;

    public EventDriver(WebSocketPolicy policy, Object websocket)
    {
        this.policy = policy;
        this.websocket = websocket;
        this.LOG = Log.getLogger(websocket.getClass());
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    public WebSocketSession getSession()
    {
        return session;
    }

    @Override
    public final void incoming(WebSocketException e)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("incoming({})",e);
        }

        if (e instanceof CloseException)
        {
            CloseException close = (CloseException)e;
            terminateConnection(close.getStatusCode(),close.getMessage());
        }

        onException(e);
    }

    @Override
    public final void incoming(WebSocketFrame frame)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("{}.onFrame({})",websocket.getClass().getSimpleName(),frame);
        }

        onFrame(frame);

        try
        {
            switch (frame.getOpCode())
            {
                case OpCode.CLOSE:
                {
                    boolean validate = true;
                    CloseInfo close = new CloseInfo(frame,validate);
                    onClose(close);

                    // Is this close frame a response to a prior close?
                    if (session.getState() == BaseConnection.State.CLOSING)
                    {
                        // Then this is close response handshake (to a prior
                        // outgoing close frame)
                        session.disconnect();
                    }
                    else
                    {
                        // This is the initiator for a close handshake
                        // Trigger close response handshake.
                        session.notifyClosing();
                        session.close(close.getStatusCode(),close.getReason());
                    }
                    return;
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
                    onBinaryFrame(frame.getPayload(),frame.isFin());
                    return;
                }
                case OpCode.TEXT:
                {
                    onTextFrame(frame.getPayload(),frame.isFin());
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

    public abstract void onBinaryFrame(ByteBuffer buffer, boolean fin) throws IOException;

    public abstract void onBinaryMessage(byte[] data);

    public abstract void onClose(CloseInfo close);

    public abstract void onConnect();

    public abstract void onException(WebSocketException e);

    public abstract void onFrame(WebSocketFrame frame);

    public abstract void onTextFrame(ByteBuffer buffer, boolean fin) throws IOException;

    public abstract void onTextMessage(String message);

    public void setSession(WebSocketSession session)
    {
        this.session = session;
    }

    protected void terminateConnection(int statusCode, String rawreason)
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

    private void unhandled(Throwable t)
    {
        LOG.warn("Unhandled Error (closing connection)",t);

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
