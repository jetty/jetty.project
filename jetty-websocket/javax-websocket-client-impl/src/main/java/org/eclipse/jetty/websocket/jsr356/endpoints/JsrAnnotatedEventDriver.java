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
import java.util.Map;

import javax.websocket.CloseReason;
import javax.websocket.DecodeException;
import javax.websocket.MessageHandler.Whole;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.message.MessageInputStream;
import org.eclipse.jetty.websocket.common.message.MessageReader;
import org.eclipse.jetty.websocket.common.message.SimpleBinaryMessage;
import org.eclipse.jetty.websocket.common.message.SimpleTextMessage;
import org.eclipse.jetty.websocket.jsr356.JsrSession;
import org.eclipse.jetty.websocket.jsr356.annotations.JsrEvents;

/**
 * Base implementation for JSR-356 Annotated event drivers.
 */
public class JsrAnnotatedEventDriver extends AbstractJsrEventDriver implements EventDriver
{
    private static final Logger LOG = Log.getLogger(JsrAnnotatedEventDriver.class);
    private final JsrEvents<?, ?> events;

    public JsrAnnotatedEventDriver(WebSocketPolicy policy, EndpointInstance endpointInstance, JsrEvents<?, ?> events)
    {
        super(policy,endpointInstance);
        this.events = events;
    }

    @Override
    protected void init(JsrSession jsrsession)
    {
        this.events.init(jsrsession);
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
                    events.callBinary(jsrsession.getAsyncRemote(),websocket,buffer,fin);
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
                final MessageInputStream stream = new MessageInputStream(session.getConnection());
                activeMessage = stream;

                // Always dispatch streaming read to another thread.
                dispatch(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            events.callBinaryStream(jsrsession.getAsyncRemote(),websocket,stream);
                        }
                        catch (DecodeException | IOException e)
                        {
                            onFatalError(e);
                        }
                    }
                });
            }
        }

        LOG.debug("handled = {}",handled);

        // Process any active MessageAppender
        if (handled && (activeMessage != null))
        {
            appendMessage(buffer,fin);
        }
    }

    /**
     * Entry point for binary frames destined for {@link Whole}
     */
    @Override
    public void onBinaryMessage(byte[] data)
    {
        if (data == null)
        {
            return;
        }

        ByteBuffer buf = ByteBuffer.wrap(data);

        if (LOG.isDebugEnabled())
        {
            LOG.debug("onBinaryMessage({})",BufferUtil.toDetailString(buf));
        }

        try
        {
            // FIN is always true here
            events.callBinary(jsrsession.getAsyncRemote(),websocket,buf,true);
        }
        catch (DecodeException e)
        {
            onFatalError(e);
        }
    }

    @Override
    protected void onClose(CloseReason closereason)
    {
        events.callClose(websocket,closereason);
    }

    @Override
    public void onConnect()
    {
        events.callOpen(websocket,config);
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
            events.callBinaryStream(jsrsession.getAsyncRemote(),websocket,stream);
        }
        catch (DecodeException | IOException e)
        {
            onFatalError(e);
        }
    }

    @Override
    public void onPong(ByteBuffer buffer)
    {
        try
        {
            events.callPong(jsrsession.getAsyncRemote(),websocket,buffer);
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
            events.callTextStream(jsrsession.getAsyncRemote(),websocket,reader);
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
                    events.callText(jsrsession.getAsyncRemote(),websocket,text,fin);
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

                final MessageReader stream = new MessageReader(new MessageInputStream(session.getConnection()));
                activeMessage = stream;

                // Always dispatch streaming read to another thread.
                dispatch(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            events.callTextStream(jsrsession.getAsyncRemote(),websocket,stream);
                        }
                        catch (DecodeException | IOException e)
                        {
                            onFatalError(e);
                        }
                    }
                });
            }
        }

        LOG.debug("handled = {}",handled);

        // Process any active MessageAppender
        if (handled && (activeMessage != null))
        {
            appendMessage(buffer,fin);
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
            events.callText(jsrsession.getAsyncRemote(),websocket,message,true);
        }
        catch (DecodeException e)
        {
            onFatalError(e);
        }
    }

    public void setRequestParameters(Map<String, String> requestParameters)
    {
        events.setRequestParameters(requestParameters);
    }

    @Override
    public String toString()
    {
        return String.format("%s[websocket=%s]",this.getClass().getSimpleName(),websocket);
    }
}
