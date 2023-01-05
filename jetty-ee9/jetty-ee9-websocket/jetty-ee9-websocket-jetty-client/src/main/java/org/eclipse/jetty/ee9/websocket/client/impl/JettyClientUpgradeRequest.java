//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.client.impl;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.ee9.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.ee9.websocket.common.JettyWebSocketFrameHandler;
import org.eclipse.jetty.ee9.websocket.common.JettyWebSocketFrameHandlerFactory;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;

public class JettyClientUpgradeRequest extends CoreClientUpgradeRequest
{
    private final JettyWebSocketFrameHandler frameHandler;

    public JettyClientUpgradeRequest(WebSocketCoreClient coreClient, ClientUpgradeRequest request, URI requestURI, JettyWebSocketFrameHandlerFactory frameHandlerFactory,
                                     Object websocketPojo)
    {
        super(coreClient, requestURI);

        if (request != null)
        {
            // Copy request details into actual request
            headers(fields -> request.getHeaders().forEach(fields::put));

            // Copy manually created Cookies into place
            headers(fields -> request.getCookies().forEach(cookie -> fields.add(HttpHeader.COOKIE, cookie.toString())));

            setSubProtocols(request.getSubProtocols());
            setExtensions(request.getExtensions().stream()
                .map(c -> new ExtensionConfig(c.getName(), c.getParameters()))
                .collect(Collectors.toList()));

            timeout(request.getTimeout(), TimeUnit.MILLISECONDS);
        }

        frameHandler = frameHandlerFactory.newJettyFrameHandler(websocketPojo);
    }

    @Override
    public void upgrade(Response response, EndPoint endPoint)
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
