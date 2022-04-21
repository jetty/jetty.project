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

package org.eclipse.jetty.ee10.websocket.jakarta.server.internal;

import java.net.URI;
import java.security.Principal;

import org.eclipse.jetty.ee10.websocket.jakarta.common.UpgradeRequest;
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
        // TODO
        return null;
    }

    @Override
    public URI getRequestURI()
    {
        return servletRequest.getHttpURI().toURI();
    }

    @Override
    public String getPathInContext()
    {
        return servletRequest.getPathInContext();
    }
}
