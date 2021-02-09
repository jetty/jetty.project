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
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.util.Objects;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
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
import org.eclipse.jetty.websocket.common.util.TextUtil;

/**
 * Handler for {@link WebSocketListener} based User WebSocket implementations.
 */
public class JettyListenerEventDriver extends AbstractEventDriver
{
    private enum PartialMode
    {
        NONE, TEXT, BINARY
    }

    private static final Logger LOG = Log.getLogger(JettyListenerEventDriver.class);
    private final WebSocketConnectionListener listener;
    private Utf8StringBuilder utf8Partial;
    private PartialMode partialMode = PartialMode.NONE;
    private boolean hasCloseBeenCalled = false;

    public JettyListenerEventDriver(WebSocketPolicy policy, WebSocketConnectionListener listener)
    {
        super(policy, listener);
        this.listener = Objects.requireNonNull(listener, "Listener may not be null");
        if (LOG.isDebugEnabled())
        {
            LOG.debug("ctor / listener={}, policy={}", listener.getClass().getName(), policy);
        }
    }

    @Override
    public void onBinaryFrame(ByteBuffer buffer, boolean fin) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onBinaryFrame({}, {}) - webSocketListener={}, webSocketPartialListener={}, listener={}, activeMessage={}",
                BufferUtil.toDetailString(buffer), fin,
                (listener instanceof WebSocketListener),
                (listener instanceof WebSocketPartialListener),
                listener.getClass().getName(),
                activeMessage);
        }

        if (listener instanceof WebSocketListener)
        {
            if (activeMessage == null)
            {
                activeMessage = new SimpleBinaryMessage(this);
            }

            appendMessage(buffer, fin);
        }

        if (listener instanceof WebSocketPartialListener)
        {
            switch (partialMode)
            {
                case NONE:
                    partialMode = PartialMode.BINARY;
                    // fallthru
                case BINARY:
                    ((WebSocketPartialListener)listener).onWebSocketPartialBinary(buffer.slice().asReadOnlyBuffer(), fin);
                    break;
                case TEXT:
                    throw new IOException("Out of order binary frame encountered");
            }

            if (fin)
            {
                partialMode = PartialMode.NONE;
            }
        }
    }

    @Override
    public void onBinaryMessage(byte[] data)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onBinaryMessage([{}]) - webSocketListener={}, listener={}",
                data.length,
                (listener instanceof WebSocketListener),
                this.listener.getClass().getName());
        }

        if (listener instanceof WebSocketListener)
        {
            ((WebSocketListener)listener).onWebSocketBinary(data, 0, data.length);
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

        if (LOG.isDebugEnabled())
        {
            LOG.debug("onClose({},{}) - listener={}", statusCode, reason, this.listener.getClass().getName());
        }
        listener.onWebSocketClose(statusCode, reason);
    }

    @Override
    public void onConnect()
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onConnect({}) - listener={}", session, this.listener.getClass().getName());
        }
        listener.onWebSocketConnect(session);
    }

    @Override
    public void onError(Throwable cause)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onError({}) - listener={}", cause.getClass().getName(), this.listener.getClass().getName());
        }
        listener.onWebSocketError(cause);
    }

    @Override
    public void onFrame(Frame frame)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onFrame({}) - frameListener={}, pingPongListener={}, listener={}",
                frame,
                (listener instanceof WebSocketFrameListener),
                (listener instanceof WebSocketPingPongListener),
                this.listener.getClass().getName());
        }

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
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onTextFrame({}, {}) - webSocketListener={}, webSocketPartialListener={}, listener={}, activeMessage={}",
                BufferUtil.toDetailString(buffer),
                fin,
                (listener instanceof WebSocketListener),
                (listener instanceof WebSocketPartialListener),
                listener.getClass().getName(),
                activeMessage);
        }

        if (listener instanceof WebSocketListener)
        {
            if (activeMessage == null)
            {
                activeMessage = new SimpleTextMessage(this);
            }

            appendMessage(buffer, fin);
        }

        if (listener instanceof WebSocketPartialListener)
        {
            switch (partialMode)
            {
                case NONE:
                    partialMode = PartialMode.TEXT;
                    // fallthru
                case TEXT:
                    if (utf8Partial == null)
                    {
                        utf8Partial = new Utf8StringBuilder();
                    }

                    String partial = "";

                    if (buffer != null)
                    {
                        utf8Partial.append(buffer);
                        partial = utf8Partial.takePartialString();
                    }

                    ((WebSocketPartialListener)listener).onWebSocketPartialText(partial, fin);

                    if (fin)
                    {
                        utf8Partial = null;
                    }
                    break;
                case BINARY:
                    throw new IOException("Out of order text frame encountered");
            }

            if (fin)
            {
                partialMode = PartialMode.NONE;
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
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onTextMessage([{}] \"{}\") - webSocketListener={}, listener={}",
                message.length(),
                TextUtil.maxStringLength(60, message),
                (listener instanceof WebSocketListener),
                listener.getClass().getName());
        }

        if (listener instanceof WebSocketListener)
        {
            ((WebSocketListener)listener).onWebSocketText(message);
        }
    }

    public void onContinuationFrame(ByteBuffer buffer, boolean fin) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onContinuationFrame({}, {}) - webSocketListener={}, webSocketPartialListener={}, listener={}, activeMessage={}",
                BufferUtil.toDetailString(buffer), fin,
                (listener instanceof WebSocketListener),
                (listener instanceof WebSocketPartialListener),
                listener.getClass().getName(),
                activeMessage);
        }

        if (listener instanceof WebSocketPartialListener)
        {
            switch (partialMode)
            {
                case NONE:
                    throw new IOException("Out of order Continuation frame encountered");
                case TEXT:
                    onTextFrame(buffer, fin);
                    break;
                case BINARY:
                    onBinaryFrame(buffer, fin);
                    break;
            }
            return;
        }

        if (listener instanceof WebSocketListener)
        {
            super.onContinuationFrame(buffer, fin);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]", JettyListenerEventDriver.class.getSimpleName(), listener.getClass().getName());
    }
}
