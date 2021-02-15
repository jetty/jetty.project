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

package org.eclipse.jetty.http2.server;

import java.io.IOException;
import java.util.Date;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class HTTP2CServer extends Server
{
    public HTTP2CServer(int port)
    {
        HttpConfiguration config = new HttpConfiguration();
        // HTTP + HTTP/2 connector

        HttpConnectionFactory http1 = new HttpConnectionFactory(config);
        HTTP2CServerConnectionFactory http2c = new HTTP2CServerConnectionFactory(config);
        ServerConnector connector = new ServerConnector(this, http1, http2c);
        connector.setPort(port);
        addConnector(connector);

        ((QueuedThreadPool)getThreadPool()).setName("server");

        setHandler(new SimpleHandler());
    }

    public static void main(String... args) throws Exception
    {
        HTTP2CServer server = new HTTP2CServer(8080);
        server.start();
    }

    private static class SimpleHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            String code = request.getParameter("code");
            if (code != null)
                response.setStatus(Integer.parseInt(code));

            response.setHeader("Custom", "Value");
            response.setContentType("text/plain");
            String content = "Hello from Jetty using " + request.getProtocol() + "\n";
            content += "uri=" + request.getRequestURI() + "\n";
            content += "date=" + new Date() + "\n";
            response.setContentLength(content.length());
            response.getOutputStream().print(content);
        }
    }
}
