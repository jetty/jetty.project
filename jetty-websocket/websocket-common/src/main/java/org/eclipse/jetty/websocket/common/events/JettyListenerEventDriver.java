//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketConnectionListener;
import org.eclipse.jetty.websocket.api.WebSocketFrameListener;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.WebSocketPartialListener;
import org.eclipse.jetty.websocket.api.WebSocketPingPongListener;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.Frame.Type;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.frames.ReadOnlyDelegatedFrame;
import org.eclipse.jetty.websocket.common.message.SimpleBinaryMessage;
import org.eclipse.jetty.websocket.common.message.SimpleTextMessage;
import org.eclipse.jetty.websocket.common.util.Utf8PartialBuilder;

/**
 * Handler for {@link WebSocketListener} based User WebSocket implementations.
 */
public class JettyListenerEventDriver extends AbstractEventDriver
{
    private static final Logger LOG = Log.getLogger(JettyListenerEventDriver.class);
    private final WebSocketConnectionListener listener;
    private Utf8PartialBuilder utf8Partial;
    private boolean hasCloseBeenCalled = false;

    public JettyListenerEventDriver(WebSocketPolicy policy, WebSocketConnectionListener listener)
    {
        super(policy,listener);
        this.listener = listener;
    }

    @Override
    public void onBinaryFrame(ByteBuffer buffer, boolean fin) throws IOException
    {
        if (listener instanceof WebSocketListener)
        {
            if (activeMessage == null)
            {
                activeMessage = new SimpleBinaryMessage(this);
            }

            appendMessage(buffer,fin);
        }

        if (listener instanceof WebSocketPartialListener)
        {
            ((WebSocketPartialListener)listener).onWebSocketPartialBinary(buffer.slice().asReadOnlyBuffer(),fin);
        }
    }

    @Override
    public void onBinaryMessage(byte[] data)
    {
        if (listener instanceof WebSocketListener)
        {
            ((WebSocketListener)listener).onWebSocketBinary(data,0,data.length);
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

        int statusCode = close.getStatusCode();
        String reason = close.getReason();
        listener.onWebSocketClose(statusCode,reason);
    }

    @Override
    public void onConnect()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onConnect({})", session);
        listener.onWebSocketConnect(session);
    }

    @Override
    public void onError(Throwable cause)
    {
        listener.onWebSocketError(cause);
    }

    @Override
    public void onFrame(Frame frame)
    {
        if (listener instanceof WebSocketFrameListener)
        {
            ((WebSocketFrameListener)listener).onWebSocketFrame(new ReadOnlyDelegatedFrame(frame));
        }

        if (listener instanceof WebSocketPingPongListener)
        {
            if (frame.getType() == Type.PING)
            {
                ((WebSocketPingPongListener)listener).onWebSocketPing(frame.getPayload().asReadOnlyBuffer());
            }
            else if (frame.getType() == Type.PONG)
            {
                ((WebSocketPingPongListener)listener).onWebSocketPong(frame.getPayload().asReadOnlyBuffer());
            }
        }
    }

    @Override
    public void onInputStream(InputStream stream)
    {
        /* not supported in Listener mode (yet) */
    }

    @Override
    public void onReader(Reader reader)
    {
        /* not supported in Listener mode (yet) */
    }

    @Override
    public void onTextFrame(ByteBuffer buffer, boolean fin) throws IOException
    {
        if (listener instanceof WebSocketListener)
        {
            if (activeMessage == null)
            {
                activeMessage = new SimpleTextMessage(this);
            }

            appendMessage(buffer,fin);
        }

        if (listener instanceof WebSocketPartialListener)
        {
            if (utf8Partial == null)
            {
                utf8Partial = new Utf8PartialBuilder();
            }
            
            String partial = utf8Partial.toPartialString(buffer);
            
            ((WebSocketPartialListener)listener).onWebSocketPartialText(partial,fin);
            
            if (fin)
            {
                partial = null;
            }
        }
    }

    /**
     * Whole Message event.
     * 
     * @param message the whole message
     */
    @Override
    public void onTextMessage(String message)
    {
        if (listener instanceof WebSocketListener)
        {
            ((WebSocketListener)listener).onWebSocketText(message);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]",JettyListenerEventDriver.class.getSimpleName(),listener.getClass().getName());
    }
}
