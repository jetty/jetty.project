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

package org.eclipse.jetty.websocket.common.events;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.message.MessageAppender;
import org.eclipse.jetty.websocket.common.message.MessageInputStream;
import org.eclipse.jetty.websocket.common.message.MessageReader;
import org.eclipse.jetty.websocket.common.message.SimpleBinaryMessage;
import org.eclipse.jetty.websocket.common.message.SimpleTextMessage;

/**
 * Handler for Annotated User WebSocket objects.
 */
public class AnnotatedEventDriver extends EventDriver
{
    private final EventMethods events;
    private MessageAppender activeMessage;

    public AnnotatedEventDriver(WebSocketPolicy policy, Object websocket, EventMethods events)
    {
        super(policy,websocket);
        this.events = events;

        WebSocket anno = websocket.getClass().getAnnotation(WebSocket.class);
        // Setup the policy
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

    @Override
    public void onBinaryFrame(ByteBuffer buffer, boolean fin) throws IOException
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
                activeMessage = new MessageInputStream(this);
            }
            else
            {
                activeMessage = new SimpleBinaryMessage(this);
            }
        }

        activeMessage.appendMessage(buffer);

        if (fin)
        {
            activeMessage.messageComplete();
            activeMessage = null;
        }
    }

    @Override
    public void onBinaryMessage(byte[] data)
    {
        if (events.onBinary != null)
        {
            events.onBinary.call(websocket,session,data,0,data.length);
        }
    }

    @Override
    public void onClose(CloseInfo close)
    {
        if (events.onClose != null)
        {
            events.onClose.call(websocket,session,close.getStatusCode(),close.getReason());
        }
    }

    @Override
    public void onConnect()
    {
        if (events.onConnect != null)
        {
            events.onConnect.call(websocket,session);
        }
    }

    @Override
    public void onException(WebSocketException e)
    {
        if (events.onException != null)
        {
            events.onException.call(websocket,session,e);
        }
    }

    @Override
    public void onFrame(Frame frame)
    {
        if (events.onFrame != null)
        {
            events.onFrame.call(websocket,session,frame);
        }
    }

    public void onInputStream(InputStream stream)
    {
        if (events.onBinary != null)
        {
            events.onBinary.call(websocket,session,stream);
        }
    }

    public void onReader(Reader reader)
    {
        if (events.onText != null)
        {
            events.onText.call(websocket,session,reader);
        }
    }

    @Override
    public void onTextFrame(ByteBuffer buffer, boolean fin) throws IOException
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
                activeMessage = new MessageReader(this);
            }
            else
            {
                activeMessage = new SimpleTextMessage(this);
            }
        }

        activeMessage.appendMessage(buffer);

        if (fin)
        {
            activeMessage.messageComplete();
            activeMessage = null;
        }
    }

    @Override
    public void onTextMessage(String message)
    {
        if (events.onText != null)
        {
            events.onText.call(websocket,session,message);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]", this.getClass().getSimpleName(), websocket);
    }
}
