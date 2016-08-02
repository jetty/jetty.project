//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;

/**
 * Servlet Specific UpgradeResponse implementation.
 */
public class ServletUpgradeResponse extends UpgradeResponse
{
    private HttpServletResponse response;
    private boolean extensionsNegotiated = false;
    private boolean subprotocolNegotiated = false;

    public ServletUpgradeResponse(HttpServletResponse response)
    {
        this.response = response;
    }

    @Override
    public int getStatusCode()
    {
        return response.getStatus();
    }

    public void setStatus(int status)
    {
        response.setStatus(status);
    }

    @Override
    public String getStatusReason()
    {
        throw new UnsupportedOperationException("Server cannot get Status Reason Message");
    }

    public boolean isCommitted()
    {
        if (response != null)
        {
            return response.isCommitted();
        }
        // True in all other cases
        return true;
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
        applyHeaders();
        response.sendError(statusCode, message);
        response.flushBuffer(); // commit response
        response = null;
    }

    @Override
    public void sendForbidden(String message) throws IOException
    {
        setSuccess(false);
        applyHeaders();
        response.sendError(HttpServletResponse.SC_FORBIDDEN, message);
        response.flushBuffer(); // commit response
        response = null;
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

    public void complete()
    {
        applyHeaders();
        response = null;
    }

    private void applyHeaders()
    {
        // Transfer all headers to the real HTTP response
        for (Map.Entry<String, List<String>> entry : getHeaders().entrySet())
        {
            for (String value : entry.getValue())
            {
                response.addHeader(entry.getKey(), value);
            }
        }
    }
}
