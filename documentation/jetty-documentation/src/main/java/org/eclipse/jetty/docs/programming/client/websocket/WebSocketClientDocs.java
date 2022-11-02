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

package org.eclipse.jetty.docs.programming.client.websocket;

import java.net.HttpCookie;
import java.net.URI;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.ee10.websocket.api.Session;
import org.eclipse.jetty.ee10.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.ee10.websocket.client.JettyUpgradeListener;
import org.eclipse.jetty.ee10.websocket.client.WebSocketClient;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.util.component.LifeCycle;

@SuppressWarnings("unused")
public class WebSocketClientDocs
{
    public void start() throws Exception
    {
        // tag::start[]
        // Instantiate WebSocketClient.
        WebSocketClient webSocketClient = new WebSocketClient();

        // Configure WebSocketClient, for example:
        webSocketClient.setMaxTextMessageSize(8 * 1024);

        // Start WebSocketClient.
        webSocketClient.start();
        // end::start[]
    }

    public void startWithHttpClient() throws Exception
    {
        // tag::startWithHttpClient[]
        // Instantiate and configure HttpClient.
        HttpClient httpClient = new HttpClient();
        // For example, configure a proxy.
        httpClient.getProxyConfiguration().addProxy(new HttpProxy("localhost", 8888));

        // Instantiate WebSocketClient, passing HttpClient to the constructor.
        WebSocketClient webSocketClient = new WebSocketClient(httpClient);
        // Configure WebSocketClient, for example:
        webSocketClient.setMaxTextMessageSize(8 * 1024);

        // Start WebSocketClient; this implicitly starts also HttpClient.
        webSocketClient.start();
        // end::startWithHttpClient[]
    }

    public void stop() throws Exception
    {
        WebSocketClient webSocketClient = new WebSocketClient();
        webSocketClient.start();
        // tag::stop[]
        // Stop WebSocketClient.
        // Use LifeCycle.stop(...) to rethrow checked exceptions as unchecked.
        new Thread(() -> LifeCycle.stop(webSocketClient)).start();
        // end::stop[]
    }

    public void connectHTTP11() throws Exception
    {
        // tag::connectHTTP11[]
        // Use a standard, HTTP/1.1, HttpClient.
        HttpClient httpClient = new HttpClient();

        // Create and start WebSocketClient.
        WebSocketClient webSocketClient = new WebSocketClient(httpClient);
        webSocketClient.start();

        // The client-side WebSocket EndPoint that
        // receives WebSocket messages from the server.
        ClientEndPoint clientEndPoint = new ClientEndPoint();
        // The server URI to connect to.
        URI serverURI = URI.create("ws://domain.com/path");

        // Connect the client EndPoint to the server.
        CompletableFuture<Session> clientSessionPromise = webSocketClient.connect(clientEndPoint, serverURI);
        // end::connectHTTP11[]
    }

    public void connectHTTP2() throws Exception
    {
        // tag::connectHTTP2[]
        // Use the HTTP/2 transport for HttpClient.
        HTTP2Client http2Client = new HTTP2Client();
        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP2(http2Client));

        // Create and start WebSocketClient.
        WebSocketClient webSocketClient = new WebSocketClient(httpClient);
        webSocketClient.start();

        // The client-side WebSocket EndPoint that
        // receives WebSocket messages from the server.
        ClientEndPoint clientEndPoint = new ClientEndPoint();
        // The server URI to connect to.
        URI serverURI = URI.create("wss://domain.com/path");

        // Connect the client EndPoint to the server.
        CompletableFuture<Session> clientSessionPromise = webSocketClient.connect(clientEndPoint, serverURI);
        // end::connectHTTP2[]
    }

    public void connectHTTP2Dynamic() throws Exception
    {
        // tag::connectHTTP2Dynamic[]
        // Use the dynamic HTTP/2 transport for HttpClient.
        HTTP2Client http2Client = new HTTP2Client();
        HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client)));

        // Create and start WebSocketClient.
        WebSocketClient webSocketClient = new WebSocketClient(httpClient);
        webSocketClient.start();

        ClientEndPoint clientEndPoint = new ClientEndPoint();
        URI serverURI = URI.create("wss://domain.com/path");

        // Connect the client EndPoint to the server.
        CompletableFuture<Session> clientSessionPromise = webSocketClient.connect(clientEndPoint, serverURI);
        // end::connectHTTP2Dynamic[]
    }

    public void customHTTPRequest() throws Exception
    {
        WebSocketClient webSocketClient = new WebSocketClient(new HttpClient());
        webSocketClient.start();

        // tag::customHTTPRequest[]
        ClientEndPoint clientEndPoint = new ClientEndPoint();
        URI serverURI = URI.create("ws://domain.com/path");

        // Create a custom HTTP request.
        ClientUpgradeRequest customRequest = new ClientUpgradeRequest();
        // Specify a cookie.
        customRequest.getCookies().add(new HttpCookie("name", "value"));
        // Specify a custom header.
        customRequest.setHeader("X-Token", "0123456789ABCDEF");
        // Specify a custom sub-protocol.
        customRequest.setSubProtocols("chat");

        // Connect the client EndPoint to the server with a custom HTTP request.
        CompletableFuture<Session> clientSessionPromise = webSocketClient.connect(clientEndPoint, serverURI, customRequest);
        // end::customHTTPRequest[]
    }

    public void inspectHTTPResponse() throws Exception
    {
        WebSocketClient webSocketClient = new WebSocketClient(new HttpClient());
        webSocketClient.start();

        // tag::inspectHTTPResponse[]
        ClientEndPoint clientEndPoint = new ClientEndPoint();
        URI serverURI = URI.create("ws://domain.com/path");

        // The listener to inspect the HTTP response.
        JettyUpgradeListener listener = new JettyUpgradeListener()
        {
            @Override
            public void onHandshakeResponse(HttpRequest request, HttpResponse response)
            {
                // Inspect the HTTP response here.
            }
        };

        // Connect the client EndPoint to the server with a custom HTTP request.
        CompletableFuture<Session> clientSessionPromise = webSocketClient.connect(clientEndPoint, serverURI, null, listener);
        // end::inspectHTTPResponse[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    public class ClientEndPoint
    {
    }
}
