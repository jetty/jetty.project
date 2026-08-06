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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpField;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReverseProxyTest extends AbstractProxyTest
{
    public static Stream<Arguments> httpVersionsAndProxySendServerHeaders()
    {
        return Stream.of(
            Arguments.of(HttpVersion.HTTP_1_1, false),
            Arguments.of(HttpVersion.HTTP_1_1, true),
            Arguments.of(HttpVersion.HTTP_2, false),
            Arguments.of(HttpVersion.HTTP_2, true)
        );
    }

    @ParameterizedTest
    @MethodSource("httpVersionsAndProxySendServerHeaders")
    public void testSimple(HttpVersion httpVersion, boolean proxySendServerHeaders) throws Exception
    {
        String clientContent = "hello";
        String serverContent = "world";

        serverHttpConfig.setSendServerVersion(true);
        serverHttpConfig.setSendDateHeader(true);

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

        proxyHttpConfig.setSendServerVersion(proxySendServerHeaders);
        proxyHttpConfig.setSendDateHeader(proxySendServerHeaders);

        ProxyHandler.Reverse proxyHandler = new ProxyHandler.Reverse(clientToProxyRequest ->
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
                // Use the client-to-proxy protocol also for proxy-to-server communication.
                return super.newProxyToServerRequest(clientToProxyRequest, newHttpURI)
                    .version(httpVersion);
            }
        };
        startProxy(proxyHandler);

        startClient();

        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .version(httpVersion)
            .body(new StringRequestContent(clientContent))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertEquals(serverContent, response.getContentAsString());

        assertEquals(1, response.getHeaders().getValuesList("Server").size());
        assertEquals(1, response.getHeaders().getValuesList("Date").size());
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testMultiLineHeadersArePreserved(HttpVersion httpVersion) throws Exception
    {
        // Repeated request/response headers must be forwarded as distinct values,
        // while proxy-owned headers (Server, Date) must not be duplicated.
        serverHttpConfig.setSendServerVersion(true);
        serverHttpConfig.setSendDateHeader(true);

        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                List<String> requestValues = request.getHeaders().stream()
                    .filter(field -> field.getName().equalsIgnoreCase("X-Request"))
                    .map(HttpField::getValue)
                    .toList();
                assertEquals(List.of("req1", "req2"), requestValues);

                response.getHeaders().add("X-Response", "resp1");
                response.getHeaders().add("X-Response", "resp2");
                callback.succeeded();
                return true;
            }
        });

        proxyHttpConfig.setSendServerVersion(true);
        proxyHttpConfig.setSendDateHeader(true);

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
                // Use the client-to-proxy protocol also for proxy-to-server communication.
                return super.newProxyToServerRequest(clientToProxyRequest, newHttpURI)
                    .version(httpVersion);
            }
        });

        startClient();

        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .version(httpVersion)
            .headers(headers -> headers.add("X-Request", "req1").add("X-Request", "req2"))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());

        List<String> responseValues = response.getHeaders().stream()
            .filter(field -> field.getName().equalsIgnoreCase("X-Response"))
            .map(HttpField::getValue)
            .toList();
        assertEquals(List.of("resp1", "resp2"), responseValues);

        assertEquals(1, response.getHeaders().getValuesList("Server").size());
        assertEquals(1, response.getHeaders().getValuesList("Date").size());
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
        serverHttpConfig.setMaxResponseHeaderSize(maxResponseHeadersSize);
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Use "+" because in HTTP/2 is Huffman encoded in more than 8 bits.
                response.getHeaders().put("X-Large", "+".repeat(maxResponseHeadersSize));

                // With HTTP/1.1, calling response.write() or callback.succeeded()
                // would trigger ErrorHandler and result in a 500 to the proxy.
                // With HTTP/2, the HpackContext cannot be rolled back, so the
                // connection is aborted, which the proxy interprets as a 502.
                // The same for HTTP/3 and QpackContext.
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

        if (httpVersion.compareTo(HttpVersion.HTTP_2) < 0)
        {
            assertFalse(serverToProxyFailureLatch.await(1, TimeUnit.SECONDS));
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, response.getStatus());
        }
        else
        {
            assertTrue(serverToProxyFailureLatch.await(5, TimeUnit.SECONDS));
            assertEquals(HttpStatus.BAD_GATEWAY_502, response.getStatus());
        }
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
        proxyHttpConfig.setMaxResponseHeaderSize(maxResponseHeadersSize);
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
        HTTP2Client proxyHTTP2Client = new HTTP2Client(proxyClientConnector);
        return new HttpClient(new HttpClientTransportDynamic(proxyClientConnector, HttpClientConnectionFactory.HTTP11, new ClientConnectionFactoryOverHTTP2.HTTP2(proxyHTTP2Client)));
    }
}
