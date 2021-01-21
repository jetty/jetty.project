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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HostHeaderCustomizerTest
{
    @Test
    public void testHostHeaderCustomizer() throws Exception
    {
        Server server = new Server();
        HttpConfiguration httpConfig = new HttpConfiguration();
        final String serverName = "test_server_name";
        final int serverPort = 13;
        final String redirectPath = "/redirect";
        httpConfig.addCustomizer(new HostHeaderCustomizer(serverName, serverPort));
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        server.addConnector(connector);
        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                assertEquals(serverName, request.getServerName());
                assertEquals(serverPort, request.getServerPort());
                response.sendRedirect(redirectPath);
            }
        });
        server.start();
        try
        {
            try (Socket socket = new Socket("localhost", connector.getLocalPort()))
            {
                try (OutputStream output = socket.getOutputStream())
                {
                    String request =
                        "GET / HTTP/1.0\r\n" +
                            "\r\n";
                    output.write(request.getBytes(StandardCharsets.UTF_8));
                    output.flush();

                    HttpTester.Input input = HttpTester.from(socket.getInputStream());
                    HttpTester.Response response = HttpTester.parseResponse(input);

                    String location = response.get("location");
                    assertNotNull(location);
                    String schemePrefix = "http://";
                    assertTrue(location.startsWith(schemePrefix));
                    assertTrue(location.endsWith(redirectPath));
                    String hostPort = location.substring(schemePrefix.length(), location.length() - redirectPath.length());
                    assertEquals(serverName + ":" + serverPort, hostPort);
                }
            }
        }
        finally
        {
            server.stop();
        }
    }
}
