//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.listeners.WebSocketFrameListener;
import org.eclipse.jetty.websocket.api.listeners.WebSocketListener;
import org.eclipse.jetty.websocket.core.CloseStatus;

public class UntrustedWSEndpoint extends TrackingEndpoint implements WebSocketListener, WebSocketFrameListener
{
    private static final Logger LOG = Log.getLogger(UntrustedWSEndpoint.class);

    private UntrustedWSSession untrustedSession;
    private CompletableFuture<UntrustedWSSession> onOpenFuture;

    private BiFunction<UntrustedWSSession, String, String> onTextFunction;
    private BiFunction<UntrustedWSSession, ByteBuffer, ByteBuffer> onBinaryFunction;
    private BiConsumer<UntrustedWSSession, CloseStatus> onCloseConsumer;

    public CompletableFuture<UntrustedWSSession> getOnOpenFuture()
    {
        return onOpenFuture;
    }

    public UntrustedWSEndpoint(String id)
    {
        super(id);
    }

    @Override
    public void onWebSocketConnect(Session session)
    {
        assertThat("Session type", session, instanceOf(UntrustedWSSession.class));
        this.untrustedSession = (UntrustedWSSession) session;
        if (this.onOpenFuture != null)
        {
            this.onOpenFuture.complete(this.untrustedSession);
        }

        super.onWebSocketConnect(session);
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        super.onWebSocketClose(statusCode, reason);
        if (this.onCloseConsumer != null)
        {
            CloseStatus closeStatus = new CloseStatus(statusCode, reason);
            this.onCloseConsumer.accept(this.untrustedSession, closeStatus);
        }
    }

    @Override
    public void onWebSocketError(Throwable cause)
    {
        if (this.onOpenFuture != null)
        {
            // Always trip this, doesn't matter if if completed normally first.
            this.onOpenFuture.completeExceptionally(cause);
        }

        super.onWebSocketError(cause);
    }

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len)
    {
        super.onWebSocketBinary(payload, offset, len);

        if (onBinaryFunction != null)
        {
            try
            {
                ByteBuffer msg = ByteBuffer.wrap(payload, offset, len);
                ByteBuffer responseBuffer = onBinaryFunction.apply(this.untrustedSession, msg);
                if (responseBuffer != null)
                {
                    this.getRemote().sendBinary(responseBuffer);
                }
            }
            catch (Throwable t)
            {
                LOG.warn("Unable to send binary", t);
            }
        }
    }

    @Override
    public void onWebSocketText(String text)
    {
        super.onWebSocketText(text);

        if (onTextFunction != null)
        {
            try
            {
                String responseText = onTextFunction.apply(this.untrustedSession, text);
                if (responseText != null)
                {
                    this.getRemote().sendText(responseText);
                }
            }
            catch (Throwable t)
            {
                LOG.warn("Unable to send text", t);
            }
        }
    }

    public void setOnOpenFuture(CompletableFuture<UntrustedWSSession> future)
    {
        this.onOpenFuture = future;
    }

    public void setOnCloseConsumer(BiConsumer<UntrustedWSSession, CloseStatus> onCloseConsumer)
    {
        this.onCloseConsumer = onCloseConsumer;
    }

    public void setOnBinaryFunction(BiFunction<UntrustedWSSession, ByteBuffer, ByteBuffer> onBinaryFunction)
    {
        this.onBinaryFunction = onBinaryFunction;
    }

    public void setOnTextFunction(BiFunction<UntrustedWSSession, String, String> onTextFunction)
    {
        this.onTextFunction = onTextFunction;
    }
}
