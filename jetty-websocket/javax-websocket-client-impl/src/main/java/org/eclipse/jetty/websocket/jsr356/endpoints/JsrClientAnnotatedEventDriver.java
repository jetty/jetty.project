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
import javax.websocket.MessageHandler;
import javax.websocket.Session;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
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
import org.eclipse.jetty.websocket.jsr356.annotations.JsrEvents;

public class JsrClientAnnotatedEventDriver extends AbstractEventDriver implements EventDriver, IJsrSession
{
    private static final Logger LOG = Log.getLogger(JsrClientAnnotatedEventDriver.class);
    private final JettyWebSocketContainer container;
    private final JsrEvents events;
    private boolean hasCloseBeenCalled = false;
    private JsrSession jsrsession;
    private ClientEndpointConfig endpointconfig;
    private MessageAppender activeMessage;

    public JsrClientAnnotatedEventDriver(JettyWebSocketContainer container, WebSocketPolicy policy, Object websocket, JsrEvents events)
    {
        super(policy,websocket);
        this.container = container;
        this.events = events;
    }

    @Override
    public Session getJsrSession()
    {
        return this.jsrsession;
    }

    /**
     * Entry point for all incoming binary frames.
     */
    @Override
    public void onBinaryFrame(ByteBuffer buffer, boolean fin) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onBinaryFrame({}, {})",BufferUtil.toDetailString(buffer),fin);
            LOG.debug("events.onBinary={}",events.hasBinary());
            LOG.debug("events.onBinaryStream={}",events.hasBinaryStream());
        }
        boolean handled = false;

        if (events.hasBinary())
        {
            handled = true;
            if (events.isBinaryPartialSupported())
            {
                LOG.debug("Partial Binary Message: fin={}",fin);
                // Partial Message Support (does not use messageAppender)
                try
                {
                    events.callBinary(websocket,buffer,fin);
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
                    LOG.debug("Whole Binary Message");
                    activeMessage = new SimpleBinaryMessage(this);
                }
            }
        }

        if (events.hasBinaryStream())
        {
            handled = true;
            // Streaming Message Support
            if (activeMessage == null)
            {
                LOG.debug("Binary Message InputStream");
                activeMessage = new MessageInputStream(this);
            }
        }

        LOG.debug("handled = {}",handled);

        // Process any active MessageAppender
        if (handled && (activeMessage != null))
        {
            LOG.debug("Appending Binary Message");
            activeMessage.appendMessage(buffer,fin);

            if (fin)
            {
                LOG.debug("Binary Message Complete");
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
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onBinary({})",data);
        }

        try
        {
            // FIN is always true here
            events.callBinary(websocket,ByteBuffer.wrap(data),true);
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
        events.callClose(websocket,close);
    }

    @Override
    public void onConnect()
    {
        events.callOpen(websocket,endpointconfig);
    }

    @Override
    public void onError(Throwable cause)
    {
        events.callError(websocket,cause);
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
        try
        {
            events.callBinaryStream(websocket,stream);
        }
        catch (DecodeException | IOException e)
        {
            onFatalError(e);
        }
    }

    @Override
    public void onReader(Reader reader)
    {
        try
        {
            events.callTextStream(websocket,reader);
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
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onTextFrame({}, {})",BufferUtil.toDetailString(buffer),fin);
            LOG.debug("events.hasText={}",events.hasText());
            LOG.debug("events.hasTextStream={}",events.hasTextStream());
        }

        boolean handled = false;

        if (events.hasText())
        {
            handled = true;
            if (events.isTextPartialSupported())
            {
                LOG.debug("Partial Text Message: fin={}",fin);
                // Partial Message Support (does not use messageAppender)
                try
                {
                    String text = BufferUtil.toUTF8String(buffer);
                    events.callText(websocket,text,fin);
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
                    LOG.debug("Whole Text Message");
                    activeMessage = new SimpleTextMessage(this);
                }
            }
        }

        if (events.hasTextStream())
        {
            handled = true;
            // Streaming Message Support
            if (activeMessage == null)
            {
                LOG.debug("Text Message Writer");
                activeMessage = new MessageReader(this);
            }
        }

        LOG.debug("handled = {}",handled);

        // Process any active MessageAppender
        if (handled && (activeMessage != null))
        {
            LOG.debug("Appending Text Message");
            activeMessage.appendMessage(buffer,fin);

            if (fin)
            {
                LOG.debug("Text Message Complete");
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
        LOG.debug("onText({})",message);

        try
        {
            // FIN is always true here
            events.callText(websocket,message,true);
        }
        catch (DecodeException e)
        {
            onFatalError(e);
        }
    }

    @Override
    public void openSession(WebSocketSession session)
    {
        String id = container.getNextId();
        this.jsrsession = new JsrSession(container,session,id);
        // Initialize the events
        this.events.init(jsrsession);
        // TODO: Initialize the decoders
        super.openSession(session);
    }

    @Override
    public String toString()
    {
        return String.format("%s[websocket=%s]",this.getClass().getSimpleName(),websocket);
    }
}
