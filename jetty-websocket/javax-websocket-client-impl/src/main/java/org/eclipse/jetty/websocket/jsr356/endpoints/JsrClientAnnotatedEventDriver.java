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

import javax.websocket.ClientEndpointConfig;
import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.Decoder.Text;
import javax.websocket.MessageHandler;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.events.AbstractEventDriver;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.message.MessageAppender;
import org.eclipse.jetty.websocket.common.message.MessageInputStream;
import org.eclipse.jetty.websocket.common.message.MessageReader;
import org.eclipse.jetty.websocket.common.message.SimpleBinaryMessage;
import org.eclipse.jetty.websocket.common.message.SimpleTextMessage;
import org.eclipse.jetty.websocket.jsr356.JettyWebSocketContainer;
import org.eclipse.jetty.websocket.jsr356.JsrSession;

public class JsrClientAnnotatedEventDriver extends AbstractEventDriver implements EventDriver
{
    private final JettyWebSocketContainer container;
    private final JsrClientMetadata events;
    private boolean hasCloseBeenCalled = false;
    private JsrSession jsrsession;
    private ClientEndpointConfig endpointconfig;
    private MessageAppender activeMessage;

    public JsrClientAnnotatedEventDriver(JettyWebSocketContainer container, WebSocketPolicy policy, Object websocket, JsrClientMetadata metadata)
    {
        super(policy,websocket);
        this.container = container;
        this.events = metadata;
    }

    /**
     * Entry point for all incoming binary frames.
     */
    @Override
    public void onBinaryFrame(ByteBuffer buffer, boolean fin) throws IOException
    {
        boolean handled = false;

        if (events.onBinary != null)
        {
            handled = true;
            if (events.onBinary.isPartialMessageSupported())
            {
                // Partial Message Support (does not use messageAppender)
                try
                {
                    events.onBinary.call(websocket,buffer,fin);
                }
                catch (DecodeException e)
                {
                    onFatalError(e);
                }
                return;
            }
            else
            {
                // Whole Message Support
                if (activeMessage == null)
                {
                    activeMessage = new SimpleBinaryMessage(this);
                }
            }
        }

        if (events.onBinaryStream != null)
        {
            handled = true;
            // Streaming Message Support
            if (activeMessage == null)
            {
                activeMessage = new MessageInputStream(this);
            }
        }

        // Process any active MessageAppender
        if (handled && (activeMessage != null))
        {
            activeMessage.appendMessage(buffer);

            if (fin)
            {
                activeMessage.messageComplete();
                activeMessage = null;
            }
        }
    }

    /**
     * Entry point for binary frames destined for {@link MessageHandler#Whole}
     */
    @Override
    public void onBinaryMessage(byte[] data)
    {
        if (events.onBinary == null)
        {
            // not interested in text events
            return;
        }

        try
        {
            events.onBinary.call(websocket,ByteBuffer.wrap(data),false);
        }
        catch (DecodeException e)
        {
            onFatalError(e);
        }
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
        if (events.onClose != null)
        {
            events.onClose.call(websocket,close);
        }
    }

    @Override
    public void onConnect()
    {
        if (events.onOpen != null)
        {
            events.onOpen.call(websocket,endpointconfig);
        }
    }

    @Override
    public void onError(Throwable cause)
    {
        if (events.onError != null)
        {
            events.onError.call(websocket,cause);
        }
    }

    private void onFatalError(Throwable t)
    {
        onError(t);
        // TODO: close connection?
    }

    @Override
    public void onFrame(Frame frame)
    {
        /* Ignored in JSR-356 */
    }

    @Override
    public void onInputStream(InputStream stream)
    {
        if (events.onBinaryStream == null)
        {
            // not interested in text events
            return;
        }

        try
        {
            events.onBinaryStream.call(websocket,stream);
        }
        catch (DecodeException | IOException e)
        {
            onFatalError(e);
        }
    }

    @Override
    public void onReader(Reader reader)
    {
        if (events.onTextStream == null)
        {
            // not interested in text events
            return;
        }

        try
        {
            events.onTextStream.call(websocket,reader);
        }
        catch (DecodeException | IOException e)
        {
            onFatalError(e);
        }
    }

    /**
     * Entry point for all incoming text frames.
     */
    @Override
    public void onTextFrame(ByteBuffer buffer, boolean fin) throws IOException
    {
        boolean handled = false;

        if (events.onText != null)
        {
            handled = true;
            if (events.onText.isPartialMessageSupported())
            {
                // Partial Message Support (does not use messageAppender)
                try
                {
                    String text = BufferUtil.toUTF8String(buffer);
                    events.onText.call(websocket,text,fin);
                }
                catch (DecodeException e)
                {
                    onFatalError(e);
                }
                return;
            }
            else
            {
                // Whole Message Support
                if (activeMessage == null)
                {
                    activeMessage = new SimpleTextMessage(this);
                }
            }
        }

        if (events.onTextStream != null)
        {
            handled = true;
            // Streaming Message Support
            if (activeMessage == null)
            {
                activeMessage = new MessageReader(this);
            }
        }

        // Process any active MessageAppender
        if (handled && (activeMessage != null))
        {
            activeMessage.appendMessage(buffer);

            if (fin)
            {
                activeMessage.messageComplete();
                activeMessage = null;
            }
        }
    }

    /**
     * Entry point for whole text messages
     */
    @Override
    public void onTextMessage(String message)
    {
        if (events.onText == null)
        {
            // not interested in text events
            return;
        }

        Decoder.Text<?> decoder = (Text<?>)events.onText.getDecoder();
        try
        {
            decoder.init(endpointconfig);
            events.onText.call(websocket,jsrsession,decoder.decode(message));
        }
        catch (DecodeException e)
        {
            onFatalError(e);
        }
        finally
        {
            decoder.destroy();
        }
    }

    @Override
    public void openSession(WebSocketSession session)
    {
        super.openSession(session);
        String id = container.getNextId();
        this.jsrsession = new JsrSession(container,session,id);
    }
}
