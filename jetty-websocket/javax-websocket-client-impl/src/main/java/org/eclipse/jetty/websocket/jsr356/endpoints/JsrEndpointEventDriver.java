//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCode;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.events.AbstractEventDriver;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.message.MessageAppender;
import org.eclipse.jetty.websocket.jsr356.JettyWebSocketContainer;
import org.eclipse.jetty.websocket.jsr356.JsrSession;
import org.eclipse.jetty.websocket.jsr356.MessageType;
import org.eclipse.jetty.websocket.jsr356.messages.BinaryPartialMessage;
import org.eclipse.jetty.websocket.jsr356.messages.BinaryStreamMessage;
import org.eclipse.jetty.websocket.jsr356.messages.BinaryWholeMessage;
import org.eclipse.jetty.websocket.jsr356.messages.MessageHandlerWrapper;
import org.eclipse.jetty.websocket.jsr356.messages.TextPartialMessage;
import org.eclipse.jetty.websocket.jsr356.messages.TextStreamMessage;
import org.eclipse.jetty.websocket.jsr356.messages.TextWholeMessage;

public class JsrEndpointEventDriver extends AbstractEventDriver implements EventDriver, IJsrSession
{
    private final JettyWebSocketContainer container;
    private final Endpoint endpoint;
    private JsrSession jsrsession;
    private EndpointConfig endpointconfig;
    private MessageAppender activeMessage;
    private boolean hasCloseBeenCalled = false;

    public JsrEndpointEventDriver(JettyWebSocketContainer container, WebSocketPolicy policy, Endpoint endpoint)
    {
        super(policy,endpoint);
        this.container = container;
        this.endpoint = endpoint;
    }

    @Override
    public Session getJsrSession()
    {
        return this.jsrsession;
    }

    @Override
    public void onBinaryFrame(ByteBuffer buffer, boolean fin) throws IOException
    {
        if (activeMessage == null)
        {
            MessageHandlerWrapper wrapper = jsrsession.getMessageHandlerWrapper(MessageType.BINARY);
            if (wrapper.wantsPartialMessages())
            {
                activeMessage = new BinaryPartialMessage(wrapper);
            }
            else if (wrapper.wantsStreams())
            {
                activeMessage = new BinaryStreamMessage(this,wrapper);
            }
            else
            {
                activeMessage = new BinaryWholeMessage(this,wrapper);
            }
        }

        activeMessage.appendMessage(buffer,fin);

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
    public void onClose(CloseInfo close)
    {
        if (hasCloseBeenCalled)
        {
            // avoid duplicate close events (possible when using harsh Session.disconnect())
            return;
        }
        hasCloseBeenCalled = true;

        CloseCode closecode = CloseCodes.getCloseCode(close.getStatusCode());
        CloseReason closereason = new CloseReason(closecode,close.getReason());
        endpoint.onClose(this.jsrsession,closereason);
    }

    @Override
    public void onConnect()
    {
        endpoint.onOpen(jsrsession,endpointconfig);
    }

    @Override
    public void onError(Throwable cause)
    {
        endpoint.onError(jsrsession,cause);
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
            MessageHandlerWrapper wrapper = jsrsession.getMessageHandlerWrapper(MessageType.TEXT);
            if (wrapper.wantsPartialMessages())
            {
                activeMessage = new TextPartialMessage(wrapper);
            }
            else if (wrapper.wantsStreams())
            {
                activeMessage = new TextStreamMessage(this,wrapper);
            }
            else
            {
                activeMessage = new TextWholeMessage(this,wrapper);
            }
        }

        activeMessage.appendMessage(buffer,fin);

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
    public void openSession(WebSocketSession session)
    {
        String id = container.getNextId();
        this.jsrsession = new JsrSession(container,session,id);
        // TODO: Initialize the Decoders
        // TODO: Initialize the MessageHandlers
        // TODO: Set the Configuration?
        super.openSession(session);
    }
}
