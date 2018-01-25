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

package org.eclipse.jetty.websocket.jsr356.client;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

import javax.websocket.Session;

import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.websocket.common.HandshakeRequest;
import org.eclipse.jetty.websocket.common.HandshakeResponse;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketFrameHandler;

public class ClientUpgradeRequestImpl extends WebSocketCoreClientUpgradeRequest
{
    private final JavaxWebSocketClientContainer containerContext;
    private final Object websocketPojo;
    private final CompletableFuture<Session> onOpenFuture;
    private final CompletableFuture<Session> futureSession;

    public ClientUpgradeRequestImpl(JavaxWebSocketClientContainer clientContainer, WebSocketCoreClient coreClient, URI requestURI, Object websocketPojo)
    {
        super(coreClient, requestURI);
        this.containerContext = clientContainer;
        this.websocketPojo = websocketPojo;
        this.onOpenFuture = new CompletableFuture<>();
        this.futureSession = super.fut.thenCombine(onOpenFuture, (channel, session) -> session);
    }

    protected void handleException(Throwable failure)
    {
        super.handleException(failure);
        onOpenFuture.completeExceptionally(failure);
    }

    @Override
    public FrameHandler getFrameHandler(WebSocketCoreClient coreClient, HttpResponse response)
    {
        HandshakeRequest handshakeRequest = new DelegatedClientHandshakeRequest(this);
        HandshakeResponse handshakeResponse = new DelegatedClientHandshakeResponse(response);

        JavaxWebSocketFrameHandler frameHandler = containerContext.newFrameHandler(websocketPojo, containerContext.getPolicy(),
                handshakeRequest, handshakeResponse, onOpenFuture);

        return frameHandler;
    }

    public CompletableFuture<Session> getFutureSession()
    {
        return futureSession;
    }
}
