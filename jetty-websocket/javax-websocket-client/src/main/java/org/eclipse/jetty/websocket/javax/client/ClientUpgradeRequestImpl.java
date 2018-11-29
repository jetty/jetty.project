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

package org.eclipse.jetty.websocket.javax.client;

import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketFrameHandler;
import org.eclipse.jetty.websocket.javax.common.UpgradeRequest;
import org.eclipse.jetty.websocket.javax.common.UpgradeResponse;

import javax.websocket.Session;
import java.net.URI;
import java.util.concurrent.CompletableFuture;

public class ClientUpgradeRequestImpl extends org.eclipse.jetty.websocket.core.client.UpgradeRequest
{
    private final JavaxWebSocketClientContainer containerContext;
    private final Object websocketPojo;
    private final CompletableFuture<Session> futureJavaxSession;

    public ClientUpgradeRequestImpl(JavaxWebSocketClientContainer clientContainer, WebSocketCoreClient coreClient, URI requestURI, Object websocketPojo)
    {
        super(coreClient, requestURI);
        this.containerContext = clientContainer;
        this.websocketPojo = websocketPojo;
        this.futureJavaxSession = new CompletableFuture<>();
    }

    @Override
    protected void handleException(Throwable failure)
    {
        super.handleException(failure);
        futureJavaxSession.completeExceptionally(failure);
    }

    @Override
    public FrameHandler getFrameHandler(WebSocketCoreClient coreClient, HttpResponse response)
    {
        UpgradeRequest upgradeRequest = new DelegatedClientUpgradeRequest(this);
        UpgradeResponse upgradeResponse = new DelegatedClientUpgradeResponse(response);

        JavaxWebSocketFrameHandler frameHandler = containerContext.newFrameHandler(websocketPojo,
            upgradeRequest, upgradeResponse, futureJavaxSession);

        return frameHandler;
    }

    public CompletableFuture<Session> getFutureSession()
    {
        return futureJavaxSession;
    }
}
