//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DelayedHandler;
import org.eclipse.jetty.server.handler.EchoHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class HandlerStackTest
{
    private Server _server;
    private ServerConnector _connector;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        Handler.Wrapper stackSnap = new Handler.Wrapper()
        {
            @Override
            public Request.Processor handle(Request request) throws Exception
            {
                request.getHttpChannel().addStreamWrapper(s -> new HttpStream.Wrapper(s)
                {
                    @Override
                    public void succeeded()
                    {
                        new Throwable().printStackTrace();
                        super.succeeded();
                    }
                });
                return super.handle(request);
            }
        };
        _server.setHandler(stackSnap);

        DelayedHandler.UntilContent delayedHandler = new DelayedHandler.UntilContent();
        stackSnap.setHandler(delayedHandler);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        delayedHandler.setHandler(contexts);

        ContextHandler context = new ContextHandler("/ctx");
        contexts.addHandler(context);

        EchoHandler echo = new EchoHandler();
        context.setHandler(echo);

        _server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testPOST() throws Exception
    {
        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = """
                POST /ctx/path HTTP/1.1\r
                Host: localhost\r
                Content-Type: text/plain\r
                Content-Length: 11\r
                \r
                Hello World
                """;
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = new String(response.getContentBytes(), StandardCharsets.UTF_8);
            assertThat(content, containsString("Hello World"));
        }
    }
}
