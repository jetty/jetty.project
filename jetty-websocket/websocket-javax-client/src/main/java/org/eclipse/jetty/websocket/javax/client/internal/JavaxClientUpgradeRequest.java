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

package org.eclipse.jetty.websocket.javax.client.internal;

import java.net.URI;
import java.security.Principal;

import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketFrameHandler;
import org.eclipse.jetty.websocket.javax.common.UpgradeRequest;

public class JavaxClientUpgradeRequest extends CoreClientUpgradeRequest implements UpgradeRequest
{
    private final JavaxWebSocketFrameHandler frameHandler;

    public JavaxClientUpgradeRequest(JavaxWebSocketClientContainer clientContainer, WebSocketCoreClient coreClient, URI requestURI, Object websocketPojo)
    {
        super(coreClient, requestURI);
        frameHandler = clientContainer.newFrameHandler(websocketPojo, this);
    }

    @Override
    public FrameHandler getFrameHandler()
    {
        return frameHandler;
    }

    @Override
    public Principal getUserPrincipal()
    {
        // User Principal not available from Client API
        return null;
    }

    @Override
    public URI getRequestURI()
    {
        return getURI();
    }

    @Override
    public String getPathInContext()
    {
        throw new UnsupportedOperationException();
    }
}
