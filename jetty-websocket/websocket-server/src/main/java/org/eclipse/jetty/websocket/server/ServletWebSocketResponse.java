//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;

public class ServletWebSocketResponse extends UpgradeResponse
{
    private String acceptedProtocol;
    private List<ExtensionConfig> extensions = new ArrayList<>();
    private boolean success = true;
    private HttpServletResponse resp;

    public ServletWebSocketResponse(HttpServletResponse resp)
    {
        super();
        this.resp = resp;
    }

    @Override
    public void addHeader(String name, String value)
    {
        super.addHeader(name,value);
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
    public int getStatusCode()
    {
        throw new UnsupportedOperationException("Server cannot get Status Code");
    }

    @Override
    public String getStatusReason()
    {
        throw new UnsupportedOperationException("Server cannot get Status Reason");
    }

    @Override
    public boolean isSuccess()
    {
        return success;
    }

    @Override
    public void sendForbidden(String message) throws IOException
    {
        success = false;
        resp.sendError(HttpServletResponse.SC_FORBIDDEN,message);
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

    @Override
    public void validateWebSocketHash(String expectedHash) throws UpgradeException
    {
        throw new UnsupportedOperationException("Server cannot validate its own hash");
    }
}
