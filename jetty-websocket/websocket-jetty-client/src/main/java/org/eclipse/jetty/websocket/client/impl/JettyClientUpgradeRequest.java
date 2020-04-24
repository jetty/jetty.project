//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.client.impl;

import java.net.HttpCookie;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandler;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandlerFactory;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;

public class JettyClientUpgradeRequest extends ClientUpgradeRequest
{
    private final DelegatedJettyClientUpgradeRequest handshakeRequest;
    private final JettyWebSocketFrameHandler frameHandler;

    public JettyClientUpgradeRequest(WebSocketCoreClient coreClient, UpgradeRequest request, URI requestURI, JettyWebSocketFrameHandlerFactory frameHandlerFactory,
                                     Object websocketPojo)
    {
        super(coreClient, requestURI);

        if (request != null)
        {
            // Copy request details into actual request
            HttpFields.Mutable fields = getHeaders();
            request.getHeaders().forEach(fields::put);

            // Copy manually created Cookies into place
            List<HttpCookie> cookies = request.getCookies();
            if (cookies != null)
            {
                // TODO: remove existing Cookie header (if set)?
                for (HttpCookie cookie : cookies)
                {
                    fields.add(HttpHeader.COOKIE, cookie.toString());
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
        frameHandler = frameHandlerFactory.newJettyFrameHandler(websocketPojo);
    }

    @Override
    protected void customize(EndPoint endPoint)
    {
        super.customize(endPoint);
        handshakeRequest.configure(endPoint);
    }

    @Override
    public void upgrade(HttpResponse response, EndPoint endPoint)
    {
        frameHandler.setUpgradeRequest(new DelegatedJettyClientUpgradeRequest(this));
        frameHandler.setUpgradeResponse(new DelegatedJettyClientUpgradeResponse(response));
        super.upgrade(response, endPoint);
    }

    @Override
    public FrameHandler getFrameHandler()
    {
        return frameHandler;
    }
}
