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

import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.websocket.api.UpgradeResponse;

public class ServletWebSocketResponse extends UpgradeResponse
{
    private HttpServletResponse resp;

    public ServletWebSocketResponse(HttpServletResponse resp)
    {
        super();
        this.resp = resp;
        // TODO: copy values from resp
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

    public void setStatus(int status)
    {
        this.resp.setStatus(status);
    }
}
