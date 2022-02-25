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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.HttpCookie;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.eclipse.jetty.http.tools.matchers.HttpFieldsMatchers.containsHeader;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProxyServletTest
{
    private static final String PROXIED_HEADER = "X-Proxied";

    public static Stream<Arguments> impls()
    {
        return Stream.of(
            ProxyServlet.class,
            AsyncProxyServlet.class,
            AsyncMiddleManServlet.class
        ).map(Arguments::of);
    }

    private HttpClient client;
    private Server proxy;
    private ServerConnector proxyConnector;
    private ServletContextHandler proxyContext;
    private AbstractProxyServlet proxyServlet;
    private Server server;
    private ServerConnector serverConnector;
    private ServerConnector tlsServerConnector;

    private void startServer(HttpServlet servlet) throws Exception
    {
        QueuedThreadPool serverPool = new QueuedThreadPool();
        serverPool.setName("server");
        server = new Server(serverPool);
        serverConnector = new ServerConnector(server);
        server.addConnector(serverConnector);

        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        String keyStorePath = MavenTestingUtils.getTestResourceFile("server_keystore.p12").getAbsolutePath();
        sslContextFactory.setKeyStorePath(keyStorePath);
        sslContextFactory.setKeyStorePassword("storepwd");
        tlsServerConnector = new ServerConnector(server, new SslConnectionFactory(
            sslContextFactory,
            HttpVersion.HTTP_1_1.asString()),
            new HttpConnectionFactory());
        server.addConnector(tlsServerConnector);

        ServletContextHandler appCtx = new ServletContextHandler(server, "/", true, false);
        ServletHolder appServletHolder = new ServletHolder(servlet);
        appCtx.addServlet(appServletHolder, "/*");

        server.start();
    }

    private void startProxy(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startProxy(proxyServletClass, new HashMap<>());
    }

    private void startProxy(Class<? extends ProxyServlet> proxyServletClass, Map<String, String> initParams) throws Exception
    {
        startProxy(proxyServletClass.getConstructor().newInstance(), initParams);
    }

    private void startProxy(AbstractProxyServlet proxyServlet, Map<String, String> initParams) throws Exception
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
        proxyConnector = new ServerConnector(proxy, new HttpConnectionFactory(configuration));
        proxy.addConnector(proxyConnector);

        proxyContext = new ServletContextHandler(proxy, "/", true, false);
        this.proxyServlet = proxyServlet;
        ServletHolder proxyServletHolder = new ServletHolder(proxyServlet);
        proxyServletHolder.setInitParameters(initParams);
        proxyContext.addServlet(proxyServletHolder, "/*");

        proxy.start();
    }

    private void startClient() throws Exception
    {
        startClient(null);
    }

    private void startClient(Consumer<HttpClient> consumer) throws Exception
    {
        client = prepareClient(consumer);
    }

    private HttpClient prepareClient(Consumer<HttpClient> consumer) throws Exception
    {
        QueuedThreadPool clientPool = new QueuedThreadPool();
        clientPool.setName("client");
        HttpClient result = new HttpClient();
        result.setExecutor(clientPool);
        result.getProxyConfiguration().getProxies().add(new HttpProxy("localhost", proxyConnector.getLocalPort()));
        if (consumer != null)
            consumer.accept(result);
        result.start();
        return result;
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (client != null)
            client.stop();
        if (proxy != null)
            proxy.stop();
        if (server != null)
            server.stop();
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyDown(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new EmptyHttpServlet());
        startProxy(proxyServletClass);
        startClient();
        // Shutdown the proxy
        proxy.stop();

        ExecutionException x = assertThrows(ExecutionException.class, () ->
        {
            client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();
        });
        assertThat(x.getCause(), instanceOf(ConnectException.class));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyWithoutContent(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
            }
        });
        startProxy(proxyServletClass);
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals("OK", response.getReason());
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyWithResponseContent(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final byte[] content = new byte[1024];
        new Random().nextBytes(content);
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                resp.getOutputStream().write(content);
            }
        });
        startProxy(proxyServletClass);
        startClient();

        ContentResponse[] responses = new ContentResponse[10];
        for (int i = 0; i < 10; ++i)
        {
            // Request is for the target server
            responses[i] = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();
        }

        for (int i = 0; i < 10; ++i)
        {
            assertEquals(200, responses[i].getStatus());
            assertTrue(responses[i].getHeaders().contains(PROXIED_HEADER));
            assertArrayEquals(content, responses[i].getContent());
        }
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyWithRequestContentAndResponseContent(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                IO.copy(req.getInputStream(), resp.getOutputStream());
            }
        });
        startProxy(proxyServletClass);
        startClient();

        byte[] content = new byte[1024];
        new Random().nextBytes(content);
        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
            .method(HttpMethod.POST)
            .body(new BytesRequestContent(content))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));
        assertArrayEquals(content, response.getContent());
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyWithBigRequestContentIgnored(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new HttpServlet()
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
        startProxy(proxyServletClass);
        startClient();

        byte[] content = new byte[128 * 1024];
        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
            .method(HttpMethod.POST)
            .body(new BytesRequestContent(content))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyWithBigRequestContentConsumed(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final byte[] content = new byte[128 * 1024];
        new Random().nextBytes(content);
        startServer(new HttpServlet()
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
        startProxy(proxyServletClass);
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
            .method(HttpMethod.POST)
            .body(new BytesRequestContent(content))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyWithBigResponseContentWithSlowReader(Class<? extends ProxyServlet> proxyServletClass) throws Exception
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
        startServer(new HttpServlet()
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
        startProxy(proxyServletClass);
        startClient();

        Request request = client.newRequest("localhost", serverConnector.getLocalPort()).path("/proxy/test");
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
    @MethodSource("impls")
    public void testProxyWithQueryString(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.getOutputStream().print(req.getQueryString());
            }
        });
        startProxy(proxyServletClass);
        startClient();

        String query = "a=1&b=%E2%82%AC";
        ContentResponse response = client.newRequest("http://localhost:" + serverConnector.getLocalPort() + "/?" + query)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertEquals(query, response.getContentAsString());
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyLongPoll(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final long timeout = 1000;
        startServer(new HttpServlet()
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
        startProxy(proxyServletClass);
        startClient();

        Response response = client.newRequest("localhost", serverConnector.getLocalPort())
            .timeout(2 * timeout, TimeUnit.MILLISECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyXForwardedHostHeaderIsPresent(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                PrintWriter writer = resp.getWriter();
                writer.write(req.getHeader("X-Forwarded-Host"));
                writer.flush();
            }
        });
        startProxy(proxyServletClass);
        startClient();

        ContentResponse response = client.GET("http://localhost:" + serverConnector.getLocalPort());
        assertThat("Response expected to contain content of X-Forwarded-Host Header from the request",
            response.getContentAsString(),
            equalTo("localhost:" + serverConnector.getLocalPort()));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyViaHeaderIsAdded(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new EmptyHttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                PrintWriter writer = response.getWriter();
                List<String> viaValues = Collections.list(request.getHeaders("Via"));
                writer.write(String.join(", ", viaValues));
            }
        });
        String viaHost = "my-good-via-host.example.org";
        startProxy(proxyServletClass, Collections.singletonMap("viaHost", viaHost));
        startClient();

        ContentResponse response = client.GET("http://localhost:" + serverConnector.getLocalPort());
        assertThat(response.getContentAsString(), equalTo("1.1 " + viaHost));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyViaHeaderValueIsAppended(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new EmptyHttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Make sure the proxy coalesced the Via headers into just one.
                org.eclipse.jetty.server.Request jettyRequest = (org.eclipse.jetty.server.Request)request;
                assertEquals(1, jettyRequest.getHttpFields().getFields(HttpHeader.VIA).size());
                PrintWriter writer = response.getWriter();
                List<String> viaValues = Collections.list(request.getHeaders("Via"));
                writer.write(String.join(", ", viaValues));
            }
        });
        String viaHost = "beatrix";
        startProxy(proxyServletClass, Collections.singletonMap("viaHost", viaHost));
        startClient();

        String existingViaHeader = "1.0 charon";
        ContentResponse response = client.newRequest("http://localhost:" + serverConnector.getLocalPort())
            .header(HttpHeader.VIA, existingViaHeader)
            .send();
        String expected = String.join(", ", existingViaHeader, "1.1 " + viaHost);
        assertThat(response.getContentAsString(), equalTo(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP/2.0", "FCGI/1.0"})
    public void testViaHeaderProtocols(String protocol) throws Exception
    {
        startServer(new EmptyHttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                PrintWriter writer = response.getWriter();
                List<String> viaValues = Collections.list(request.getHeaders("Via"));
                writer.write(String.join(", ", viaValues));
            }
        });
        String viaHost = "proxy";
        startProxy(new ProxyServlet()
        {
            @Override
            protected void addViaHeader(HttpServletRequest clientRequest, Request proxyRequest)
            {
                HttpServletRequest wrapped = new HttpServletRequestWrapper(clientRequest)
                {
                    @Override
                    public String getProtocol()
                    {
                        return protocol;
                    }
                };
                super.addViaHeader(wrapped, proxyRequest);
            }
        }, Collections.singletonMap("viaHost", viaHost));
        startClient();

        ContentResponse response = client.GET("http://localhost:" + serverConnector.getLocalPort());

        String expectedProtocol = protocol.startsWith("HTTP/") ? protocol.substring("HTTP/".length()) : protocol;
        String expected = expectedProtocol + " " + viaHost;
        assertThat(response.getContentAsString(), equalTo(expected));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyWhiteList(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new EmptyHttpServlet());
        startProxy(proxyServletClass);
        startClient();
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
    @MethodSource("impls")
    public void testProxyBlackList(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new EmptyHttpServlet());
        startProxy(proxyServletClass);
        startClient();
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
    @MethodSource("impls")
    public void testClientExcludedHosts(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
            }
        });
        startProxy(proxyServletClass);
        startClient();
        int port = serverConnector.getLocalPort();
        client.getProxyConfiguration().getProxies().get(0).getExcludedAddresses().add("127.0.0.1:" + port);

        // Try with a proxied host
        ContentResponse response = client.newRequest("localhost", port)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));

        // Try again with an excluded host
        response = client.newRequest("127.0.0.1", port)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertFalse(response.getHeaders().contains(PROXIED_HEADER));
    }

    public static Stream<Arguments> transparentImpls()
    {
        return Stream.of(
            new ProxyServlet.Transparent()
            {
                @Override
                protected HttpClient newHttpClient()
                {
                    return newTrustAllClient(super.newHttpClient());
                }

                @Override
                public String toString()
                {
                    return ProxyServlet.Transparent.class.getName();
                }
            },
            new AsyncProxyServlet.Transparent()
            {
                @Override
                protected HttpClient newHttpClient()
                {
                    return newTrustAllClient(super.newHttpClient());
                }

                @Override
                public String toString()
                {
                    return AsyncProxyServlet.Transparent.class.getName();
                }
            },
            new AsyncMiddleManServlet.Transparent()
            {
                @Override
                protected HttpClient newHttpClient()
                {
                    return newTrustAllClient(super.newHttpClient());
                }

                @Override
                public String toString()
                {
                    return AsyncMiddleManServlet.Transparent.class.getName();
                }
            }
        ).map(Arguments::of);
    }

    private static HttpClient newTrustAllClient(HttpClient client)
    {
        SslContextFactory.Client sslContextFactory = client.getSslContextFactory();
        sslContextFactory.setTrustAll(true);
        return client;
    }

    @ParameterizedTest
    @MethodSource("transparentImpls")
    public void testTransparentProxy(AbstractProxyServlet proxyServletClass) throws Exception
    {
        testTransparentProxyWithPrefix(proxyServletClass, "http", "/proxy");
    }

    @ParameterizedTest
    @MethodSource("transparentImpls")
    public void testTransparentProxyTls(AbstractProxyServlet proxyServletClass) throws Exception
    {
        testTransparentProxyWithPrefix(proxyServletClass, "https", "/proxy");
    }

    @ParameterizedTest
    @MethodSource("transparentImpls")
    public void testTransparentProxyWithRootContext(AbstractProxyServlet proxyServletClass) throws Exception
    {
        testTransparentProxyWithPrefix(proxyServletClass, "http", "/");
    }

    private void testTransparentProxyWithPrefix(AbstractProxyServlet proxyServletClass, String scheme, String prefix) throws Exception
    {
        final String target = "/test";
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                resp.setStatus(target.equals(req.getRequestURI()) ? 200 : 404);
            }
        });
        int serverPort = serverConnector.getLocalPort();
        if (HttpScheme.HTTPS.is(scheme))
            serverPort = tlsServerConnector.getLocalPort();
        String proxyTo = scheme + "://localhost:" + serverPort;
        Map<String, String> params = new HashMap<>();
        params.put("proxyTo", proxyTo);
        params.put("prefix", prefix);
        startProxy(proxyServletClass, params);
        startClient();

        // Make the request to the proxy, it should transparently forward to the server
        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .path(StringUtil.replace((prefix + target), "//", "/"))
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("transparentImpls")
    public void testTransparentProxyWithQuery(AbstractProxyServlet proxyServletClass) throws Exception
    {
        testTransparentProxyWithQuery(proxyServletClass, "/foo", "/proxy", "/test");
    }

    @ParameterizedTest
    @MethodSource("transparentImpls")
    public void testTransparentProxyEmptyContextWithQuery(AbstractProxyServlet proxyServletClass) throws Exception
    {
        testTransparentProxyWithQuery(proxyServletClass, "", "/proxy", "/test");
    }

    @ParameterizedTest
    @MethodSource("transparentImpls")
    public void testTransparentProxyEmptyTargetWithQuery(AbstractProxyServlet proxyServletClass) throws Exception
    {
        testTransparentProxyWithQuery(proxyServletClass, "/bar", "/proxy", "");
    }

    @ParameterizedTest
    @MethodSource("transparentImpls")
    public void testTransparentProxyEmptyContextEmptyTargetWithQuery(AbstractProxyServlet proxyServletClass) throws Exception
    {
        testTransparentProxyWithQuery(proxyServletClass, "", "/proxy", "");
    }

    private void testTransparentProxyWithQuery(AbstractProxyServlet proxyServletClass, String proxyToContext, String prefix, String target) throws Exception
    {
        final String query = "a=1&b=2";
        startServer(new HttpServlet()
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
        startProxy(proxyServletClass, params);
        startClient();

        // Make the request to the proxy, it should transparently forward to the server
        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .path(prefix + target + "?" + query)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertThat(response.getHeaders(), containsHeader(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("transparentImpls")
    public void testTransparentProxyWithQueryWithSpaces(AbstractProxyServlet proxyServletClass) throws Exception
    {
        final String target = "/test";
        final String query = "a=1&b=2&c=1234%205678&d=hello+world";
        startServer(new HttpServlet()
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
        startProxy(proxyServletClass, params);
        startClient();

        // Make the request to the proxy, it should transparently forward to the server
        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .path(prefix + target + "?" + query)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("transparentImpls")
    public void testTransparentProxyWithoutPrefix(AbstractProxyServlet proxyServletClass) throws Exception
    {
        final String target = "/test";
        startServer(new HttpServlet()
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
        startProxy(proxyServletClass, initParams);
        startClient();

        // Make the request to the proxy, it should transparently forward to the server
        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .path(target)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));
    }

    /**
     * Only tests overridden ProxyServlet behavior, see CachingProxyServlet
     */
    @Test
    public void testCachingProxy() throws Exception
    {
        final byte[] content = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0xA, 0xB, 0xC, 0xD, 0xE, 0xF};
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                resp.getOutputStream().write(content);
            }
        });

        startProxy(CachingProxyServlet.class);
        startClient();

        // First request
        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertThat(response.getHeaders(), containsHeader(PROXIED_HEADER));
        assertArrayEquals(content, response.getContent());

        // Second request should be cached
        response = client.newRequest("localhost", serverConnector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertThat(response.getHeaders(), containsHeader(CachingProxyServlet.CACHE_HEADER));
        assertArrayEquals(content, response.getContent());
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testRedirectsAreProxied(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                resp.sendRedirect("/");
            }
        });
        startProxy(proxyServletClass);
        startClient();

        client.setFollowRedirects(false);

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(302, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testGZIPContentIsProxied(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final byte[] content = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        startServer(new HttpServlet()
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
        startProxy(proxyServletClass);
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));
        assertArrayEquals(content, response.getContent());
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testWrongContentLength(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {

        startServer(new HttpServlet()
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
        startProxy(proxyServletClass);
        startClient();

        try
        {
            ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();
            assertThat(response.getStatus(), greaterThanOrEqualTo(500));
        }
        catch (ExecutionException e)
        {
            assertThat(e.getCause(), instanceOf(IOException.class));
        }
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testCookiesFromDifferentClientsAreNotMixed(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final String name = "biscuit";
        startServer(new HttpServlet()
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
        startProxy(proxyServletClass);
        startClient();

        String value1 = "1";
        ContentResponse response1 = client.newRequest("localhost", serverConnector.getLocalPort())
            .headers(headers -> headers.put(name, value1))
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response1.getStatus());
        assertTrue(response1.getHeaders().contains(PROXIED_HEADER));
        List<HttpCookie> cookies = client.getCookieStore().getCookies();
        assertEquals(1, cookies.size());
        assertEquals(name, cookies.get(0).getName());
        assertEquals(value1, cookies.get(0).getValue());

        HttpClient client2 = prepareClient(null);
        try
        {
            String value2 = "2";
            ContentResponse response2 = client2.newRequest("localhost", serverConnector.getLocalPort())
                .headers(headers -> headers.put(name, value2))
                .timeout(5, TimeUnit.SECONDS)
                .send();
            assertEquals(200, response2.getStatus());
            assertTrue(response2.getHeaders().contains(PROXIED_HEADER));
            cookies = client2.getCookieStore().getCookies();
            assertEquals(1, cookies.size());
            assertEquals(name, cookies.get(0).getName());
            assertEquals(value2, cookies.get(0).getValue());

            // Make a third request to be sure the proxy does not mix cookies
            ContentResponse response3 = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();
            assertEquals(200, response3.getStatus());
            assertTrue(response3.getHeaders().contains(PROXIED_HEADER));
        }
        finally
        {
            client2.stop();
        }
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyRequestFailureInTheMiddleOfProxyingSmallContent(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final CountDownLatch chunk1Latch = new CountDownLatch(1);
        final int chunk1 = 'q';
        final int chunk2 = 'w';
        startServer(new HttpServlet()
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
        startProxy(proxyServletClass, proxyParams);
        startClient();

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
        assertThat(body, containsString("HTTP ERROR 504"));
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
    @MethodSource("impls")
    public void testProxyRequestFailureInTheMiddleOfProxyingBigContent(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        int outputBufferSize = 1024;
        CountDownLatch chunk1Latch = new CountDownLatch(1);
        byte[] chunk1 = new byte[outputBufferSize];
        new Random().nextBytes(chunk1);
        int chunk2 = 'w';
        startServer(new HttpServlet()
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
        startProxy(proxyServletClass, proxyParams);
        startClient();

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
    @MethodSource("impls")
    public void testResponseHeadersAreNotRemoved(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new EmptyHttpServlet());
        startProxy(proxyServletClass);
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
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertEquals(headerValue, response.getHeaders().get(headerName));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testHeadersListedByConnectionHeaderAreRemoved(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final Map<String, String> hopHeaders = new LinkedHashMap<>();
        hopHeaders.put(HttpHeader.TE.asString(), "gzip");
        hopHeaders.put(HttpHeader.CONNECTION.asString(), "Keep-Alive, Foo, Bar");
        hopHeaders.put("Foo", "abc");
        hopHeaders.put("Foo", "def");
        hopHeaders.put(HttpHeader.KEEP_ALIVE.asString(), "timeout=30");
        startServer(new HttpServlet()
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
        startProxy(proxyServletClass);
        startClient();

        Request request = client.newRequest("localhost", serverConnector.getLocalPort());
        hopHeaders.forEach((key, value) -> request.headers(headers -> headers.add(key, value)));
        ContentResponse response = request
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testExpect100ContinueRespond100Continue(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        CountDownLatch serverLatch1 = new CountDownLatch(1);
        CountDownLatch serverLatch2 = new CountDownLatch(1);
        startServer(new HttpServlet()
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
        startProxy(proxyServletClass);
        startClient();

        byte[] content = new byte[1024];
        CountDownLatch contentLatch = new CountDownLatch(1);
        CountDownLatch clientLatch = new CountDownLatch(1);
        client.newRequest("localhost", serverConnector.getLocalPort())
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString()))
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
    @MethodSource("impls")
    public void testExpect100ContinueRespond100ContinueDelayedRequestContent(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new HttpServlet()
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
        startProxy(proxyServletClass);
        startClient();

        byte[] content = new byte[1024];
        new Random().nextBytes(content);
        int chunk1 = content.length / 2;
        AsyncRequestContent requestContent = new AsyncRequestContent();
        requestContent.offer(ByteBuffer.wrap(content, 0, chunk1));
        CountDownLatch clientLatch = new CountDownLatch(1);
        client.newRequest("localhost", serverConnector.getLocalPort())
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString()))
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
    @MethodSource("impls")
    public void testExpect100ContinueRespond100ContinueSomeRequestContentThenFailure(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        startServer(new HttpServlet()
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
        startProxy(proxyServletClass);
        long idleTimeout = 1000;
        startClient(httpClient -> httpClient.setIdleTimeout(idleTimeout));

        byte[] content = new byte[1024];
        new Random().nextBytes(content);
        int chunk1 = content.length / 2;
        AsyncRequestContent requestContent = new AsyncRequestContent();
        requestContent.offer(ByteBuffer.wrap(content, 0, chunk1));
        CountDownLatch clientLatch = new CountDownLatch(1);
        client.newRequest("localhost", serverConnector.getLocalPort())
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString()))
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
    @MethodSource("impls")
    public void testExpect100ContinueRespond417ExpectationFailed(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        CountDownLatch serverLatch1 = new CountDownLatch(1);
        CountDownLatch serverLatch2 = new CountDownLatch(1);
        startServer(new HttpServlet()
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
        startProxy(proxyServletClass);
        startClient();

        byte[] content = new byte[1024];
        CountDownLatch contentLatch = new CountDownLatch(1);
        CountDownLatch clientLatch = new CountDownLatch(1);
        client.newRequest("localhost", serverConnector.getLocalPort())
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString()))
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
}
