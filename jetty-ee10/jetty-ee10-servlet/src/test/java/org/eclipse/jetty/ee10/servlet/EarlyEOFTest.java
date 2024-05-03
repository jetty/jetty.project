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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EarlyEOFTest
{
    private Server server;
    private ServerConnector connector;

    private void start(HttpServlet servlet) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler("/ctx");
        context.addServlet(servlet, "/path/*");
        server.setHandler(context);

        server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        server.stop();
    }

    @Test
    public void testEarlyEOF() throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                AsyncContext asyncContext = request.startAsync();

                ServletInputStream input = request.getInputStream();
                assertEquals('0', input.read());

                // Early EOF.
                assertThrows(EofException.class, input::read);

                // Must be able to send a response.
                response.setStatus(HttpStatus.ACCEPTED_202);
                ServletOutputStream output = response.getOutputStream();
                output.print("out");
                output.close();

                asyncContext.complete();
            }
        });

        try (SocketChannel channel = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            String request = """
                POST /ctx/path/early HTTP/1.1\r
                Host: localhost\r
                Content-Length: 10\r
                
                0""";
            channel.write(UTF_8.encode(request));
            // Close output before sending the whole content.
            channel.shutdownOutput();

            HttpTester.Response response = HttpTester.parseResponse(channel);

            assertThat(response.getStatus(), is(HttpStatus.ACCEPTED_202));
            assertTrue(response.contains(HttpHeader.CONNECTION, "close"));
            assertEquals("out", response.getContent());

            // Connection must be closed by server.
            assertEquals(-1, channel.read(ByteBuffer.allocate(512)));
        }
    }
}
