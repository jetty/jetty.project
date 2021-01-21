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

package org.eclipse.jetty.websocket.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.server.helper.EchoSocket;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketProtocolTest
{
    private Server server;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        ServletHolder holder = new ServletHolder(new WebSocketServlet()
        {
            @Override
            public void configure(WebSocketServletFactory factory)
            {
                factory.getPolicy().setIdleTimeout(10000);
                factory.getPolicy().setMaxTextMessageSize(1024 * 1024 * 2);
                factory.setCreator((req, resp) ->
                {
                    if (req.hasSubProtocol("echo"))
                    {
                        resp.setAcceptedSubProtocol("echo");
                    }
                    return new EchoSocket();
                });
            }
        });
        context.addServlet(holder, "/ws");

        HandlerList handlers = new HandlerList();
        handlers.addHandler(context);
        handlers.addHandler(new DefaultHandler());
        server.setHandler(handlers);

        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testWebSocketProtocolResponse() throws Exception
    {
        URI uri = server.getURI();
        String host = uri.getHost();
        int port = uri.getPort();

        try (Socket client = new Socket(host, port))
        {
            byte[] key = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
            StringBuilder request = new StringBuilder();
            String secWebSocketKey = Base64.getEncoder().encodeToString(key);
            request.append("GET /ws HTTP/1.1\r\n")
                .append("Host: localhost\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Sec-WebSocket-version: 13\r\n")
                .append("Sec-WebSocket-Key:").append(secWebSocketKey).append("\r\n")
                .append("Sec-WebSocket-Protocol: echo\r\n")
                .append("\r\n");

            OutputStream output = client.getOutputStream();
            output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();

            BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String line = input.readLine();
            assertThat(line, containsString(" 101 "));
            HttpFields fields = new HttpFields();
            while ((line = input.readLine()) != null)
            {
                if (line.isEmpty())
                    break;
                int colon = line.indexOf(':');
                assertTrue(colon > 0);
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                fields.add(name, value);
            }

            assertEquals(1, fields.getValuesList("Sec-WebSocket-Protocol").size());
        }
    }
}
