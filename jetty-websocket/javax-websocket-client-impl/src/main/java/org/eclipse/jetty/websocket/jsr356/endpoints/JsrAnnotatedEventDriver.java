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
import javax.websocket.DecodeException;
import javax.websocket.MessageHandler.Whole;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.message.MessageInputStream;
import org.eclipse.jetty.websocket.common.message.MessageReader;
import org.eclipse.jetty.websocket.common.message.SimpleBinaryMessage;
import org.eclipse.jetty.websocket.common.message.SimpleTextMessage;
import org.eclipse.jetty.websocket.jsr356.JsrSession;
import org.eclipse.jetty.websocket.jsr356.annotations.JsrEvents;
import org.eclipse.jetty.websocket.jsr356.messages.BinaryPartialOnMessage;
import org.eclipse.jetty.websocket.jsr356.messages.TextPartialOnMessage;
import org.eclipse.jetty.websocket.jsr356.metadata.EndpointMetadata;

/**
 * Base implementation for JSR-356 Annotated event drivers.
 */
public class JsrAnnotatedEventDriver extends AbstractJsrEventDriver
{
    private static final Logger LOG = Log.getLogger(JsrAnnotatedEventDriver.class);
    private final JsrEvents<?, ?> events;

    public JsrAnnotatedEventDriver(WebSocketPolicy policy, EndpointInstance endpointInstance, JsrEvents<?, ?> events)
    {
        super(policy, endpointInstance);
        this.events = events;

        EndpointMetadata metadata = endpointInstance.getMetadata();

        if (metadata.maxTextMessageSize() >= 1)
            policy.setMaxTextMessageSize((int)metadata.maxTextMessageSize());
        if (metadata.maxBinaryMessageSize() >= 1)
            policy.setMaxBinaryMessageSize((int)metadata.maxBinaryMessageSize());
    }

    @Override
    public void init(JsrSession jsrsession)
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
            LOG.debug("onBinaryFrame({}, {})", BufferUtil.toDetailString(buffer), fin);
            LOG.debug("events.onBinary={}", events.hasBinary());
            LOG.debug("events.onBinaryStream={}", events.hasBinaryStream());
        }
        boolean handled = false;

        if (events.hasBinary())
        {
            handled = true;
            if (activeMessage == null)
            {
                if (events.isBinaryPartialSupported())
                {
                    // Partial Message Support (does not use messageAppender)
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("Partial Binary Message: fin={}", fin);
                    }
                    activeMessage = new BinaryPartialOnMessage(this);
                }
                else
                {
                    // Whole Message Support
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("Whole Binary Message");
                    }
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
                if (LOG.isDebugEnabled())
                    LOG.debug("Binary Message InputStream");

                MessageInputStream stream = new MessageInputStream(session);
                activeMessage = stream;
                dispatch(() ->
                {
                    try
                    {
                        events.callBinaryStream(jsrsession.getAsyncRemote(), websocket, stream);
                    }
                    catch (Throwable e)
                    {
                        session.close(e);
                    }

                    stream.handlerComplete();
                });
            }
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("handled = {}", handled);
        }

        // Process any active MessageAppender
        if (handled && (activeMessage != null))
        {
            appendMessage(buffer, fin);
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
            LOG.debug("onBinaryMessage({})", BufferUtil.toDetailString(buf));
        }

        try
        {
            // FIN is always true here
            events.callBinary(jsrsession.getAsyncRemote(), websocket, buf, true);
        }
        catch (Throwable e)
        {
            onFatalError(e);
        }
    }

    @Override
    protected void onClose(CloseReason closereason)
    {
        events.callClose(websocket, closereason);
    }

    @Override
    public void onConnect()
    {
        events.callOpen(websocket, config);
    }

    @Override
    public void onError(Throwable cause)
    {
        try
        {
            events.callError(websocket, cause);
        }
        catch (Throwable e)
        {
            LOG.warn("Unable to call onError with cause", cause);
            LOG.warn("Call to onError resulted in exception", e);
        }
    }

    private void onFatalError(Throwable t)
    {
        onError(t);
    }

    @Override
    public void onFrame(Frame frame)
    {
        /* Ignored in JSR-356 */
    }

    @Override
    public void onInputStream(InputStream stream) throws IOException
    {
        try
        {
            events.callBinaryStream(jsrsession.getAsyncRemote(), websocket, stream);
        }
        catch (DecodeException e)
        {
            throw new RuntimeException("Unable decode input stream", e);
        }
    }

    public void onPartialBinaryMessage(ByteBuffer buffer, boolean fin)
    {
        try
        {
            events.callBinary(jsrsession.getAsyncRemote(), websocket, buffer, fin);
        }
        catch (DecodeException e)
        {
            throw new RuntimeException("Unable decode partial binary message", e);
        }
    }

    public void onPartialTextMessage(String message, boolean fin)
    {
        try
        {
            events.callText(jsrsession.getAsyncRemote(), websocket, message, fin);
        }
        catch (DecodeException e)
        {
            throw new RuntimeException("Unable decode partial text message", e);
        }
    }

    @Override
    public void onPing(ByteBuffer buffer)
    {
        // Call pong, as there is no "onPing" method in the JSR
        events.callPong(jsrsession.getAsyncRemote(), websocket, buffer);
    }

    @Override
    public void onPong(ByteBuffer buffer)
    {
        events.callPong(jsrsession.getAsyncRemote(), websocket, buffer);
    }

    @Override
    public void onReader(Reader reader) throws IOException
    {
        try
        {
            events.callTextStream(jsrsession.getAsyncRemote(), websocket, reader);
        }
        catch (DecodeException e)
        {
            throw new RuntimeException("Unable decode reader", e);
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
            LOG.debug("onTextFrame({}, {})", BufferUtil.toDetailString(buffer), fin);
            LOG.debug("events.hasText={}", events.hasText());
            LOG.debug("events.hasTextStream={}", events.hasTextStream());
        }

        boolean handled = false;

        if (events.hasText())
        {
            handled = true;
            if (activeMessage == null)
            {
                if (events.isTextPartialSupported())
                {
                    // Partial Message Support
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("Partial Text Message: fin={}", fin);
                    }
                    activeMessage = new TextPartialOnMessage(this);
                }
                else
                {
                    // Whole Message Support
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("Whole Text Message");
                    }
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
                if (LOG.isDebugEnabled())
                    LOG.debug("Text Message Writer");

                MessageReader reader = new MessageReader(session);
                activeMessage = reader;
                dispatch(() ->
                {
                    try
                    {
                        events.callTextStream(jsrsession.getAsyncRemote(), websocket, reader);
                    }
                    catch (Throwable e)
                    {
                        session.close(e);
                        return;
                    }

                    reader.handlerComplete();
                });
            }
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("handled = {}", handled);
        }

        // Process any active MessageAppender
        if (handled && (activeMessage != null))
        {
            appendMessage(buffer, fin);
        }
    }

    /**
     * Entry point for whole text messages
     */
    @Override
    public void onTextMessage(String message)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onText({})", message);
        }

        try
        {
            // FIN is always true here
            events.callText(jsrsession.getAsyncRemote(), websocket, message, true);
        }
        catch (Throwable e)
        {
            onFatalError(e);
        }
    }

    @Override
    public void setPathParameters(Map<String, String> pathParameters)
    {
        events.setPathParameters(pathParameters);
    }

    @Override
    public String toString()
    {
        return String.format("%s[websocket=%s]", this.getClass().getSimpleName(), websocket);
    }
}
