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

package org.eclipse.jetty.websocket.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.websocket.server.browser.BrowserDebugTool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class WebSocketProtocolTest
{
    private BrowserDebugTool server;

    @Before
    public void startServer() throws Exception
    {
        server = new BrowserDebugTool();
        server.prepare(0);
        server.start();
    }

    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testWebSocketProtocolResponse() throws Exception
    {
        try (Socket client = new Socket("localhost", server.getPort()))
        {
            byte[] key = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
            StringBuilder request = new StringBuilder();
            request.append("GET / HTTP/1.1\r\n")
                    .append("Host: localhost\r\n")
                    .append("Connection: Upgrade\r\n")
                    .append("Upgrade: websocket\r\n")
                    .append("Sec-WebSocket-version: 13\r\n")
                    .append("Sec-WebSocket-Key:").append(B64Code.encode(key)).append("\r\n")
                    .append("Sec-WebSocket-Protocol: echo\r\n")
                    .append("\r\n");

            OutputStream output = client.getOutputStream();
            output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();

            BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String line = input.readLine();
            Assert.assertTrue(line.contains(" 101 "));
            HttpFields fields = new HttpFields();
            while ((line = input.readLine()) != null)
            {
                if (line.isEmpty())
                    break;
                int colon = line.indexOf(':');
                Assert.assertTrue(colon > 0);
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                fields.add(name, value);
            }

            Assert.assertEquals(1, fields.getValuesList("Sec-WebSocket-Protocol").size());
        }
    }
}
