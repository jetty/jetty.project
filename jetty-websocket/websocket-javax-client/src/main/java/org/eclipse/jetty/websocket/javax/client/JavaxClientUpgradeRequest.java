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

package org.eclipse.jetty.websocket.javax.client;

import java.net.URI;

import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketFrameHandler;
import org.eclipse.jetty.websocket.javax.common.UpgradeRequest;

public class JavaxClientUpgradeRequest extends ClientUpgradeRequest
{
    private final JavaxWebSocketClientContainer containerContext;
    private final JavaxWebSocketFrameHandler frameHandler;

    public JavaxClientUpgradeRequest(JavaxWebSocketClientContainer clientContainer, WebSocketCoreClient coreClient, URI requestURI, Object websocketPojo)
    {
        super(coreClient, requestURI);
        this.containerContext = clientContainer;

        UpgradeRequest upgradeRequest = new DelegatedJavaxClientUpgradeRequest(this);
        frameHandler = containerContext.newFrameHandler(websocketPojo, upgradeRequest);
    }

    @Override
    public void upgrade(HttpResponse response, EndPoint endPoint)
    {
        frameHandler.setUpgradeRequest(new DelegatedJavaxClientUpgradeRequest(this));
        frameHandler.setUpgradeResponse(new DelegatedJavaxClientUpgradeResponse(response));
        super.upgrade(response, endPoint);
    }

    @Override
    public FrameHandler getFrameHandler()
    {
        return frameHandler;
    }
}
