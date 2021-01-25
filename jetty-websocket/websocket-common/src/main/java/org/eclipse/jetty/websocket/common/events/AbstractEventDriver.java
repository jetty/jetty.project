//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.common.events;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Utf8Appendable.NotUtf8Exception;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BadPayloadException;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.frames.CloseFrame;
import org.eclipse.jetty.websocket.common.message.MessageAppender;

/**
 * EventDriver is the main interface between the User's WebSocket POJO and the internal jetty implementation of WebSocket.
 */
public abstract class AbstractEventDriver extends AbstractLifeCycle implements IncomingFrames, EventDriver
{
    private final Logger logger;
    protected final Logger targetLog;
    protected WebSocketPolicy policy;
    protected final Object websocket;
    protected WebSocketSession session;
    protected MessageAppender activeMessage;

    public AbstractEventDriver(WebSocketPolicy policy, Object websocket)
    {
        this.logger = Log.getLogger(this.getClass());
        this.policy = policy;
        this.websocket = Objects.requireNonNull(websocket, "WebSocket endpoint may not be null");
        this.targetLog = Log.getLogger(websocket.getClass());
    }

    protected void appendMessage(ByteBuffer buffer, boolean fin) throws IOException
    {
        activeMessage.appendFrame(buffer, fin);

        if (fin)
        {
            activeMessage.messageComplete();
            activeMessage = null;
        }
    }

    protected void dispatch(Runnable runnable)
    {
        session.dispatch(runnable);
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    @Override
    public WebSocketSession getSession()
    {
        return session;
    }

    @Override
    public void incomingFrame(Frame frame)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("incomingFrame({})", frame);
        }

        try
        {
            onFrame(frame);

            byte opcode = frame.getOpCode();
            switch (opcode)
            {
                case OpCode.CLOSE:
                {
                    boolean validate = true;
                    CloseFrame closeframe = (CloseFrame)frame;
                    CloseInfo close = new CloseInfo(closeframe, validate);

                    // process handshake
                    session.getConnection().remoteClose(close);

                    return;
                }
                case OpCode.PING:
                {
                    if (logger.isDebugEnabled())
                    {
                        logger.debug("PING: {}", BufferUtil.toDetailString(frame.getPayload()));
                    }
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
                    onPing(frame.getPayload());
                    session.getRemote().sendPong(pongBuf);
                    break;
                }
                case OpCode.PONG:
                {
                    if (logger.isDebugEnabled())
                    {
                        logger.debug("PONG: {}", BufferUtil.toDetailString(frame.getPayload()));
                    }
                    onPong(frame.getPayload());
                    break;
                }
                case OpCode.BINARY:
                {
                    onBinaryFrame(frame.getPayload(), frame.isFin());
                    return;
                }
                case OpCode.TEXT:
                {
                    onTextFrame(frame.getPayload(), frame.isFin());
                    return;
                }
                case OpCode.CONTINUATION:
                {
                    onContinuationFrame(frame.getPayload(), frame.isFin());
                    return;
                }
                default:
                {
                    if (logger.isDebugEnabled())
                        logger.debug("Unhandled OpCode: {}", opcode);
                }
            }
        }
        catch (NotUtf8Exception e)
        {
            session.close(new BadPayloadException(e));
        }
        catch (Throwable t)
        {
            session.close(t);
        }
    }

    @Override
    public void onContinuationFrame(ByteBuffer buffer, boolean fin) throws IOException
    {
        if (activeMessage == null)
        {
            throw new IOException("Out of order Continuation frame encountered");
        }

        appendMessage(buffer, fin);
    }

    @Override
    public void onPong(ByteBuffer buffer)
    {
    }

    @Override
    public void onPing(ByteBuffer buffer)
    {
    }

    @Override
    public BatchMode getBatchMode()
    {
        return null;
    }

    @Override
    public void openSession(WebSocketSession session)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("openSession({}) objectFactory={}", session, session.getContainerScope().getObjectFactory());
        }
        this.session = session;
        this.session.getContainerScope().getObjectFactory().decorate(this.websocket);

        try
        {
            // Call application onOpen
            this.onConnect();
        }
        catch (Throwable t)
        {
            this.session.close(t);
        }
    }
}
