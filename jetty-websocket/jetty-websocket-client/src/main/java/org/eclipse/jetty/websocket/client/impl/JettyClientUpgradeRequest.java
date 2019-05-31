//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client.impl;

import java.net.HttpCookie;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.http.HttpConnectionOverHTTP;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandler;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;

public class JettyClientUpgradeRequest extends ClientUpgradeRequest
{
    private final WebSocketClient containerContext;
    private final Object websocketPojo;
    private final DelegatedJettyClientUpgradeRequest handshakeRequest;
    private final CompletableFuture<Session> completableFutureSession;
    private final CompletableFuture<Session> futureSession;
    private UpgradeResponse upgradeResponse;

    public JettyClientUpgradeRequest(WebSocketClient clientContainer, WebSocketCoreClient coreClient, UpgradeRequest request,
                                     URI requestURI, Object websocketPojo)
    {
        super(coreClient, requestURI);
        this.containerContext = clientContainer;
        this.websocketPojo = websocketPojo;
        this.completableFutureSession = new CompletableFuture<>();
        this.futureSession = completableFutureSession.thenApply(s->
        {
            // Note: we must set the upgrade response before the future session will complete
            ((WebSocketSession)s).setUpgradeResponse(upgradeResponse);
            return s;
        });

        if (request != null)
        {
            // Copy request details into actual request
            HttpFields fields = getHeaders();
            request.getHeaders().forEach((name, values) -> fields.put(name, values));

            // Copy manually created Cookies into place
            List<HttpCookie> cookies = request.getCookies();
            if (cookies != null)
            {
                HttpFields headers = getHeaders();
                // TODO: remove existing Cookie header (if set)?
                for (HttpCookie cookie : cookies)
                {
                    headers.add(HttpHeader.COOKIE, cookie.toString());
                }
            }

            // Copy sub-protocols
            setSubProtocols(request.getSubProtocols());

            // Copy extensions
            setExtensions(request.getExtensions().stream()
                    .map(c -> new ExtensionConfig(c.getName(), c.getParameters()))
                    .collect(Collectors.toList()));

            // Copy method from upgradeRequest object
            if (request.getMethod() != null)
                method(request.getMethod());

            // Copy version from upgradeRequest object
            if (request.getHttpVersion() != null)
                version(HttpVersion.fromString(request.getHttpVersion()));
        }

        handshakeRequest = new DelegatedJettyClientUpgradeRequest(this);
    }

    @Override
    protected void customize(EndPoint endp)
    {
        super.customize(endp);
        handshakeRequest.configure(endp);
    }

    protected void handleException(Throwable failure)
    {
        super.handleException(failure);
        completableFutureSession.completeExceptionally(failure);
    }

    @Override
    public void upgrade(HttpResponse response, HttpConnectionOverHTTP httpConnection)
    {
        // Set the upgrade response which is used in the callback on the futureSession
        upgradeResponse = new DelegatedJettyClientUpgradeResponse(response);
        super.upgrade(response, httpConnection);
    }

    @Override
    public FrameHandler getFrameHandler(WebSocketCoreClient coreClient)
    {
        JettyWebSocketFrameHandler frameHandler = containerContext.newFrameHandler(websocketPojo, handshakeRequest, completableFutureSession);
        return frameHandler;
    }

    public CompletableFuture<Session> getFutureSession()
    {
        return futureSession;
    }
}
