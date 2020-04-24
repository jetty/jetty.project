//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.test;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.HttpCookie;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.proxy.AbstractProxyServlet;
import org.eclipse.jetty.proxy.AsyncMiddleManServlet;
import org.eclipse.jetty.proxy.AsyncProxyServlet;
import org.eclipse.jetty.proxy.ProxyServlet;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.eclipse.jetty.http.tools.matchers.HttpFieldsMatchers.containsHeader;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProxyCommunicationsTest
{
    private static final String PROXIED_HEADER = "X-Proxied";

    public enum Protocol
    {
        HTTP1,
        SSL_HTTP1,
        HTTP2;
    }

    private HttpClient client;
    private Server proxy;
    private ServerConnector proxyConnector;
    private ServletContextHandler proxyContext;
    private AbstractProxyServlet proxyServlet;
    private Server server;
    private ServerConnector serverConnector;
    private URI serverURI;

    private ServerConnector newServerConnector(Server server, Protocol protocol, HttpConfiguration configuration)
    {
        switch (protocol)
        {
            case HTTP1:
            {
                HttpConnectionFactory h1 = new HttpConnectionFactory(configuration);
                return new ServerConnector(server, h1);
            }
            case SSL_HTTP1:
            {
                SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
                sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
                sslContextFactory.setKeyStorePassword("storepwd");

                HttpConfiguration sslConfig = new HttpConfiguration(configuration);
                sslConfig.addCustomizer(new SecureRequestCustomizer());

                HttpConnectionFactory h1 = new HttpConnectionFactory(sslConfig);
                SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, h1.getProtocol());

                return new ServerConnector(server, ssl, h1);
            }
            case HTTP2:
            {
                SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
                sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
                sslContextFactory.setKeyStorePassword("storepwd");

                HttpConfiguration sslConfig = new HttpConfiguration(configuration);
                sslConfig.addCustomizer(new SecureRequestCustomizer());

                HttpConnectionFactory h1 = new HttpConnectionFactory(sslConfig);
                ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
                alpn.setDefaultProtocol(h1.getProtocol());
                SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());
                HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(sslConfig);

                return new ServerConnector(server, 1, 1, ssl, alpn, h2, h1);
            }
            default:
                throw new RuntimeException("Unrecognized Server Protocol: " + protocol);
        }
    }

    private void startServer(Protocol serverProtocol, HttpServlet servlet) throws Exception
    {
        QueuedThreadPool serverPool = new QueuedThreadPool();
        serverPool.setName("server");
        server = new Server(serverPool);

        HttpConfiguration configuration = new HttpConfiguration();
        configuration.setSendDateHeader(false);
        configuration.setSendServerVersion(false);

        serverConnector = newServerConnector(server, serverProtocol, configuration);
        server.addConnector(serverConnector);

        ServletContextHandler appCtx = new ServletContextHandler(server, "/", true, false);
        ServletHolder appServletHolder = new ServletHolder(servlet);
        appCtx.addServlet(appServletHolder, "/*");

        server.start();

        serverURI = server.getURI().resolve("/");
    }

    private void startProxy(Protocol proxyProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startProxy(proxyProtocol, proxyServletClass, new HashMap<>());
    }

    private void startProxy(Protocol proxyProtocol, Class<? extends ProxyServlet> proxyServletClass, Map<String, String> initParams) throws Exception
    {
        QueuedThreadPool proxyPool = new QueuedThreadPool();
        proxyPool.setName("proxy");
        proxy = new Server(proxyPool);

        HttpConfiguration configuration = new HttpConfiguration();
        configuration.setSendDateHeader(false);
        configuration.setSendServerVersion(false);
        String value = initParams.get("outputBufferSize");
        if (value != null)
            configuration.setOutputBufferSize(Integer.parseInt(value));

        proxyConnector = newServerConnector(proxy, proxyProtocol, configuration);
        proxy.addConnector(proxyConnector);

        proxyServlet = proxyServletClass.getDeclaredConstructor().newInstance();

        proxyContext = new ServletContextHandler(proxy, "/", true, false);
        ServletHolder proxyServletHolder = new ServletHolder(proxyServlet);
        proxyServletHolder.setInitParameters(initParams);
        proxyContext.addServlet(proxyServletHolder, "/*");

        proxy.start();
    }

    private void startClient(Protocol proxyProtocol) throws Exception
    {
        startClient(proxyProtocol, null);
    }

    private void startClient(Protocol proxyProtocol, Consumer<HttpClient> consumer) throws Exception
    {
        client = prepareClient(proxyProtocol, consumer);
    }

    private HttpClient prepareClient(Protocol proxyProtocol, Consumer<HttpClient> consumer) throws Exception
    {
        HttpClientTransport transport = null;
        ProxyConfiguration.Proxy proxy = null;

        switch (proxyProtocol)
        {
            case HTTP1:
            {
                transport = new HttpClientTransportOverHTTP();
                proxy = new HttpProxy("localhost", proxyConnector.getLocalPort());
                break;
            }
            case SSL_HTTP1:
            {
                SslContextFactory.Client clientSsl = new SslContextFactory.Client();
                clientSsl.setEndpointIdentificationAlgorithm(null);
                clientSsl.setTrustAll(true);
                ClientConnector clientConnector = new ClientConnector();
                clientConnector.setSelectors(1);
                clientConnector.setSslContextFactory(clientSsl);
                transport = new HttpClientTransportOverHTTP(clientConnector);
                Origin.Address proxyAddress = new Origin.Address("localhost", proxyConnector.getLocalPort());
                proxy = new HttpProxy(proxyAddress, true);
                break;
            }
            case HTTP2:
            {
                SslContextFactory.Client clientSsl = new SslContextFactory.Client();
                clientSsl.setEndpointIdentificationAlgorithm(null);
                clientSsl.setTrustAll(true);
                ClientConnector clientConnector = new ClientConnector();
                clientConnector.setSelectors(1);
                clientConnector.setSslContextFactory(clientSsl);
                transport = new HttpClientTransportOverHTTP2(new HTTP2Client(clientConnector));
                Origin.Address proxyAddress = new Origin.Address("localhost", proxyConnector.getLocalPort());
                Origin.Protocol proxyOriginProtocol = new Origin.Protocol(List.of("http/2"), true);
                proxy = new HttpProxy(proxyAddress, true, proxyOriginProtocol);
                break;
            }
        }

        assertNotNull(proxy, "Unsupported Proxy Protocol: " + proxyProtocol);

        QueuedThreadPool clientPool = new QueuedThreadPool();
        clientPool.setName("client");

        HttpClient result = new HttpClient(transport);
        result.setExecutor(clientPool);
        result.getProxyConfiguration().getProxies().add(proxy);

        if (consumer != null)
            consumer.accept(result);
        result.start();
        return result;
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(proxy);
        LifeCycle.stop(server);
    }

    public static Stream<Arguments> scenarios()
    {
        List<Arguments> args = new ArrayList<>();

        for (Protocol proxyProtocol : Protocol.values())
        {
            for (Protocol serverProtocol : Protocol.values())
            {
                args.add(Arguments.of(proxyProtocol, serverProtocol, ProxyServlet.class));
                args.add(Arguments.of(proxyProtocol, serverProtocol, AsyncProxyServlet.class));
                args.add(Arguments.of(proxyProtocol, serverProtocol, AsyncMiddleManServlet.class));
            }
        }

        return args.stream();
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testProxyDown(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(serverProtocol, new EmptyHttpServlet());
        startProxy(proxyProtocol, proxyServletClass);
        startClient(proxyProtocol);
        // Shutdown the proxy
        proxy.stop();

        ExecutionException x = assertThrows(ExecutionException.class, () ->
        {
            client.newRequest(serverURI)
                .timeout(5, TimeUnit.SECONDS)
                .send();
        });
        assertThat(x.getCause(), instanceOf(ConnectException.class));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testProxyWithoutContent(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
            }
        });
        startProxy(proxyProtocol, proxyServletClass);
        startClient(proxyProtocol);

        ContentResponse response = client.newRequest(serverURI)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals("OK", response.getReason());
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testProxyWithResponseContent(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final byte[] content = new byte[1024];
        new Random().nextBytes(content);
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                resp.getOutputStream().write(content);
            }
        });
        startProxy(proxyProtocol, proxyServletClass);
        startClient(proxyProtocol);

        ContentResponse[] responses = new ContentResponse[10];
        for (int i = 0; i < 10; ++i)
        {
            // Request is for the target server
            responses[i] = client.newRequest(serverURI)
                .timeout(5, TimeUnit.SECONDS)
                .send();
        }

        for (int i = 0; i < 10; ++i)
        {
            assertEquals(200, responses[i].getStatus());
            assertTrue(responses[i].getHeaders().containsKey(PROXIED_HEADER));
            assertArrayEquals(content, responses[i].getContent());
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testProxyWithRequestContentAndResponseContent(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                IO.copy(req.getInputStream(), resp.getOutputStream());
            }
        });
        startProxy(proxyProtocol, proxyServletClass);
        startClient(proxyProtocol);

        byte[] content = new byte[1024];
        new Random().nextBytes(content);
        ContentResponse response = client.newRequest(serverURI)
            .method(HttpMethod.POST)
            .body(new BytesRequestContent(content))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
        assertArrayEquals(content, response.getContent());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testProxyWithBigRequestContentIgnored(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                try
                {
                    // Give some time to the proxy to
                    // upload the content to the server.
                    Thread.sleep(1000);

                    if (req.getHeader("Via") != null)
                        resp.addHeader(PROXIED_HEADER, "true");
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }
            }
        });
        startProxy(proxyProtocol, proxyServletClass);
        startClient(proxyProtocol);

        byte[] content = new byte[128 * 1024];
        ContentResponse response = client.newRequest(serverURI)
            .method(HttpMethod.POST)
            .body(new BytesRequestContent(content))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testProxyWithBigRequestContentConsumed(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final byte[] content = new byte[128 * 1024];
        new Random().nextBytes(content);
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                InputStream input = req.getInputStream();
                int index = 0;

                byte[] buffer = new byte[16 * 1024];
                while (true)
                {
                    int value = input.read(buffer);
                    if (value < 0)
                        break;
                    for (int i = 0; i < value; i++)
                    {
                        assertEquals(content[index] & 0xFF, buffer[i] & 0xFF, "Content mismatch at index=" + index);
                        ++index;
                    }
                }
            }
        });
        startProxy(proxyProtocol, proxyServletClass);
        startClient(proxyProtocol);

        ContentResponse response = client.newRequest(serverURI)
            .method(HttpMethod.POST)
            .body(new BytesRequestContent(content))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testProxyWithBigResponseContentWithSlowReader(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        // Create a 6 MiB file
        final int length = 6 * 1024;
        Path targetTestsDir = MavenTestingUtils.getTargetTestingDir().toPath();
        Files.createDirectories(targetTestsDir);
        final Path temp = Files.createTempFile(targetTestsDir, "test_", null);
        byte[] kb = new byte[1024];
        new Random().nextBytes(kb);
        try (OutputStream output = Files.newOutputStream(temp, StandardOpenOption.CREATE))
        {
            for (int i = 0; i < length; ++i)
            {
                output.write(kb);
            }
        }
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                try (InputStream input = Files.newInputStream(temp))
                {
                    IO.copy(input, response.getOutputStream());
                }
            }
        });
        startProxy(proxyProtocol, proxyServletClass);
        startClient(proxyProtocol);

        Request request = client.newRequest(serverURI).path("/proxy/test");
        final CountDownLatch latch = new CountDownLatch(1);
        request.send(new BufferingResponseListener(2 * length * 1024)
        {
            @Override
            public void onContent(Response response, ByteBuffer content)
            {
                try
                {
                    // Slow down the reader
                    TimeUnit.MILLISECONDS.sleep(5);
                    super.onContent(response, content);
                }
                catch (InterruptedException x)
                {
                    response.abort(x);
                }
            }

            @Override
            public void onComplete(Result result)
            {
                assertFalse(result.isFailed());
                assertEquals(200, result.getResponse().getStatus());
                assertEquals(length * 1024, getContent().length);
                latch.countDown();
            }
        });
        assertTrue(latch.await(30, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testProxyWithQueryString(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.getOutputStream().print(req.getQueryString());
            }
        });
        startProxy(proxyProtocol, proxyServletClass);
        startClient(proxyProtocol);

        String query = "a=1&b=%E2%82%AC";
        URI destURI = serverURI.resolve("/?" + query);
        System.out.println("destURI: " + destURI);
        ContentResponse response = client.newRequest(destURI)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertEquals(query, response.getContentAsString());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testProxyLongPoll(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final long timeout = 1000;
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void doGet(final HttpServletRequest request, final HttpServletResponse response)
            {
                if (!request.isAsyncStarted())
                {
                    final AsyncContext asyncContext = request.startAsync();
                    asyncContext.setTimeout(timeout);
                    asyncContext.addListener(new AsyncListener()
                    {
                        @Override
                        public void onComplete(AsyncEvent event)
                        {
                        }

                        @Override
                        public void onTimeout(AsyncEvent event)
                        {
                            if (request.getHeader("Via") != null)
                                response.addHeader(PROXIED_HEADER, "true");
                            asyncContext.complete();
                        }

                        @Override
                        public void onError(AsyncEvent event)
                        {
                        }

                        @Override
                        public void onStartAsync(AsyncEvent event)
                        {
                        }
                    });
                }
            }
        });
        startProxy(proxyProtocol, proxyServletClass);
        startClient(proxyProtocol);

        Response response = client.newRequest(serverURI)
            .timeout(2 * timeout, TimeUnit.MILLISECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testProxyXForwardedHostHeaderIsPresent(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                PrintWriter writer = resp.getWriter();
                writer.write(req.getHeader("X-Forwarded-Host"));
                writer.flush();
            }
        });
        startProxy(proxyProtocol, proxyServletClass);
        startClient(proxyProtocol);

        ContentResponse response = client.GET("http://localhost:" + serverConnector.getLocalPort());
        assertThat("Response expected to contain content of X-Forwarded-Host Header from the request",
            response.getContentAsString(),
            Matchers.equalTo("localhost:" + serverConnector.getLocalPort()));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testProxyWhiteList(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(serverProtocol, new EmptyHttpServlet());
        startProxy(proxyProtocol, proxyServletClass);
        startClient(proxyProtocol);
        int port = serverConnector.getLocalPort();
        proxyServlet.getWhiteListHosts().add("127.0.0.1:" + port);

        // Try with the wrong host
        ContentResponse response = client.newRequest("localhost", port)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(403, response.getStatus());

        // Try again with the right host
        response = client.newRequest("127.0.0.1", port)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testProxyBlackList(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(serverProtocol, new EmptyHttpServlet());
        startProxy(proxyProtocol, proxyServletClass);
        startClient(proxyProtocol);
        int port = serverConnector.getLocalPort();
        proxyServlet.getBlackListHosts().add("localhost:" + port);

        // Try with the wrong host
        ContentResponse response = client.newRequest("localhost", port)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(403, response.getStatus());

        // Try again with the right host
        response = client.newRequest("127.0.0.1", port)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testClientExcludedHosts(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
            }
        });
        startProxy(proxyProtocol, proxyServletClass);
        startClient(proxyProtocol);
        int port = serverConnector.getLocalPort();
        client.getProxyConfiguration().getProxies().get(0).getExcludedAddresses().add("127.0.0.1:" + port);

        // Try with a proxied host
        ContentResponse response = client.newRequest("localhost", port)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));

        // Try again with an excluded host
        response = client.newRequest("127.0.0.1", port)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertFalse(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    public static Stream<Arguments> transparentScenarios()
    {
        List<Arguments> args = new ArrayList<>();

        for (Protocol proxyProtocol : Protocol.values())
        {
            for (Protocol serverProtocol : Protocol.values())
            {
                args.add(Arguments.of(proxyProtocol, serverProtocol, ProxyServlet.Transparent.class));
                args.add(Arguments.of(proxyProtocol, serverProtocol, AsyncProxyServlet.Transparent.class));
                args.add(Arguments.of(proxyProtocol, serverProtocol, AsyncMiddleManServlet.Transparent.class));
            }
        }

        return args.stream();
    }

    @ParameterizedTest
    @MethodSource("transparentScenarios")
    public void testTransparentProxy(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        testTransparentProxyWithPrefix(proxyProtocol, serverProtocol, proxyServletClass, "/proxy");
    }

    @ParameterizedTest
    @MethodSource("transparentScenarios")
    public void testTransparentProxyWithRootContext(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        testTransparentProxyWithPrefix(proxyProtocol, serverProtocol, proxyServletClass, "/");
    }

    private void testTransparentProxyWithPrefix(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass, String prefix) throws Exception
    {
        final String target = "/test";
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                resp.setStatus(target.equals(req.getRequestURI()) ? 200 : 404);
            }
        });
        String proxyTo = "http://localhost:" + serverConnector.getLocalPort();
        Map<String, String> params = new HashMap<>();
        params.put("proxyTo", proxyTo);
        params.put("prefix", prefix);
        startProxy(proxyProtocol, proxyServletClass, params);
        startClient(proxyProtocol);

        // Make the request to the proxy, it should transparently forward to the server
        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .path(StringUtil.replace((prefix + target), "//", "/"))
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("transparentScenarios")
    public void testTransparentProxyWithQuery(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        testTransparentProxyWithQuery(proxyProtocol, serverProtocol, proxyServletClass, "/foo", "/proxy", "/test");
    }

    @ParameterizedTest
    @MethodSource("transparentScenarios")
    public void testTransparentProxyEmptyContextWithQuery(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        testTransparentProxyWithQuery(proxyProtocol, serverProtocol, proxyServletClass, "", "/proxy", "/test");
    }

    @ParameterizedTest
    @MethodSource("transparentScenarios")
    public void testTransparentProxyEmptyTargetWithQuery(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        testTransparentProxyWithQuery(proxyProtocol, serverProtocol, proxyServletClass, "/bar", "/proxy", "");
    }

    @ParameterizedTest
    @MethodSource("transparentScenarios")
    public void testTransparentProxyEmptyContextEmptyTargetWithQuery(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        testTransparentProxyWithQuery(proxyProtocol, serverProtocol, proxyServletClass, "", "/proxy", "");
    }

    private void testTransparentProxyWithQuery(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass, String proxyToContext, String prefix, String target) throws Exception
    {
        final String query = "a=1&b=2";
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");

                String expectedURI = proxyToContext + target;
                if (expectedURI.isEmpty())
                    expectedURI = "/";
                if (expectedURI.equals(req.getRequestURI()))
                {
                    if (query.equals(req.getQueryString()))
                    {
                        resp.setStatus(HttpStatus.OK_200);
                        return;
                    }
                }
                resp.setStatus(HttpStatus.NOT_FOUND_404);
            }
        });

        String proxyTo = "http://localhost:" + serverConnector.getLocalPort() + proxyToContext;
        Map<String, String> params = new HashMap<>();
        params.put("proxyTo", proxyTo);
        params.put("prefix", prefix);
        startProxy(proxyProtocol, proxyServletClass, params);
        startClient(proxyProtocol);

        // Make the request to the proxy, it should transparently forward to the server
        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .path(prefix + target + "?" + query)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertThat(response.getHeaders(), containsHeader(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("transparentScenarios")
    public void testTransparentProxyWithQueryWithSpaces(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final String target = "/test";
        final String query = "a=1&b=2&c=1234%205678&d=hello+world";
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");

                if (target.equals(req.getRequestURI()))
                {
                    if (query.equals(req.getQueryString()))
                    {
                        resp.setStatus(200);
                        return;
                    }
                }
                resp.setStatus(404);
            }
        });
        String proxyTo = "http://localhost:" + serverConnector.getLocalPort();
        String prefix = "/proxy";
        Map<String, String> params = new HashMap<>();
        params.put("proxyTo", proxyTo);
        params.put("prefix", prefix);
        startProxy(proxyProtocol, proxyServletClass, params);
        startClient(proxyProtocol);

        // Make the request to the proxy, it should transparently forward to the server
        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .path(prefix + target + "?" + query)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("transparentScenarios")
    public void testTransparentProxyWithoutPrefix(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final String target = "/test";
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                resp.setStatus(target.equals(req.getRequestURI()) ? 200 : 404);
            }
        });
        final String proxyTo = "http://localhost:" + serverConnector.getLocalPort();
        Map<String, String> initParams = new HashMap<>();
        initParams.put("proxyTo", proxyTo);
        startProxy(proxyProtocol, proxyServletClass, initParams);
        startClient(proxyProtocol);

        // Make the request to the proxy, it should transparently forward to the server
        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .path(target)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testRedirectsAreProxied(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                resp.sendRedirect("/");
            }
        });
        startProxy(proxyProtocol, proxyServletClass);
        startClient(proxyProtocol);

        client.setFollowRedirects(false);

        ContentResponse response = client.newRequest(serverURI)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(302, response.getStatus());
        assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testGZIPContentIsProxied(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final byte[] content = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");

                resp.addHeader("Content-Encoding", "gzip");
                GZIPOutputStream gzipOutputStream = new GZIPOutputStream(resp.getOutputStream());
                gzipOutputStream.write(content);
                gzipOutputStream.close();
            }
        });
        startProxy(proxyProtocol, proxyServletClass);
        startClient(proxyProtocol);

        ContentResponse response = client.newRequest(serverURI)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
        assertArrayEquals(content, response.getContent());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testWrongContentLength(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {

        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                byte[] message = "tooshort".getBytes(StandardCharsets.US_ASCII);
                resp.setContentType("text/plain;charset=ascii");
                resp.setHeader("Content-Length", Long.toString(message.length + 1));
                resp.getOutputStream().write(message);
            }
        });
        startProxy(proxyProtocol, proxyServletClass);
        startClient(proxyProtocol);

        try
        {
            ContentResponse response = client.newRequest(serverURI)
                .timeout(5, TimeUnit.SECONDS)
                .send();
            assertThat(response.getStatus(), Matchers.greaterThanOrEqualTo(500));
        }
        catch (ExecutionException e)
        {
            assertThat(e.getCause(), instanceOf(IOException.class));
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testCookiesFromDifferentClientsAreNotMixed(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final String name = "biscuit";
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");

                String value = req.getHeader(name);
                if (value != null)
                {
                    Cookie cookie = new Cookie(name, value);
                    cookie.setMaxAge(3600);
                    resp.addCookie(cookie);
                }
                else
                {
                    Cookie[] cookies = req.getCookies();
                    assertEquals(1, cookies.length);
                }
            }
        });
        startProxy(proxyProtocol, proxyServletClass);
        startClient(proxyProtocol);

        String value1 = "1";
        ContentResponse response1 = client.newRequest(serverURI)
            .header(name, value1)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response1.getStatus());
        assertTrue(response1.getHeaders().containsKey(PROXIED_HEADER));
        List<HttpCookie> cookies = client.getCookieStore().getCookies();
        assertEquals(1, cookies.size());
        assertEquals(name, cookies.get(0).getName());
        assertEquals(value1, cookies.get(0).getValue());

        HttpClient client2 = prepareClient(proxyProtocol, null);
        try
        {
            String value2 = "2";
            ContentResponse response2 = client2.newRequest(serverURI)
                .header(name, value2)
                .timeout(5, TimeUnit.SECONDS)
                .send();
            assertEquals(200, response2.getStatus());
            assertTrue(response2.getHeaders().containsKey(PROXIED_HEADER));
            cookies = client2.getCookieStore().getCookies();
            assertEquals(1, cookies.size());
            assertEquals(name, cookies.get(0).getName());
            assertEquals(value2, cookies.get(0).getValue());

            // Make a third request to be sure the proxy does not mix cookies
            ContentResponse response3 = client.newRequest(serverURI)
                .timeout(5, TimeUnit.SECONDS)
                .send();
            assertEquals(200, response3.getStatus());
            assertTrue(response3.getHeaders().containsKey(PROXIED_HEADER));
        }
        finally
        {
            client2.stop();
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testProxyRequestFailureInTheMiddleOfProxyingSmallContent(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final CountDownLatch chunk1Latch = new CountDownLatch(1);
        final int chunk1 = 'q';
        final int chunk2 = 'w';
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                ServletOutputStream output = response.getOutputStream();
                output.write(chunk1);
                response.flushBuffer();

                // Wait for the client to receive this chunk.
                await(chunk1Latch, 5000);

                // Send second chunk, must not be received by proxy.
                output.write(chunk2);
            }

            private boolean await(CountDownLatch latch, long ms) throws IOException
            {
                try
                {
                    return latch.await(ms, TimeUnit.MILLISECONDS);
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }
            }
        });
        final long proxyTimeout = 1000;
        Map<String, String> proxyParams = new HashMap<>();
        proxyParams.put("timeout", String.valueOf(proxyTimeout));
        startProxy(proxyProtocol, proxyServletClass, proxyParams);
        startClient(proxyProtocol);

        InputStreamResponseListener listener = new InputStreamResponseListener();
        int port = serverConnector.getLocalPort();
        Request request = client.newRequest("localhost", port);
        request.send(listener);

        // Make the proxy request fail; given the small content, the
        // proxy-to-client response is not committed yet so it will be reset.
        TimeUnit.MILLISECONDS.sleep(2 * proxyTimeout);

        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(504, response.getStatus());

        // Make sure there is error page content, as the proxy-to-client response has been reset.
        InputStream input = listener.getInputStream();
        String body = IO.toString(input);
        assertThat(body, Matchers.containsString("HTTP ERROR 504"));
        chunk1Latch.countDown();

        // Result succeeds because a 504 is a valid HTTP response.
        Result result = listener.await(5, TimeUnit.SECONDS);
        assertTrue(result.isSucceeded());

        // Make sure the proxy does not receive chunk2.
        assertEquals(-1, input.read());

        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        ConnectionPool connectionPool = destination.getConnectionPool();
        assertTrue(connectionPool.isEmpty());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testProxyRequestFailureInTheMiddleOfProxyingBigContent(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        int outputBufferSize = 1024;
        CountDownLatch chunk1Latch = new CountDownLatch(1);
        byte[] chunk1 = new byte[outputBufferSize];
        new Random().nextBytes(chunk1);
        int chunk2 = 'w';
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                ServletOutputStream output = response.getOutputStream();
                output.write(chunk1);
                response.flushBuffer();

                // Wait for the client to receive this chunk.
                await(chunk1Latch, 5000);

                // Send second chunk, must not be received by proxy.
                output.write(chunk2);
            }

            private boolean await(CountDownLatch latch, long ms) throws IOException
            {
                try
                {
                    return latch.await(ms, TimeUnit.MILLISECONDS);
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }
            }
        });
        final long proxyTimeout = 1000;
        Map<String, String> proxyParams = new HashMap<>();
        proxyParams.put("timeout", String.valueOf(proxyTimeout));
        proxyParams.put("outputBufferSize", String.valueOf(outputBufferSize));
        startProxy(proxyProtocol, proxyServletClass, proxyParams);
        startClient(proxyProtocol);

        InputStreamResponseListener listener = new InputStreamResponseListener();
        int port = serverConnector.getLocalPort();
        Request request = client.newRequest("localhost", port);
        request.send(listener);

        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        for (byte b : chunk1)
        {
            assertEquals(b & 0xFF, input.read());
        }

        TimeUnit.MILLISECONDS.sleep(2 * proxyTimeout);

        chunk1Latch.countDown();

        assertThrows(EOFException.class, () ->
        {
            // Make sure the proxy does not receive chunk2.
            input.read();
        });

        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        ConnectionPool connectionPool = destination.getConnectionPool();
        assertTrue(connectionPool.isEmpty());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResponseHeadersAreNotRemoved(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(serverProtocol, new EmptyHttpServlet());
        startProxy(proxyProtocol, proxyServletClass);
        proxyContext.stop();
        final String headerName = "X-Test";
        final String headerValue = "test-value";
        proxyContext.addFilter(new FilterHolder(new Filter()
        {
            @Override
            public void init(FilterConfig filterConfig)
            {
            }

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
            {
                ((HttpServletResponse)response).addHeader(headerName, headerValue);
                chain.doFilter(request, response);
            }

            @Override
            public void destroy()
            {
            }
        }), "/*", EnumSet.of(DispatcherType.REQUEST));
        proxyContext.start();
        startClient(proxyProtocol);

        ContentResponse response = client.newRequest(serverURI)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertEquals(headerValue, response.getHeaders().get(headerName));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testHeadersListedByConnectionHeaderAreRemoved(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final Map<String, String> hopHeaders = new LinkedHashMap<>();
        hopHeaders.put(HttpHeader.TE.asString(), "gzip");
        hopHeaders.put(HttpHeader.CONNECTION.asString(), "Keep-Alive, Foo, Bar");
        hopHeaders.put("Foo", "abc");
        hopHeaders.put("Foo", "def");
        hopHeaders.put(HttpHeader.KEEP_ALIVE.asString(), "timeout=30");
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                List<String> names = Collections.list(request.getHeaderNames());
                for (String name : names)
                {
                    if (hopHeaders.containsKey(name))
                        throw new IOException("Hop header must not be proxied: " + name);
                }
            }
        });
        startProxy(proxyProtocol, proxyServletClass);
        startClient(proxyProtocol);

        Request request = client.newRequest(serverURI);
        for (Map.Entry<String, String> entry : hopHeaders.entrySet())
        {
            request.header(entry.getKey(), entry.getValue());
        }
        ContentResponse response = request
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testExpect100ContinueRespond100Continue(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        CountDownLatch serverLatch1 = new CountDownLatch(1);
        CountDownLatch serverLatch2 = new CountDownLatch(1);
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                serverLatch1.countDown();

                try
                {
                    serverLatch2.await(5, TimeUnit.SECONDS);
                }
                catch (Throwable x)
                {
                    throw new InterruptedIOException();
                }

                // Send the 100 Continue.
                ServletInputStream input = request.getInputStream();

                // Echo the content.
                IO.copy(input, response.getOutputStream());
            }
        });
        startProxy(proxyProtocol, proxyServletClass);
        startClient(proxyProtocol);

        byte[] content = new byte[1024];
        CountDownLatch contentLatch = new CountDownLatch(1);
        CountDownLatch clientLatch = new CountDownLatch(1);
        client.newRequest(serverURI)
            .header(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString())
            .body(new BytesRequestContent(content))
            .onRequestContent((request, buffer) -> contentLatch.countDown())
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    if (result.isSucceeded())
                    {
                        if (result.getResponse().getStatus() == HttpStatus.OK_200)
                        {
                            if (Arrays.equals(content, getContent()))
                                clientLatch.countDown();
                        }
                    }
                }
            });

        // Wait until we arrive on the server.
        assertTrue(serverLatch1.await(5, TimeUnit.SECONDS));
        // The client should not send the content yet.
        assertFalse(contentLatch.await(1, TimeUnit.SECONDS));

        // Make the server send the 100 Continue.
        serverLatch2.countDown();

        // The client has sent the content.
        assertTrue(contentLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testExpect100ContinueRespond100ContinueDelayedRequestContent(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Send the 100 Continue.
                ServletInputStream input = request.getInputStream();
                // Echo the content.
                IO.copy(input, response.getOutputStream());
            }
        });
        startProxy(proxyProtocol, proxyServletClass);
        startClient(proxyProtocol);

        byte[] content = new byte[1024];
        new Random().nextBytes(content);
        int chunk1 = content.length / 2;
        AsyncRequestContent requestContent = new AsyncRequestContent();
        requestContent.offer(ByteBuffer.wrap(content, 0, chunk1));
        CountDownLatch clientLatch = new CountDownLatch(1);
        client.newRequest(serverURI)
            .header(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString())
            .body(requestContent)
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    if (result.isSucceeded())
                    {
                        if (result.getResponse().getStatus() == HttpStatus.OK_200)
                        {
                            if (Arrays.equals(content, getContent()))
                                clientLatch.countDown();
                        }
                    }
                }
            });

        // Wait a while and then offer more content.
        Thread.sleep(1000);
        requestContent.offer(ByteBuffer.wrap(content, chunk1, content.length - chunk1));
        requestContent.close();

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testExpect100ContinueRespond100ContinueSomeRequestContentThenFailure(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Send the 100 Continue.
                ServletInputStream input = request.getInputStream();
                try
                {
                    // Echo the content.
                    IO.copy(input, response.getOutputStream());
                }
                catch (IOException x)
                {
                    serverLatch.countDown();
                }
            }
        });
        startProxy(proxyProtocol, proxyServletClass);
        long idleTimeout = 1000;
        startClient(proxyProtocol, httpClient -> httpClient.setIdleTimeout(idleTimeout));

        byte[] content = new byte[1024];
        new Random().nextBytes(content);
        int chunk1 = content.length / 2;
        AsyncRequestContent requestContent = new AsyncRequestContent();
        requestContent.offer(ByteBuffer.wrap(content, 0, chunk1));
        CountDownLatch clientLatch = new CountDownLatch(1);
        client.newRequest(serverURI)
            .header(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString())
            .body(requestContent)
            .send(result ->
            {
                if (result.isFailed())
                    clientLatch.countDown();
            });

        // Wait more than the idle timeout to break the connection.
        Thread.sleep(2 * idleTimeout);

        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testExpect100ContinueRespond417ExpectationFailed(Protocol proxyProtocol, Protocol serverProtocol, Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        CountDownLatch serverLatch1 = new CountDownLatch(1);
        CountDownLatch serverLatch2 = new CountDownLatch(1);
        startServer(serverProtocol, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                serverLatch1.countDown();

                try
                {
                    serverLatch2.await(5, TimeUnit.SECONDS);
                }
                catch (Throwable x)
                {
                    throw new InterruptedIOException();
                }

                // Send the 417 Expectation Failed.
                response.setStatus(HttpStatus.EXPECTATION_FAILED_417);
            }
        });
        startProxy(proxyProtocol, proxyServletClass);
        startClient(proxyProtocol);

        byte[] content = new byte[1024];
        CountDownLatch contentLatch = new CountDownLatch(1);
        CountDownLatch clientLatch = new CountDownLatch(1);
        client.newRequest(serverURI)
            .header(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString())
            .body(new BytesRequestContent(content))
            .onRequestContent((request, buffer) -> contentLatch.countDown())
            .send(result ->
            {
                if (result.isFailed())
                {
                    if (result.getResponse().getStatus() == HttpStatus.EXPECTATION_FAILED_417)
                        clientLatch.countDown();
                }
            });

        // Wait until we arrive on the server.
        assertTrue(serverLatch1.await(5, TimeUnit.SECONDS));
        // The client should not send the content yet.
        assertFalse(contentLatch.await(1, TimeUnit.SECONDS));

        // Make the server send the 417 Expectation Failed.
        serverLatch2.countDown();

        // The client should not send the content.
        assertFalse(contentLatch.await(1, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    public static class EmptyHttpServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response)
        {
        }
    }
}
