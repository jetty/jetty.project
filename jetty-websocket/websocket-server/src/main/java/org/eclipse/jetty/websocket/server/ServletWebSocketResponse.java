// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.eclipse.jetty.websocket.protocol.ExtensionConfig;

public class ServletWebSocketResponse extends HttpServletResponseWrapper implements WebSocketResponse
{
    private String acceptedProtocol;
    private List<ExtensionConfig> extensions = new ArrayList<>();

    public ServletWebSocketResponse(HttpServletResponse resp)
    {
        super(resp);
    }

    @Override
    public String getAcceptedSubProtocol()
    {
        return acceptedProtocol;
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return this.extensions;
    }

    @Override
    public void sendForbidden(String message) throws IOException
    {
        sendError(HttpServletResponse.SC_FORBIDDEN,message);
    }

    @Override
    public void setAcceptedSubProtocol(String protocol)
    {
        this.acceptedProtocol = protocol;
    }

    @Override
    public void setExtensions(List<ExtensionConfig> extensions)
    {
        this.extensions = extensions;
    }
}
