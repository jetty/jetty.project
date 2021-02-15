//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.tests;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.websocket.common.AcceptHash;

public class InvalidUpgradeServlet extends HttpServlet
{
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
    {
        String pathInfo = req.getPathInfo();
        if (pathInfo.contains("only-accept"))
        {
            // Force 200 response, no response body content, incomplete websocket response headers, no actual upgrade for this test
            resp.setStatus(HttpServletResponse.SC_OK);
            String key = req.getHeader(HttpHeader.SEC_WEBSOCKET_KEY.toString());
            resp.setHeader(HttpHeader.SEC_WEBSOCKET_ACCEPT.toString(), AcceptHash.hashKey(key));
        }
        else if (pathInfo.contains("close-connection"))
        {
            // Force 101 response, with invalid Connection header, invalid handshake
            resp.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
            String key = req.getHeader(HttpHeader.SEC_WEBSOCKET_KEY.toString());
            resp.setHeader(HttpHeader.CONNECTION.toString(), "close");
            resp.setHeader(HttpHeader.SEC_WEBSOCKET_ACCEPT.toString(), AcceptHash.hashKey(key));
        }
        else if (pathInfo.contains("missing-connection"))
        {
            // Force 101 response, with no Connection header, invalid handshake
            resp.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
            String key = req.getHeader(HttpHeader.SEC_WEBSOCKET_KEY.toString());
            // Intentionally leave out Connection header
            resp.setHeader(HttpHeader.SEC_WEBSOCKET_ACCEPT.toString(), AcceptHash.hashKey(key));
        }
        else if (pathInfo.contains("rubbish-accept"))
        {
            // Force 101 response, with no Connection header, invalid handshake
            resp.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
            resp.setHeader(HttpHeader.SEC_WEBSOCKET_ACCEPT.toString(), "rubbish");
        }
        else
        {
            resp.setStatus(500);
        }
    }
}
