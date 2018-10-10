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

package org.eclipse.jetty.websocket.jsr356.server.internal;

import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.websocket.jsr356.UpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;

public class UpgradeRequestAdapter implements UpgradeRequest
{
    private final ServletUpgradeRequest servletRequest;

    public UpgradeRequestAdapter(ServletUpgradeRequest servletRequest)
    {
        this.servletRequest = servletRequest;
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return this.servletRequest.getLocalSocketAddress();
    }

    @Override
    public Map<String, List<String>> getParameterMap()
    {
        return this.servletRequest.getParameterMap();
    }

    @Override
    public String getProtocolVersion()
    {
        return this.servletRequest.getProtocolVersion();
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return this.servletRequest.getRemoteSocketAddress();
    }

    @Override
    public URI getRequestURI()
    {
        return this.servletRequest.getRequestURI();
    }

    @Override
    public boolean isSecure()
    {
        return this.servletRequest.isSecure();
    }
}
