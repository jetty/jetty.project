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

package org.eclipse.jetty.proxy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReverseProxyTest extends AbstractProxyTest
{
    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testSimple(HttpVersion httpVersion) throws Exception
    {
        String clientContent = "hello";
        String serverContent = "world";
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                String requestContent = Content.Source.asString(request);
                assertEquals(clientContent, requestContent);
                Content.Sink.write(response, true, serverContent, callback);
                return true;
            }
        });

        startProxy(new ProxyHandler.Reverse(clientToProxyRequest ->
            HttpURI.build(clientToProxyRequest.getHttpURI()).port(serverConnector.getLocalPort()))
        {
            @Override
            protected HttpClient newHttpClient()
            {
                return newProxyHttpClient();
            }

            @Override
            protected org.eclipse.jetty.client.Request newProxyToServerRequest(Request clientToProxyRequest, HttpURI newHttpURI)
            {
                // Use the client to proxy protocol also from the proxy to server.
                return super.newProxyToServerRequest(clientToProxyRequest, newHttpURI)
                    .version(httpVersion);
            }
        });

        startClient();

        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .version(httpVersion)
            .body(new StringRequestContent(clientContent))
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(serverContent, response.getContentAsString());
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testEmptyHeaderValue(HttpVersion httpVersion) throws Exception
    {
        String emptyHeaderName = "X-Empty";
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                assertEquals("", request.getHeaders().get(emptyHeaderName));
                response.getHeaders().put(emptyHeaderName, "");
                callback.succeeded();
                return true;
            }
        });
        startProxy(new ProxyHandler.Reverse(clientToProxyRequest ->
            HttpURI.build(clientToProxyRequest.getHttpURI()).port(serverConnector.getLocalPort()))
        {
            @Override
            protected HttpClient newHttpClient()
            {
                return newProxyHttpClient();
            }

            @Override
            protected org.eclipse.jetty.client.Request newProxyToServerRequest(Request clientToProxyRequest, HttpURI newHttpURI)
            {
                // Use the client to proxy protocol also from the proxy to server.
                return super.newProxyToServerRequest(clientToProxyRequest, newHttpURI)
                    .version(httpVersion);
            }
        });
        startClient();

        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .version(httpVersion)
            .headers(headers -> headers.put(emptyHeaderName, ""))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertEquals("", response.getHeaders().get(emptyHeaderName));
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testServerResponseHeadersTooLargeForServerConfiguration(HttpVersion httpVersion) throws Exception
    {
        // Server is not able to write response and aborts.
        // Proxy sees the abort and sends 502 to client.

        int maxResponseHeadersSize = 256;
        serverHttpConfig.setResponseHeaderSize(maxResponseHeadersSize);
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.getHeaders().put("X-Large", "A".repeat(maxResponseHeadersSize));

                // With HTTP/1.1, calling response.write() would fail the Handler callback
                // which would trigger ErrorHandler and result in a 500 to the proxy.
//                response.write(true, null, callback);

                // With HTTP/1.1, succeeding the callback before the actual last write
                // results in skipping the ErrorHandler and aborting the response and
                // the connection, which the proxy interprets as a 502.
                // HTTP/2 always behaves by aborting the connection.
                callback.succeeded();
                return true;
            }
        });

        CountDownLatch serverToProxyFailureLatch = new CountDownLatch(1);
        startProxy(new ProxyHandler.Reverse(clientToProxyRequest ->
            HttpURI.build(clientToProxyRequest.getHttpURI()).port(serverConnector.getLocalPort()))
        {
            @Override
            protected HttpClient newHttpClient()
            {
                return newProxyHttpClient();
            }

            @Override
            protected org.eclipse.jetty.client.Request newProxyToServerRequest(Request clientToProxyRequest, HttpURI newHttpURI)
            {
                // Use the client to proxy protocol also from the proxy to server.
                return super.newProxyToServerRequest(clientToProxyRequest, newHttpURI)
                    .version(httpVersion);
            }

            @Override
            protected void onServerToProxyResponseFailure(Request clientToProxyRequest, org.eclipse.jetty.client.Request proxyToServerRequest, org.eclipse.jetty.client.Response serverToProxyResponse, Response proxyToClientResponse, Callback proxyToClientCallback, Throwable failure)
            {
                serverToProxyFailureLatch.countDown();
                super.onServerToProxyResponseFailure(clientToProxyRequest, proxyToServerRequest, serverToProxyResponse, proxyToClientResponse, proxyToClientCallback, failure);
            }
        });

        startClient();

        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .version(httpVersion)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertTrue(serverToProxyFailureLatch.await(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.BAD_GATEWAY_502, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testServerResponseHeadersTooLargeForProxyConfiguration(HttpVersion httpVersion) throws Exception
    {
        // Server is able to write the response.
        // Proxy cannot parse the response from server, fails and sends 502 to client.

        int maxResponseHeadersSize = 256;
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.getHeaders().put("X-Large", "A".repeat(maxResponseHeadersSize));
                callback.succeeded();
                return true;
            }
        });

        CountDownLatch serverToProxyFailureLatch = new CountDownLatch(1);
        startProxy(new ProxyHandler.Reverse(clientToProxyRequest ->
            HttpURI.build(clientToProxyRequest.getHttpURI()).port(serverConnector.getLocalPort()))
        {
            @Override
            protected HttpClient newHttpClient()
            {
                HttpClient httpClient = newProxyHttpClient();
                httpClient.setMaxResponseHeadersSize(maxResponseHeadersSize);
                return httpClient;
            }

            @Override
            protected org.eclipse.jetty.client.Request newProxyToServerRequest(Request clientToProxyRequest, HttpURI newHttpURI)
            {
                // Use the client to proxy protocol also from the proxy to server.
                return super.newProxyToServerRequest(clientToProxyRequest, newHttpURI)
                    .version(httpVersion);
            }

            @Override
            protected void onServerToProxyResponseFailure(Request clientToProxyRequest, org.eclipse.jetty.client.Request proxyToServerRequest, org.eclipse.jetty.client.Response serverToProxyResponse, Response proxyToClientResponse, Callback proxyToClientCallback, Throwable failure)
            {
                serverToProxyFailureLatch.countDown();
                super.onServerToProxyResponseFailure(clientToProxyRequest, proxyToServerRequest, serverToProxyResponse, proxyToClientResponse, proxyToClientCallback, failure);
            }
        });

        startClient();

        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .version(httpVersion)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertTrue(serverToProxyFailureLatch.await(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.BAD_GATEWAY_502, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testProxyResponseHeadersTooLargeForProxyConfiguration(HttpVersion httpVersion) throws Exception
    {
        // Proxy client receives response from server.
        // Proxy server is not able to write the response to client.

        int maxResponseHeadersSize = 256;
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        CountDownLatch proxyToClientFailureLatch = new CountDownLatch(1);
        proxyHttpConfig.setResponseHeaderSize(maxResponseHeadersSize);
        startProxy(new ProxyHandler.Reverse(clientToProxyRequest ->
            HttpURI.build(clientToProxyRequest.getHttpURI()).port(serverConnector.getLocalPort()))
        {
            @Override
            protected HttpClient newHttpClient()
            {
                return newProxyHttpClient();
            }

            @Override
            protected org.eclipse.jetty.client.Request newProxyToServerRequest(Request clientToProxyRequest, HttpURI newHttpURI)
            {
                // Use the client to proxy protocol also from the proxy to server.
                return super.newProxyToServerRequest(clientToProxyRequest, newHttpURI)
                    .version(httpVersion);
            }

            @Override
            protected org.eclipse.jetty.client.Response.CompleteListener newServerToProxyResponseListener(Request clientToProxyRequest, org.eclipse.jetty.client.Request proxyToServerRequest, Response proxyToClientResponse, Callback proxyToClientCallback)
            {
                return new ProxyResponseListener(clientToProxyRequest, proxyToServerRequest, proxyToClientResponse, proxyToClientCallback)
                {
                    @Override
                    public void onHeaders(org.eclipse.jetty.client.Response serverToProxyResponse)
                    {
                        proxyToClientResponse.getHeaders().put("X-Large", "A".repeat(maxResponseHeadersSize));
                        super.onHeaders(serverToProxyResponse);
                    }
                };
            }

            @Override
            protected void onProxyToClientResponseFailure(Request clientToProxyRequest, org.eclipse.jetty.client.Request proxyToServerRequest, org.eclipse.jetty.client.Response serverToProxyResponse, Response proxyToClientResponse, Callback proxyToClientCallback, Throwable failure)
            {
                proxyToClientFailureLatch.countDown();
                super.onProxyToClientResponseFailure(clientToProxyRequest, proxyToServerRequest, serverToProxyResponse, proxyToClientResponse, proxyToClientCallback, failure);
            }
        });

        startClient();

        var request = client.newRequest("localhost", proxyConnector.getLocalPort())
            .version(httpVersion)
            .timeout(5, TimeUnit.SECONDS);
        CompletableFuture<ContentResponse> completable = new CompletableResponseListener(request).send();

        assertTrue(proxyToClientFailureLatch.await(5, TimeUnit.SECONDS));

        completable.handle((response, failure) ->
        {
            switch (httpVersion)
            {
                case HTTP_1_1 ->
                {
                    // HTTP/1.1 fails to generate the response, but does not commit,
                    // so it is able to write an error response to the client.

                    assertNotNull(response);
                    assertNull(failure);
                    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, response.getStatus());
                }
                case HTTP_2 ->
                {
                    // HTTP/2 fails to generate the response, sends a GOAWAY,
                    // and the client aborts the response.
                    assertNull(response);
                    assertNotNull(failure);
                }
            }
            return null;
        }).get(5, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testProxyResponseHeadersTooLargeForClientConfiguration(HttpVersion httpVersion) throws Exception
    {
        int maxResponseHeadersSize = 256;
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        startProxy(new ProxyHandler.Reverse(clientToProxyRequest ->
            HttpURI.build(clientToProxyRequest.getHttpURI()).port(serverConnector.getLocalPort()))
        {
            @Override
            protected HttpClient newHttpClient()
            {
                return newProxyHttpClient();
            }

            @Override
            protected org.eclipse.jetty.client.Request newProxyToServerRequest(Request clientToProxyRequest, HttpURI newHttpURI)
            {
                // Use the client to proxy protocol also from the proxy to server.
                return super.newProxyToServerRequest(clientToProxyRequest, newHttpURI)
                    .version(httpVersion);
            }

            @Override
            protected org.eclipse.jetty.client.Response.CompleteListener newServerToProxyResponseListener(Request clientToProxyRequest, org.eclipse.jetty.client.Request proxyToServerRequest, Response proxyToClientResponse, Callback proxyToClientCallback)
            {
                return new ProxyResponseListener(clientToProxyRequest, proxyToServerRequest, proxyToClientResponse, proxyToClientCallback)
                {
                    @Override
                    public void onHeaders(org.eclipse.jetty.client.Response serverToProxyResponse)
                    {
                        proxyToClientResponse.getHeaders().put("X-Large", "A".repeat(maxResponseHeadersSize));
                        super.onHeaders(serverToProxyResponse);
                    }
                };
            }
        });

        startClient(client -> client.setMaxResponseHeadersSize(maxResponseHeadersSize));

        CountDownLatch responseFailureLatch = new CountDownLatch(1);
        assertThrows(ExecutionException.class, () -> client.newRequest("localhost", proxyConnector.getLocalPort())
            .version(httpVersion)
            .onResponseFailure((r, x) -> responseFailureLatch.countDown())
            .timeout(5, TimeUnit.SECONDS)
            .send());

        assertTrue(responseFailureLatch.await(5, TimeUnit.SECONDS));
    }

    private static HttpClient newProxyHttpClient()
    {
        ClientConnector proxyClientConnector = new ClientConnector();
        QueuedThreadPool proxyClientThreads = new QueuedThreadPool();
        proxyClientThreads.setName("proxy-client");
        proxyClientConnector.setExecutor(proxyClientThreads);
        HTTP2Client proxyHTTP2Client = new HTTP2Client(proxyClientConnector);
        return new HttpClient(new HttpClientTransportDynamic(proxyClientConnector, HttpClientConnectionFactory.HTTP11, new ClientConnectionFactoryOverHTTP2.HTTP2(proxyHTTP2Client)));
    }
}
