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
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.protocol.ExtensionConfig;

public class ServletWebSocketResponse extends HttpServletResponseWrapper implements UpgradeResponse
{
    private String acceptedProtocol;
    private List<ExtensionConfig> extensions = new ArrayList<>();
    private boolean success = true;

    public ServletWebSocketResponse(HttpServletResponse resp)
    {
        super(resp);
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
    public Set<String> getHeaderNamesSet()
    {
        Collection<String> names = getHeaderNames();
        return new HashSet<String>(names);
    }

    @Override
    public String getHeaderValue(String name)
    {
        return super.getHeader(name);
    }

    @Override
    public Iterator<String> getHeaderValues(String name)
    {
        return super.getHeaders(name).iterator();
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

    @Override
    public void validateWebSocketHash(String expectedHash) throws UpgradeException
    {
        throw new UnsupportedOperationException("Server cannot validate its own hash");
    }
}
