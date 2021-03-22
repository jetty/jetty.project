//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.Test;

public class ServerDatagramConnectorTest
{
    @Test
    public void testSmall() throws Exception
    {
        Server server = new Server();

        HttpConfiguration config = new HttpConfiguration();
        config.setHttpCompliance(HttpCompliance.LEGACY); // enable HTTP/0.9
        HttpConnectionFactory connectionFactory = new HttpConnectionFactory(config);

        ServerDatagramConnector serverDatagramConnector = new ServerDatagramConnector(server, connectionFactory);
        serverDatagramConnector.setPort(8443);
        server.addConnector(serverDatagramConnector);

        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                PrintWriter writer = response.getWriter();
                writer.println("<html>\n" +
                    "\t<body>\n" +
                    "\t\tRequest served\n" +
                    "\t</body>\n" +
                    "</html>");
            }
        });

        server.start();

        System.out.println("Started.");
        System.in.read();

        server.stop();
    }

    @Test
    public void testBig() throws Exception
    {
        Server server = new Server();

        HttpConfiguration config = new HttpConfiguration();
        config.setHttpCompliance(HttpCompliance.LEGACY); // enable HTTP/0.9
        HttpConnectionFactory connectionFactory = new HttpConnectionFactory(config);

        ServerDatagramConnector serverDatagramConnector = new ServerDatagramConnector(server, connectionFactory);
        serverDatagramConnector.setPort(8443);
        server.addConnector(serverDatagramConnector);

        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                ServletOutputStream outputStream = response.getOutputStream();

                File file = new File("./src/test/resources/zfs-intro.pdf");
                response.setContentLengthLong(file.length());
                byte[] buffer = new byte[1024];
                try (FileInputStream fis = new FileInputStream(file))
                {
                    while (true)
                    {
                        int read = fis.read(buffer);
                        if (read == -1)
                            break;
                        outputStream.write(buffer, 0, read);
                    }
                }
            }
        });

        server.start();

        System.out.println("Started.");
        System.in.read();

        server.stop();
    }
}
