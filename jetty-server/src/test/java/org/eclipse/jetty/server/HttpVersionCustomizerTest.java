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
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class HttpVersionCustomizerTest
{
    @Test
    public void testCustomizeHttpVersion() throws Exception
    {
        Server server = new Server();
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.addCustomizer((connector, config, request) -> request.setHttpVersion(HttpVersion.HTTP_1_1));
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        server.addConnector(connector);
        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setStatus(500);
                assertEquals(HttpVersion.HTTP_1_1.asString(), request.getProtocol());
                response.setStatus(200);
                response.getWriter().println("OK");
            }
        });
        server.start();

        try
        {
            try (SocketChannel socket = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
            {
                HttpTester.Request request = HttpTester.newRequest();
                request.setVersion(HttpVersion.HTTP_1_0);
                socket.write(request.generate());

                HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(socket));
                assertNotNull(response);
                assertThat(response.getStatus(), Matchers.equalTo(HttpStatus.OK_200));
            }
        }
        finally
        {
            server.stop();
        }
    }
}
