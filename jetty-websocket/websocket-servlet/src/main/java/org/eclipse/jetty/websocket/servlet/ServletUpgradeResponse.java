//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.servlet;

import java.io.IOException;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;

/**
 * Servlet Specific UpgradeResponse implementation.
 */
public class ServletUpgradeResponse extends UpgradeResponse
{
    private HttpServletResponse resp;
    private boolean extensionsNegotiated = false;
    private boolean subprotocolNegotiated = false;

    public ServletUpgradeResponse(HttpServletResponse resp)
    {
        super();
        this.resp = resp;
    }

    @Override
    public void addHeader(String name, String value)
    {
        this.resp.addHeader(name,value);
    }

    @Override
    public int getStatusCode()
    {
        return this.resp.getStatus();
    }

    @Override
    public String getStatusReason()
    {
        throw new UnsupportedOperationException("Server cannot get Status Reason Message");
    }

    public boolean isCommitted()
    {
        return this.resp.isCommitted();
    }

    public boolean isExtensionsNegotiated()
    {
        return extensionsNegotiated;
    }

    public boolean isSubprotocolNegotiated()
    {
        return subprotocolNegotiated;
    }

    public void sendError(int statusCode, String message) throws IOException
    {
        setSuccess(false);
        this.resp.sendError(statusCode,message);
    }

    @Override
    public void sendForbidden(String message) throws IOException
    {
        setSuccess(false);
        resp.sendError(HttpServletResponse.SC_FORBIDDEN,message);
    }

    @Override
    public void setAcceptedSubProtocol(String protocol)
    {
        super.setAcceptedSubProtocol(protocol);
        subprotocolNegotiated = true;
    }

    @Override
    public void setExtensions(List<ExtensionConfig> extensions)
    {
        super.setExtensions(extensions);
        extensionsNegotiated = true;
    }

    @Override
    public void setHeader(String name, String value)
    {
        this.resp.setHeader(name,value);
    }

    public void setStatus(int status)
    {
        this.resp.setStatus(status);
    }

}
