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

package org.eclipse.jetty.ee10.websocket.jakarta.server.internal;

import java.net.URI;
import java.security.Principal;

import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jetty.ee10.websocket.jakarta.common.UpgradeRequest;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;

import static org.eclipse.jetty.util.URIUtil.addEncodedPaths;
import static org.eclipse.jetty.util.URIUtil.encodePath;

public class JakartaServerUpgradeRequest implements UpgradeRequest
{
    private final HttpServletRequest _servletRequest;
    private final Principal _userPrincipal;

    public JakartaServerUpgradeRequest(ServerUpgradeRequest upgradeRequest)
    {
        _servletRequest = (HttpServletRequest)upgradeRequest.getAttribute(WebSocketConstants.WEBSOCKET_WRAPPED_REQUEST_ATTRIBUTE);
        _userPrincipal = _servletRequest.getUserPrincipal();
    }

    @Override
    public Principal getUserPrincipal()
    {
        return _userPrincipal;
    }

    @Override
    public URI getRequestURI()
    {
        return HttpURI.build(_servletRequest.getRequestURI())
            .path(addEncodedPaths(_servletRequest.getContextPath(), getPathInContext()))
            .query(_servletRequest.getQueryString())
            .asImmutable().toURI();
    }

    @Override
    public String getPathInContext()
    {
        return encodePath(URIUtil.addPaths(_servletRequest.getServletPath(), _servletRequest.getPathInfo()));
    }
}
