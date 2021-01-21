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

package org.eclipse.jetty.websocket.jsr356.endpoints;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.util.Map;
import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.MessageHandler;
import javax.websocket.MessageHandler.Whole;
import javax.websocket.PongMessage;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.message.MessageInputStream;
import org.eclipse.jetty.websocket.common.message.MessageReader;
import org.eclipse.jetty.websocket.jsr356.JsrPongMessage;
import org.eclipse.jetty.websocket.jsr356.JsrSession;
import org.eclipse.jetty.websocket.jsr356.MessageHandlerWrapper;
import org.eclipse.jetty.websocket.jsr356.MessageType;
import org.eclipse.jetty.websocket.jsr356.messages.BinaryPartialMessage;
import org.eclipse.jetty.websocket.jsr356.messages.BinaryWholeMessage;
import org.eclipse.jetty.websocket.jsr356.messages.TextPartialMessage;
import org.eclipse.jetty.websocket.jsr356.messages.TextWholeMessage;

/**
 * EventDriver for websocket that extend from {@link javax.websocket.Endpoint}
 */
public class JsrEndpointEventDriver extends AbstractJsrEventDriver
{
    private static final Logger LOG = Log.getLogger(JsrEndpointEventDriver.class);

    private final Endpoint endpoint;
    private Map<String, String> pathParameters;

    public JsrEndpointEventDriver(WebSocketPolicy policy, EndpointInstance endpointInstance)
    {
        super(policy, endpointInstance);
        this.endpoint = (Endpoint)endpointInstance.getEndpoint();
    }

    @Override
    public void init(JsrSession jsrsession)
    {
        jsrsession.setPathParameters(pathParameters);
    }

    @Override
    public void onBinaryFrame(ByteBuffer buffer, boolean fin) throws IOException
    {
        if (activeMessage == null)
        {
            final MessageHandlerWrapper wrapper = jsrsession.getMessageHandlerWrapper(MessageType.BINARY);
            if (wrapper == null)
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("No BINARY MessageHandler declared");
                }
                return;
            }
            if (wrapper.wantsPartialMessages())
            {
                activeMessage = new BinaryPartialMessage(wrapper);
            }
            else if (wrapper.wantsStreams())
            {
                @SuppressWarnings("unchecked")
                MessageHandler.Whole<InputStream> handler = (Whole<InputStream>)wrapper.getHandler();
                MessageInputStream inputStream = new MessageInputStream(session);
                activeMessage = inputStream;
                dispatch(() ->
                {
                    try
                    {
                        handler.onMessage(inputStream);
                    }
                    catch (Throwable t)
                    {
                        session.close(t);
                        return;
                    }

                    inputStream.handlerComplete();
                });
            }
            else
            {
                activeMessage = new BinaryWholeMessage(this, wrapper);
            }
        }

        activeMessage.appendFrame(buffer, fin);

        if (fin)
        {
            activeMessage.messageComplete();
            activeMessage = null;
        }
    }

    @Override
    public void onBinaryMessage(byte[] data)
    {
        /* Ignored, handled by BinaryWholeMessage */
    }

    @Override
    protected void onClose(CloseReason closereason)
    {
        endpoint.onClose(this.jsrsession, closereason);
    }

    @Override
    public void onConnect()
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onConnect({}, {})", jsrsession, config);
        }

        // Let unhandled exceptions flow out
        endpoint.onOpen(jsrsession, config);
    }

    @Override
    public void onError(Throwable cause)
    {
        try
        {
            endpoint.onError(jsrsession, cause);
        }
        catch (Throwable t)
        {
            LOG.warn("Unable to report to onError due to exception", t);
        }
    }

    @Override
    public void onFrame(Frame frame)
    {
        /* Ignored, not supported by JSR-356 */
    }

    @Override
    public void onInputStream(InputStream stream)
    {
        /* Ignored, handled by BinaryStreamMessage */
    }

    @Override
    public void onReader(Reader reader)
    {
        /* Ignored, handled by TextStreamMessage */
    }

    @Override
    public void onTextFrame(ByteBuffer buffer, boolean fin) throws IOException
    {
        if (activeMessage == null)
        {
            final MessageHandlerWrapper wrapper = jsrsession.getMessageHandlerWrapper(MessageType.TEXT);
            if (wrapper == null)
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("No TEXT MessageHandler declared");
                }
                return;
            }
            if (wrapper.wantsPartialMessages())
            {
                activeMessage = new TextPartialMessage(wrapper);
            }
            else if (wrapper.wantsStreams())
            {
                @SuppressWarnings("unchecked")
                MessageHandler.Whole<Reader> handler = (Whole<Reader>)wrapper.getHandler();
                MessageReader reader = new MessageReader(session);
                activeMessage = reader;
                dispatch(() ->
                {
                    try
                    {
                        handler.onMessage(reader);
                    }
                    catch (Throwable t)
                    {
                        session.close(t);
                        return;
                    }

                    reader.handlerComplete();
                });
            }
            else
            {
                activeMessage = new TextWholeMessage(this, wrapper);
            }
        }

        activeMessage.appendFrame(buffer, fin);

        if (fin)
        {
            activeMessage.messageComplete();
            activeMessage = null;
        }
    }

    @Override
    public void onTextMessage(String message)
    {
        /* Ignored, handled by TextWholeMessage */
    }

    @Override
    public void onPing(ByteBuffer buffer)
    {
        onPongMessage(buffer);
    }

    @Override
    public void onPong(ByteBuffer buffer)
    {
        onPongMessage(buffer);
    }

    private void onPongMessage(ByteBuffer buffer)
    {
        final MessageHandlerWrapper wrapper = jsrsession.getMessageHandlerWrapper(MessageType.PONG);
        if (wrapper == null)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("No PONG MessageHandler declared");
            }
            return;
        }

        ByteBuffer pongBuf = null;

        if (BufferUtil.isEmpty(buffer))
        {
            pongBuf = BufferUtil.EMPTY_BUFFER;
        }
        else
        {
            pongBuf = ByteBuffer.allocate(buffer.remaining());
            BufferUtil.put(buffer, pongBuf);
            BufferUtil.flipToFlush(pongBuf, 0);
        }

        @SuppressWarnings("unchecked")
        Whole<PongMessage> pongHandler = (Whole<PongMessage>)wrapper.getHandler();
        pongHandler.onMessage(new JsrPongMessage(pongBuf));
    }

    @Override
    public void setPathParameters(Map<String, String> pathParameters)
    {
        this.pathParameters = pathParameters;
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]", JsrEndpointEventDriver.class.getSimpleName(), endpoint.getClass().getName());
    }
}
