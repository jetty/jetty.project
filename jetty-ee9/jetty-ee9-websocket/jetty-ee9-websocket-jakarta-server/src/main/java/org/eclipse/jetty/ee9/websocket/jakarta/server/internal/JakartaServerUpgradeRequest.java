//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.jakarta.server.internal;

import java.net.URI;
import java.security.Principal;

import org.eclipse.jetty.ee9.websocket.jakarta.common.UpgradeRequest;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;

public class JakartaServerUpgradeRequest implements UpgradeRequest
{
    private final ServerUpgradeRequest servletRequest;

    public JakartaServerUpgradeRequest(ServerUpgradeRequest servletRequest)
    {
        this.servletRequest = servletRequest;
    }

    @Override
    public Principal getUserPrincipal()
    {
        // TODO: keep the wrapped request and ask it for user principal.
        return null;
    }

    @Override
    public URI getRequestURI()
    {
        // TODO: keep the wrapped request and ask it for request URI.
        return null;
    }

    @Override
    public String getPathInContext()
    {
        return Request.getPathInContext(servletRequest);
    }
}
