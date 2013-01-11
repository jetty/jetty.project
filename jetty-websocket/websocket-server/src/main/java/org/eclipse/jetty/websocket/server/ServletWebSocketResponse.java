//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.websocket.api.UpgradeResponse;

public class ServletWebSocketResponse extends UpgradeResponse
{
    private HttpServletResponse resp;

    public ServletWebSocketResponse(HttpServletResponse resp)
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
    public void setHeader(String name, String value)
    {
        this.resp.setHeader(name,value);
    }

    public void setStatus(int status)
    {
        this.resp.setStatus(status);
    }
}
