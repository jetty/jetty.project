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

package org.eclipse.jetty.proxy;

import java.util.List;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ReverseProxyTest
{
    private Server server;
    private ServerConnector serverConnector;
    private Server proxy;
    private ServerConnector proxyConnector;
    private HttpClient client;

    private void startServer(Handler handler) throws Exception
    {
        QueuedThreadPool serverPool = new QueuedThreadPool();
        serverPool.setName("server");
        server = new Server(serverPool);
        HttpConfiguration httpConfig = new HttpConfiguration();
        serverConnector = new ServerConnector(server, 1, 1, new HttpConnectionFactory(httpConfig), new HTTP2CServerConnectionFactory(httpConfig));
        server.addConnector(serverConnector);
        server.setHandler(handler);
        server.start();
    }

    private void startProxy(Handler handler) throws Exception
    {
        QueuedThreadPool proxyPool = new QueuedThreadPool();
        proxyPool.setName("proxy");
        proxy = new Server(proxyPool);
        HttpConfiguration configuration = new HttpConfiguration();
        configuration.setSendDateHeader(false);
        configuration.setSendServerVersion(false);
        proxyConnector = new ServerConnector(proxy, 1, 1, new HttpConnectionFactory(configuration), new HTTP2CServerConnectionFactory(configuration));
        proxy.addConnector(proxyConnector);
        proxy.setHandler(handler);
        proxy.start();
    }

    private void startClient() throws Exception
    {
        ClientConnector clientConnector = new ClientConnector();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientConnector.setExecutor(clientThreads);
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        client = new HttpClient(new HttpClientTransportDynamic(clientConnector, HttpClientConnectionFactory.HTTP11, new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client)));
        client.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(proxy);
        LifeCycle.stop(server);
    }

    private static List<HttpVersion> httpVersions()
    {
        return List.of(HttpVersion.HTTP_1_1, HttpVersion.HTTP_2);
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testSimple(HttpVersion httpVersion) throws Exception
    {
        String clientContent = "hello";
        String serverContent = "world";
        startServer(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                String requestContent = Content.Source.asString(request);
                assertEquals(clientContent, requestContent);
                Content.Sink.write(response, true, serverContent, callback);
            }
        });

        startProxy(new ProxyHandler.Reverse(clientToProxyRequest ->
            HttpURI.build(clientToProxyRequest.getHttpURI()).port(serverConnector.getLocalPort()))
        {
            @Override
            protected HttpClient newHttpClient()
            {
                ClientConnector proxyClientConnector = new ClientConnector();
                QueuedThreadPool proxyClientThreads = new QueuedThreadPool();
                proxyClientThreads.setName("proxy-client");
                proxyClientConnector.setExecutor(proxyClientThreads);
                HTTP2Client proxyHTTP2Client = new HTTP2Client(proxyClientConnector);
                return new HttpClient(new HttpClientTransportDynamic(proxyClientConnector, HttpClientConnectionFactory.HTTP11, new ClientConnectionFactoryOverHTTP2.HTTP2(proxyHTTP2Client)));
            }

            @Override
            protected org.eclipse.jetty.client.api.Request newProxyToServerRequest(Request clientToProxyRequest, HttpURI newHttpURI)
            {
                return super.newProxyToServerRequest(clientToProxyRequest, newHttpURI)
                    .version(httpVersion);
            }
        });

        startClient();

        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .version(httpVersion)
            .body(new StringRequestContent(clientContent))
            .send();
        assertEquals(serverContent, response.getContentAsString());
    }
}
