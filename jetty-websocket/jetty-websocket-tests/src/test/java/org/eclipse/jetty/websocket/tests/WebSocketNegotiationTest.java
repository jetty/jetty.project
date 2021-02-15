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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Base64;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

public class WebSocketNegotiationTest
{
    public static class EchoServlet extends WebSocketServlet
    {
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.register(EchoSocket.class);
        }
    }

    private Server server;
    private ServerConnector connector;
    private WebSocketClient client;

    @BeforeEach
    public void start() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        contextHandler.addServlet(EchoServlet.class, "/");
        server.setHandler(contextHandler);

        client = new WebSocketClient();

        server.start();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void testValidUpgradeRequest() throws Exception
    {
        Socket client = new Socket();
        client.connect(new InetSocketAddress("127.0.0.1", connector.getLocalPort()));

        HttpFields httpFields = newUpgradeRequest(null);
        httpFields.remove(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL);
        httpFields.add(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, "testInvalidUpgradeRequest");
        String upgradeRequest = "GET / HTTP/1.1\r\n" + httpFields;
        client.getOutputStream().write(upgradeRequest.getBytes(ISO_8859_1));
        String response = getUpgradeResponse(client.getInputStream());

        assertThat(response, startsWith("HTTP/1.1 101 Switching Protocols"));
        assertThat(response, containsString("Sec-WebSocket-Accept: +WahVcVmeMLKQUMm0fvPrjSjwzI="));
    }

    @Test
    public void testInvalidUpgradeRequestNoKey() throws Exception
    {
        Socket client = new Socket();
        client.connect(new InetSocketAddress("127.0.0.1", connector.getLocalPort()));

        HttpFields httpFields = newUpgradeRequest(null);
        httpFields.remove(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL);
        httpFields.add(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, "testInvalidUpgradeRequest");
        httpFields.remove(HttpHeader.SEC_WEBSOCKET_KEY);

        String upgradeRequest = "GET / HTTP/1.1\r\n" + httpFields;
        client.getOutputStream().write(upgradeRequest.getBytes(ISO_8859_1));
        String response = getUpgradeResponse(client.getInputStream());

        assertThat(response, containsString("400 "));
    }

    protected static HttpFields newUpgradeRequest(String extensions)
    {
        HttpFields fields = new HttpFields();
        fields.add(HttpHeader.HOST, "127.0.0.1");
        fields.add(HttpHeader.UPGRADE, "websocket");
        fields.add(HttpHeader.CONNECTION, "Upgrade");
        fields.add(HttpHeader.SEC_WEBSOCKET_KEY, Base64.getEncoder().encodeToString("0123456701234567".getBytes(ISO_8859_1)));
        fields.add(HttpHeader.SEC_WEBSOCKET_VERSION, "13");
        fields.add(HttpHeader.PRAGMA, "no-cache");
        fields.add(HttpHeader.CACHE_CONTROL, "no-cache");
        fields.add(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, "test");
        if (extensions != null)
            fields.add(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, extensions);

        return fields;
    }

    protected static String getUpgradeResponse(InputStream in) throws IOException
    {
        int state = 0;
        StringBuilder buffer = new StringBuilder();
        while (state < 4)
        {
            int i = in.read();
            if (i < 0)
                throw new EOFException();
            int b = (byte)(i & 0xff);
            buffer.append((char)b);
            switch (state)
            {
                case 0:
                    state = (b == '\r') ? 1 : 0;
                    break;
                case 1:
                    state = (b == '\n') ? 2 : 0;
                    break;
                case 2:
                    state = (b == '\r') ? 3 : 0;
                    break;
                case 3:
                    state = (b == '\n') ? 4 : 0;
                    break;
                default:
                    state = 0;
            }
        }

        return buffer.toString();
    }
}
