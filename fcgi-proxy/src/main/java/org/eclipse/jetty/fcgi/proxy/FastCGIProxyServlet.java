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

package org.eclipse.jetty.fcgi.proxy;

import javax.servlet.ServletConfig;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.client.http.HttpClientTransportOverFCGI;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.proxy.ProxyServlet;

public class FastCGIProxyServlet extends ProxyServlet.Transparent
{
    public static final String SCRIPT_ROOT_INIT_PARAM = "scriptRoot";
    private static final String REMOTE_ADDR_ATTRIBUTE = FastCGIProxyServlet.class.getName() + ".remoteAddr";
    private static final String REMOTE_PORT_ATTRIBUTE = FastCGIProxyServlet.class.getName() + ".remotePort";
    private static final String SERVER_ADDR_ATTRIBUTE = FastCGIProxyServlet.class.getName() + ".serverAddr";
    private static final String SERVER_PORT_ATTRIBUTE = FastCGIProxyServlet.class.getName() + ".serverPort";

    @Override
    protected HttpClient newHttpClient()
    {
        ServletConfig config = getServletConfig();
        String scriptRoot = config.getInitParameter(SCRIPT_ROOT_INIT_PARAM);
        if (scriptRoot == null)
            throw new IllegalArgumentException("Mandatory parameter '" + SCRIPT_ROOT_INIT_PARAM + "' not configured");
        return new HttpClient(new ProxyHttpClientTransportOverFCGI(scriptRoot), null);
    }

    @Override
    protected void customizeProxyRequest(Request proxyRequest, HttpServletRequest request)
    {
        proxyRequest.attribute(REMOTE_ADDR_ATTRIBUTE, request.getRemoteAddr());
        proxyRequest.attribute(REMOTE_PORT_ATTRIBUTE, String.valueOf(request.getRemotePort()));
        proxyRequest.attribute(SERVER_ADDR_ATTRIBUTE, request.getLocalAddr());
        proxyRequest.attribute(SERVER_PORT_ATTRIBUTE, String.valueOf(request.getLocalPort()));
        super.customizeProxyRequest(proxyRequest, request);
    }

    private static class ProxyHttpClientTransportOverFCGI extends HttpClientTransportOverFCGI
    {
        public ProxyHttpClientTransportOverFCGI(String scriptRoot)
        {
            super(scriptRoot);
        }

        @Override
        protected void customize(Request request, HttpFields fastCGIHeaders)
        {
            super.customize(request, fastCGIHeaders);
            fastCGIHeaders.put(FCGI.Headers.REMOTE_ADDR, (String)request.getAttributes().get(REMOTE_ADDR_ATTRIBUTE));
            fastCGIHeaders.put(FCGI.Headers.REMOTE_PORT, (String)request.getAttributes().get(REMOTE_PORT_ATTRIBUTE));
            fastCGIHeaders.put(FCGI.Headers.SERVER_ADDR, (String)request.getAttributes().get(SERVER_ADDR_ATTRIBUTE));
            fastCGIHeaders.put(FCGI.Headers.SERVER_PORT, (String)request.getAttributes().get(SERVER_PORT_ATTRIBUTE));
        }
    }
}
