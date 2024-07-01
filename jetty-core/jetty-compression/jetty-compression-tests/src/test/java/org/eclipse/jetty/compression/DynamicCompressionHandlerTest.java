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

package org.eclipse.jetty.compression;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.compression.gzip.GzipDynamicCompressionCodec;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class DynamicCompressionHandlerTest
{
    private Server server;
    private HttpClient client;

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new HttpClient();
        client.getContentDecoderFactories().clear();
        client.start();
    }

    @AfterEach
    public void stopAll()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    private void startServer(Handler rootHandler) throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        server.setHandler(rootHandler);
        server.start();
    }

    @Test
    public void testDefaultConfiguration() throws Exception
    {
        DynamicCompressionHandler compressionHandler = new DynamicCompressionHandler();
        compressionHandler.setHandler(new HelloHandler());

        startServer(compressionHandler);

        URI serverURI = server.getURI();

        ContentResponse response = client.newRequest(serverURI.getHost(), serverURI.getPort())
            .method(HttpMethod.GET)
            .path("/hello")
            .send();
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContentAsString(), is("Hello World"));
    }

    @Test
    public void testDefaultGzipConfiguration() throws Exception
    {
        final byte[] buffer = new byte[2 * 1024 * 1024];
        Arrays.fill(buffer, (byte)'a');

        DynamicCompressionHandler compressionHandler = new DynamicCompressionHandler();
        compressionHandler.addCodec(new GzipDynamicCompressionCodec());
        compressionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                response.write(true, ByteBuffer.wrap(buffer), callback);
                return true;
            }
        });

        startServer(compressionHandler);

        URI serverURI = server.getURI();

        ContentResponse response = client.newRequest(serverURI.getHost(), serverURI.getPort())
            .method(HttpMethod.GET)
            .path("/hello")
            .send();
        dumpResponse(response);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getHeaders().get(HttpHeader.CONTENT_ENCODING), is("gzip"));
        assertThat(response.getHeaders().getLongField(HttpHeader.CONTENT_LENGTH), is(buffer.length));
        assertThat(response.getContentAsString(), is(new String(buffer, StandardCharsets.UTF_8)));
    }

    private void dumpResponse(org.eclipse.jetty.client.Response response)
    {
        System.out.printf("  %s %d %s%n", response.getVersion(), response.getStatus(), response.getReason());
        response.getHeaders().forEach((field) -> System.out.printf("  %s%n", field));
    }
}
