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

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests of the nested ee9/ee8 {@link BufferedResponseHandler}
 */
public class BufferedResponseHandlerTest
{
    private Server server;
    private LocalConnector localConnector;

    public void startServer(Consumer<Server> serverConsumer) throws Exception
    {
        server = new Server();

        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        serverConsumer.accept(server);
        server.start();
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testEmptyFlushThenClose() throws Exception
    {
        startServer((server) ->
        {
            ContextHandlerCollection contexts = new ContextHandlerCollection();
            server.setHandler(contexts);

            ContextHandler rootContextHandler = new org.eclipse.jetty.ee9.nested.ContextHandler();
            rootContextHandler.setContextPath("/");
            BufferedResponseHandler bufferedResponseHandler = new BufferedResponseHandler();
            rootContextHandler.setHandler(bufferedResponseHandler);

            AbstractHandler endpointHandler = new AbstractHandler()
            {
                @Override
                public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
                {
                    response.setStatus(400);
                    response.flushBuffer();
                    response.getOutputStream().close();
                }
            };
            bufferedResponseHandler.setHandler(endpointHandler);
            server.setHandler(rootContextHandler);
        });

        String rawRequest = """
            GET /test HTTP/1.1
            Host: local
            Connection: close
            
            """;
        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertEquals(400, response.getStatus());
    }

    @Test
    public void testEmptyFlushWriteSomeThenClose() throws Exception
    {
        final String content = "X".repeat(128) + "\n";

        startServer((server) ->
        {
            ContextHandlerCollection contexts = new ContextHandlerCollection();
            server.setHandler(contexts);

            ContextHandler rootContextHandler = new org.eclipse.jetty.ee9.nested.ContextHandler();
            rootContextHandler.setContextPath("/");
            BufferedResponseHandler bufferedResponseHandler = new BufferedResponseHandler();
            rootContextHandler.setHandler(bufferedResponseHandler);

            AbstractHandler endpointHandler = new AbstractHandler()
            {
                @Override
                public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
                {
                    response.setStatus(200);
                    response.flushBuffer();
                    response.getOutputStream().write(content.getBytes(StandardCharsets.UTF_8));
                    response.getOutputStream().close();
                }
            };
            bufferedResponseHandler.setHandler(endpointHandler);
            server.setHandler(rootContextHandler);
        });

        String rawRequest = """
            GET /test HTTP/1.1
            Host: local
            Connection: close
            
            """;
        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertEquals(200, response.getStatus());
        assertThat(response.getContent(), is(content));
    }

    @Test
    public void testFlushWriteManyThenClose() throws Exception
    {
        final String content = "X".repeat(128) + "\n";

        startServer((server) ->
        {
            ContextHandlerCollection contexts = new ContextHandlerCollection();
            server.setHandler(contexts);

            ContextHandler rootContextHandler = new org.eclipse.jetty.ee9.nested.ContextHandler();
            rootContextHandler.setContextPath("/");
            BufferedResponseHandler bufferedResponseHandler = new BufferedResponseHandler();
            rootContextHandler.setHandler(bufferedResponseHandler);

            AbstractHandler endpointHandler = new AbstractHandler()
            {
                @Override
                public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
                {
                    response.setStatus(200);
                    for (int i = 0; i < 10; i++)
                    {
                        response.getOutputStream().flush();
                        response.getOutputStream().write(content.getBytes(StandardCharsets.UTF_8));
                    }
                    response.getOutputStream().close();
                }
            };
            bufferedResponseHandler.setHandler(endpointHandler);
            server.setHandler(rootContextHandler);
        });

        String rawRequest = """
            GET /test HTTP/1.1
            Host: local
            Connection: close
            
            """;
        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertEquals(200, response.getStatus());
        assertThat(response.getContent(), is(content.repeat(10)));
    }
}
