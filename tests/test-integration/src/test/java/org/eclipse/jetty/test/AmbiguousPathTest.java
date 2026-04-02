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

package org.eclipse.jetty.test;

import java.util.function.Supplier;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AmbiguousPathTest
{
    private Server server;
    private HttpClient client;

    @AfterEach
    public void stopAll()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @Test
    public void testAmbiguousPaths() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                String body = """
                    Client: %s
                    Request URI: %s
                    """.formatted(
                    Request.getRemoteAddr(request),
                    request.getHttpURI().toString()
                );
                Content.Sink.write(response, true, body, callback);
                return true;
            }
        };
        ContextHandler rootContext = new ContextHandler();
        rootContext.setContextPath("/");
        rootContext.setHandler(handler);

        ContextHandlerCollection contextHandlerCollection = new ContextHandlerCollection();
        contextHandlerCollection.addHandler(rootContext);

        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(contextHandlerCollection);
        server.start();

        String baseURI = "http://%s".formatted(server.getURI().getAuthority());

        client = new HttpClient();
        client.start();

        for (String ambiguous : new String[]{"/foo%2Fbar", "/foo//bar", "/foo/..;/bar", "/foo/%2e%2e;param/bar"})
        {
            ContentResponse response = client.GET(baseURI + ambiguous);
            assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus(), new ResponseDetails(response));
        }
    }

    protected static class ResponseDetails implements Supplier<String>
    {
        private final ContentResponse response;

        public ResponseDetails(ContentResponse response)
        {
            this.response = response;
        }

        @Override
        public String get()
        {
            StringBuilder ret = new StringBuilder();
            ret.append(response.toString()).append(System.lineSeparator());
            ret.append(response.getHeaders().toString()).append(System.lineSeparator());
            ret.append(response.getContentAsString()).append(System.lineSeparator());
            return ret.toString();
        }
    }
}
