//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.unixsocket;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.ProxyConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.unixsocket.server.UnixSocketConnector;

public class UnixSocketProxyServer
{
    public static void main(String... args) throws Exception
    {
        Server server = new Server();

        HttpConnectionFactory http = new HttpConnectionFactory();
        ProxyConnectionFactory proxy = new ProxyConnectionFactory(http.getProtocol());
        UnixSocketConnector connector = new UnixSocketConnector(server, proxy, http);
        server.addConnector(connector);

        Path socket = Paths.get(connector.getUnixSocket());
        if (Files.exists(socket))
            Files.delete(socket);

        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                int l = 0;
                if (request.getContentLength() != 0)
                {
                    InputStream in = request.getInputStream();
                    byte[] buffer = new byte[4096];
                    int r = 0;
                    while (r >= 0)
                    {
                        l += r;
                        r = in.read(buffer);
                    }
                }
                baseRequest.setHandled(true);
                response.setStatus(200);
                response.getWriter().write("Hello World " + new Date() + "\r\n");
                response.getWriter().write("remote=" + request.getRemoteAddr() + ":" + request.getRemotePort() + "\r\n");
                response.getWriter().write("local =" + request.getLocalAddr() + ":" + request.getLocalPort() + "\r\n");
                response.getWriter().write("read =" + l + "\r\n");
            }
        });

        server.start();

        while (true)
        {
            Thread.sleep(5000);
            connector.dumpStdErr();
        }

        // server.join();
    }
}
