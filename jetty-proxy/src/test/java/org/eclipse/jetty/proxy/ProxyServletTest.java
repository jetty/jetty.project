//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.proxy;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.HttpCookie;
import java.nio.ByteBuffer;
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
import java.util.concurrent.TimeoutException;
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

import org.eclipse.jetty.client.DuplexConnectionPool;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpContentResponse;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.http.HttpDestinationOverHTTP;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ProxyServletTest
{
    private static final String PROXIED_HEADER = "X-Proxied";

    @Parameterized.Parameters(name = "{0}")
    public static Iterable<Object[]> data()
    {
        return Arrays.asList(new Object[][]{
                {ProxyServlet.class},
                {AsyncProxyServlet.class},
                {AsyncMiddleManServlet.class}
        });
    }

    @Rule
    public final TestTracker tracker = new TestTracker();
    private HttpClient client;
    private Server proxy;
    private ServerConnector proxyConnector;
    private ServletContextHandler proxyContext;
    private AbstractProxyServlet proxyServlet;
    private Server server;
    private ServerConnector serverConnector;

    public ProxyServletTest(Class<?> proxyServletClass) throws Exception
    {
        this.proxyServlet = (AbstractProxyServlet)proxyServletClass.newInstance();
    }

    private void startServer(HttpServlet servlet) throws Exception
    {
        QueuedThreadPool serverPool = new QueuedThreadPool();
        serverPool.setName("server");
        server = new Server(serverPool);
        serverConnector = new ServerConnector(server);
        server.addConnector(serverConnector);

        ServletContextHandler appCtx = new ServletContextHandler(server, "/", true, false);
        ServletHolder appServletHolder = new ServletHolder(servlet);
        appCtx.addServlet(appServletHolder, "/*");

        server.start();
    }

    private void startProxy() throws Exception
    {
        startProxy(new HashMap<>());
    }

    private void startProxy(Map<String, String> initParams) throws Exception
    {
        QueuedThreadPool proxyPool = new QueuedThreadPool();
        proxyPool.setName("proxy");
        proxy = new Server(proxyPool);

        HttpConfiguration configuration = new HttpConfiguration();
        configuration.setSendDateHeader(false);
        configuration.setSendServerVersion(false);
        String value = initParams.get("outputBufferSize");
        if (value != null)
            configuration.setOutputBufferSize(Integer.valueOf(value));
        proxyConnector = new ServerConnector(proxy, new HttpConnectionFactory(configuration));
        proxy.addConnector(proxyConnector);

        proxyContext = new ServletContextHandler(proxy, "/", true, false);
        ServletHolder proxyServletHolder = new ServletHolder(proxyServlet);
        proxyServletHolder.setInitParameters(initParams);
        proxyContext.addServlet(proxyServletHolder, "/*");

        proxy.start();
    }

    private void startClient() throws Exception
    {
        client = prepareClient();
    }

    private HttpClient prepareClient() throws Exception
    {
        QueuedThreadPool clientPool = new QueuedThreadPool();
        clientPool.setName("client");
        HttpClient result = new HttpClient();
        result.setExecutor(clientPool);
        result.getProxyConfiguration().getProxies().add(new HttpProxy("localhost", proxyConnector.getLocalPort()));
        result.start();
        return result;
    }

    @After
    public void dispose() throws Exception
    {
        if (client != null)
            client.stop();
        if (proxy != null)
            proxy.stop();
        if (server != null)
            server.stop();
    }

    @Test
    public void testProxyDown() throws Exception
    {
        startServer(new EmptyHttpServlet());
        startProxy();
        startClient();
        // Shutdown the proxy
        proxy.stop();

        try
        {
            client.newRequest("localhost", serverConnector.getLocalPort())
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            Assert.assertThat(x.getCause(), Matchers.instanceOf(ConnectException.class));
        }
    }

    @Test
    public void testProxyWithoutContent() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
            }
        });
        startProxy();
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals("OK", response.getReason());
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @Test
    public void testProxyWithResponseContent() throws Exception
    {
        final byte[] content = new byte[1024];
        new Random().nextBytes(content);
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                resp.getOutputStream().write(content);
            }
        });
        startProxy();
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
            Assert.assertEquals(200, responses[i].getStatus());
            Assert.assertTrue(responses[i].getHeaders().containsKey(PROXIED_HEADER));
            Assert.assertArrayEquals(content, responses[i].getContent());
        }
    }

    @Test
    public void testProxyWithRequestContentAndResponseContent() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                IO.copy(req.getInputStream(), resp.getOutputStream());
            }
        });
        startProxy();
        startClient();

        byte[] content = new byte[1024];
        new Random().nextBytes(content);
        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .method(HttpMethod.POST)
                .content(new BytesContentProvider(content))
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
        Assert.assertArrayEquals(content, response.getContent());
    }

    @Test
    public void testProxyWithBigRequestContentIgnored() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
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
        startProxy();
        startClient();

        byte[] content = new byte[128 * 1024];
        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .method(HttpMethod.POST)
                .content(new BytesContentProvider(content))
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @Test
    public void testProxyWithBigRequestContentConsumed() throws Exception
    {
        final byte[] content = new byte[128 * 1024];
        new Random().nextBytes(content);
        startServer(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                InputStream input = req.getInputStream();
                int index = 0;
                
                byte[] buffer = new byte[16*1024];
                while (true)
                {
                    int value = input.read(buffer);
                    if (value < 0)
                        break;
                    for (int i=0;i<value;i++)
                    {
                        Assert.assertEquals("Content mismatch at index=" + index, content[index] & 0xFF, buffer[i] & 0xFF);
                        ++index;
                    }
                }
            }
        });
        startProxy();
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .method(HttpMethod.POST)
                .content(new BytesContentProvider(content))
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @Test
    public void testProxyWithBigResponseContentWithSlowReader() throws Exception
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
                output.write(kb);
        }
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                try (InputStream input = Files.newInputStream(temp))
                {
                    IO.copy(input, response.getOutputStream());
                }
            }
        });
        startProxy();
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
                Assert.assertFalse(result.isFailed());
                Assert.assertEquals(200, result.getResponse().getStatus());
                Assert.assertEquals(length * 1024, getContent().length);
                latch.countDown();
            }
        });
        Assert.assertTrue(latch.await(30, TimeUnit.SECONDS));
    }

    @Test
    public void testProxyWithQueryString() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                resp.getOutputStream().print(req.getQueryString());
            }
        });
        startProxy();
        startClient();

        String query = "a=1&b=%E2%82%AC";
        ContentResponse response = client.newRequest("http://localhost:" + serverConnector.getLocalPort() + "/?" + query)
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals(query, response.getContentAsString());
    }

    @Test
    public void testProxyLongPoll() throws Exception
    {
        final long timeout = 1000;
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
            {
                if (!request.isAsyncStarted())
                {
                    final AsyncContext asyncContext = request.startAsync();
                    asyncContext.setTimeout(timeout);
                    asyncContext.addListener(new AsyncListener()
                    {
                        @Override
                        public void onComplete(AsyncEvent event) throws IOException
                        {
                        }

                        @Override
                        public void onTimeout(AsyncEvent event) throws IOException
                        {
                            if (request.getHeader("Via") != null)
                                response.addHeader(PROXIED_HEADER, "true");
                            asyncContext.complete();
                        }

                        @Override
                        public void onError(AsyncEvent event) throws IOException
                        {
                        }

                        @Override
                        public void onStartAsync(AsyncEvent event) throws IOException
                        {
                        }
                    });
                }
            }
        });
        startProxy();
        startClient();

        Response response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(2 * timeout, TimeUnit.MILLISECONDS)
                .send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @Test
    public void testProxyXForwardedHostHeaderIsPresent() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                PrintWriter writer = resp.getWriter();
                writer.write(req.getHeader("X-Forwarded-Host"));
                writer.flush();
            }
        });
        startProxy();
        startClient();

        ContentResponse response = client.GET("http://localhost:" + serverConnector.getLocalPort());
        Assert.assertThat("Response expected to contain content of X-Forwarded-Host Header from the request",
                response.getContentAsString(),
                Matchers.equalTo("localhost:" + serverConnector.getLocalPort()));
    }

    @Test
    public void testProxyWhiteList() throws Exception
    {
        startServer(new EmptyHttpServlet());
        startProxy();
        startClient();
        int port = serverConnector.getLocalPort();
        proxyServlet.getWhiteListHosts().add("127.0.0.1:" + port);

        // Try with the wrong host
        ContentResponse response = client.newRequest("localhost", port)
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(403, response.getStatus());

        // Try again with the right host
        response = client.newRequest("127.0.0.1", port)
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testProxyBlackList() throws Exception
    {
        startServer(new EmptyHttpServlet());
        startProxy();
        startClient();
        int port = serverConnector.getLocalPort();
        proxyServlet.getBlackListHosts().add("localhost:" + port);

        // Try with the wrong host
        ContentResponse response = client.newRequest("localhost", port)
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(403, response.getStatus());

        // Try again with the right host
        response = client.newRequest("127.0.0.1", port)
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testClientExcludedHosts() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
            }
        });
        startProxy();
        startClient();
        int port = serverConnector.getLocalPort();
        client.getProxyConfiguration().getProxies().get(0).getExcludedAddresses().add("127.0.0.1:" + port);

        // Try with a proxied host
        ContentResponse response = client.newRequest("localhost", port)
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));

        // Try again with an excluded host
        response = client.newRequest("127.0.0.1", port)
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertFalse(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @Test
    public void testTransparentProxy() throws Exception
    {
        testTransparentProxyWithPrefix("/proxy");
    }

    @Test
    public void testTransparentProxyWithRootContext() throws Exception
    {
        testTransparentProxyWithPrefix("/");
    }

    private void testTransparentProxyWithPrefix(String prefix) throws Exception
    {
        final String target = "/test";
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                resp.setStatus(target.equals(req.getRequestURI()) ? 200 : 404);
            }
        });
        String proxyTo = "http://localhost:" + serverConnector.getLocalPort();
        proxyServlet = new ProxyServlet.Transparent();
        Map<String, String> params = new HashMap<>();
        params.put("proxyTo", proxyTo);
        params.put("prefix", prefix);
        startProxy(params);
        startClient();

        // Make the request to the proxy, it should transparently forward to the server
        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
                .path((prefix + target).replaceAll("//", "/"))
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @Test
    public void testTransparentProxyWithQuery() throws Exception
    {
        testTransparentProxyWithQuery("/foo", "/proxy", "/test");
    }

    @Test
    public void testTransparentProxyEmptyContextWithQuery() throws Exception
    {
        testTransparentProxyWithQuery("", "/proxy", "/test");
    }

    @Test
    public void testTransparentProxyEmptyTargetWithQuery() throws Exception
    {
        testTransparentProxyWithQuery("/bar", "/proxy", "");
    }

    @Test
    public void testTransparentProxyEmptyContextEmptyTargetWithQuery() throws Exception
    {
        testTransparentProxyWithQuery("", "/proxy", "");
    }

    private void testTransparentProxyWithQuery(String proxyToContext, String prefix, String target) throws Exception
    {
        final String query = "a=1&b=2";
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
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
                        resp.setStatus(200);
                        return;
                    }
                }
                resp.setStatus(404);
            }
        });
        String proxyTo = "http://localhost:" + serverConnector.getLocalPort() + proxyToContext;
        proxyServlet = new ProxyServlet.Transparent();
        Map<String, String> params = new HashMap<>();
        params.put("proxyTo", proxyTo);
        params.put("prefix", prefix);
        startProxy(params);
        startClient();

        // Make the request to the proxy, it should transparently forward to the server
        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
                .path(prefix + target + "?" + query)
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @Test
    public void testTransparentProxyWithQueryWithSpaces() throws Exception
    {
        final String target = "/test";
        final String query = "a=1&b=2&c=1234%205678&d=hello+world";
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
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
        proxyServlet = new ProxyServlet.Transparent();
        Map<String, String> params = new HashMap<>();
        params.put("proxyTo", proxyTo);
        params.put("prefix", prefix);
        startProxy(params);
        startClient();

        // Make the request to the proxy, it should transparently forward to the server
        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
                .path(prefix + target + "?" + query)
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @Test
    public void testTransparentProxyWithoutPrefix() throws Exception
    {
        final String target = "/test";
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                resp.setStatus(target.equals(req.getRequestURI()) ? 200 : 404);
            }
        });
        final String proxyTo = "http://localhost:" + serverConnector.getLocalPort();
        proxyServlet = new ProxyServlet.Transparent();
        Map<String, String> initParams = new HashMap<>();
        initParams.put("proxyTo", proxyTo);
        startProxy(initParams);
        startClient();

        // Make the request to the proxy, it should transparently forward to the server
        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
                .path(target)
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @Test
    public void testCachingProxy() throws Exception
    {
        final byte[] content = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0xA, 0xB, 0xC, 0xD, 0xE, 0xF};
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                resp.getOutputStream().write(content);
            }
        });
        // Don't do this at home: this example is not concurrent, not complete,
        // it is only used for this test and to verify that ProxyServlet can be
        // subclassed enough to write your own caching servlet
        final String cacheHeader = "X-Cached";
        proxyServlet = new ProxyServlet()
        {
            private Map<String, ContentResponse> cache = new HashMap<>();
            private Map<String, ByteArrayOutputStream> temp = new HashMap<>();

            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                ContentResponse cachedResponse = cache.get(request.getRequestURI());
                if (cachedResponse != null)
                {
                    response.setStatus(cachedResponse.getStatus());
                    // Should copy headers too, but keep it simple
                    response.addHeader(cacheHeader, "true");
                    response.getOutputStream().write(cachedResponse.getContent());
                }
                else
                {
                    super.service(request, response);
                }
            }

            @Override
            protected void onResponseContent(HttpServletRequest request, HttpServletResponse response, Response proxyResponse, byte[] buffer, int offset, int length, Callback callback)
            {
                // Accumulate the response content
                ByteArrayOutputStream baos = temp.get(request.getRequestURI());
                if (baos == null)
                {
                    baos = new ByteArrayOutputStream();
                    temp.put(request.getRequestURI(), baos);
                }
                baos.write(buffer, offset, length);
                super.onResponseContent(request, response, proxyResponse, buffer, offset, length, callback);
            }

            @Override
            protected void onProxyResponseSuccess(HttpServletRequest request, HttpServletResponse response, Response proxyResponse)
            {
                byte[] content = temp.remove(request.getRequestURI()).toByteArray();
                ContentResponse cached = new HttpContentResponse(proxyResponse, content, null, null);
                cache.put(request.getRequestURI(), cached);
                super.onProxyResponseSuccess(request, response, proxyResponse);
            }
        };
        startProxy();
        startClient();

        // First request
        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
        Assert.assertArrayEquals(content, response.getContent());

        // Second request should be cached
        response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(response.getHeaders().containsKey(cacheHeader));
        Assert.assertArrayEquals(content, response.getContent());
    }

    @Test
    public void testRedirectsAreProxied() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                resp.sendRedirect("/");
            }
        });
        startProxy();
        startClient();

        client.setFollowRedirects(false);

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(302, response.getStatus());
        Assert.assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @Test
    public void testGZIPContentIsProxied() throws Exception
    {
        final byte[] content = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");

                resp.addHeader("Content-Encoding", "gzip");
                GZIPOutputStream gzipOutputStream = new GZIPOutputStream(resp.getOutputStream());
                gzipOutputStream.write(content);
                gzipOutputStream.close();
            }
        });
        startProxy();
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
        Assert.assertArrayEquals(content, response.getContent());
    }

    @Test
    public void testWrongContentLength() throws Exception
    {
        
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                byte[] message = "tooshort".getBytes("ascii");
                resp.setContentType("text/plain;charset=ascii");
                resp.setHeader("Content-Length", Long.toString(message.length + 1));
                resp.getOutputStream().write(message);
            }
        });
        startProxy();
        startClient();

        try
        {
            ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();
            Assert.assertThat(response.getStatus(),Matchers.greaterThanOrEqualTo(500));   
        }
        catch(ExecutionException e)
        {     
            Assert.assertThat(e.getCause(),Matchers.instanceOf(IOException.class));
        }
    }

    @Test
    public void testCookiesFromDifferentClientsAreNotMixed() throws Exception
    {
        final String name = "biscuit";
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
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
                    Assert.assertEquals(1, cookies.length);
                }
            }
        });
        startProxy();
        startClient();

        String value1 = "1";
        ContentResponse response1 = client.newRequest("localhost", serverConnector.getLocalPort())
                .header(name, value1)
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(200, response1.getStatus());
        Assert.assertTrue(response1.getHeaders().containsKey(PROXIED_HEADER));
        List<HttpCookie> cookies = client.getCookieStore().getCookies();
        Assert.assertEquals(1, cookies.size());
        Assert.assertEquals(name, cookies.get(0).getName());
        Assert.assertEquals(value1, cookies.get(0).getValue());

        HttpClient client2 = prepareClient();
        try
        {
            String value2 = "2";
            ContentResponse response2 = client2.newRequest("localhost", serverConnector.getLocalPort())
                    .header(name, value2)
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.assertEquals(200, response2.getStatus());
            Assert.assertTrue(response2.getHeaders().containsKey(PROXIED_HEADER));
            cookies = client2.getCookieStore().getCookies();
            Assert.assertEquals(1, cookies.size());
            Assert.assertEquals(name, cookies.get(0).getName());
            Assert.assertEquals(value2, cookies.get(0).getValue());

            // Make a third request to be sure the proxy does not mix cookies
            ContentResponse response3 = client.newRequest("localhost", serverConnector.getLocalPort())
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.assertEquals(200, response3.getStatus());
            Assert.assertTrue(response3.getHeaders().containsKey(PROXIED_HEADER));
        }
        finally
        {
            client2.stop();
        }
    }

    @Test
    public void testProxyRequestFailureInTheMiddleOfProxyingSmallContent() throws Exception
    {
        final CountDownLatch chunk1Latch = new CountDownLatch(1);
        final int chunk1 = 'q';
        final int chunk2 = 'w';
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
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
        startProxy(proxyParams);
        startClient();

        InputStreamResponseListener listener = new InputStreamResponseListener();
        int port = serverConnector.getLocalPort();
        client.newRequest("localhost", port).send(listener);

        // Make the proxy request fail; given the small content, the
        // proxy-to-client response is not committed yet so it will be reset.
        TimeUnit.MILLISECONDS.sleep(2 * proxyTimeout);

        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(504, response.getStatus());

        // Make sure there is no content, as the proxy-to-client response has been reset.
        InputStream input = listener.getInputStream();
        Assert.assertEquals(-1, input.read());

        chunk1Latch.countDown();

        // Result succeeds because a 504 is a valid HTTP response.
        Result result = listener.await(5, TimeUnit.SECONDS);
        Assert.assertTrue(result.isSucceeded());

        // Make sure the proxy does not receive chunk2.
        Assert.assertEquals(-1, input.read());

        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination("http", "localhost", port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        Assert.assertEquals(0, connectionPool.getIdleConnections().size());
    }

    @Test
    public void testProxyRequestFailureInTheMiddleOfProxyingBigContent() throws Exception
    {
        int outputBufferSize = 1024;
        final CountDownLatch chunk1Latch = new CountDownLatch(1);
        final byte[] chunk1 = new byte[outputBufferSize];
        new Random().nextBytes(chunk1);
        final int chunk2 = 'w';
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
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
        startProxy(proxyParams);
        startClient();

        InputStreamResponseListener listener = new InputStreamResponseListener();
        int port = serverConnector.getLocalPort();
        client.newRequest("localhost", port).send(listener);

        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        for (int i = 0; i < chunk1.length; ++i)
            Assert.assertEquals(chunk1[i] & 0xFF, input.read());

        TimeUnit.MILLISECONDS.sleep(2 * proxyTimeout);

        chunk1Latch.countDown();

        try
        {
            // Make sure the proxy does not receive chunk2.
            input.read();
            Assert.fail();
        }
        catch (EOFException x)
        {
            // Expected
        }

        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination("http", "localhost", port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        Assert.assertEquals(0, connectionPool.getIdleConnections().size());
    }

    @Test
    public void testResponseHeadersAreNotRemoved() throws Exception
    {
        startServer(new EmptyHttpServlet());
        startProxy();
        proxyContext.stop();
        final String headerName = "X-Test";
        final String headerValue = "test-value";
        proxyContext.addFilter(new FilterHolder(new Filter()
        {
            @Override
            public void init(FilterConfig filterConfig) throws ServletException
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

        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals(headerValue, response.getHeaders().get(headerName));
    }

    @Test
    public void testHeadersListedByConnectionHeaderAreRemoved() throws Exception
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
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                List<String> names = Collections.list(request.getHeaderNames());
                for (String name : names)
                {
                    if (hopHeaders.containsKey(name))
                        throw new IOException("Hop header must not be proxied: " + name);
                }
            }
        });
        startProxy();
        startClient();

        Request request = client.newRequest("localhost", serverConnector.getLocalPort());
        for (Map.Entry<String, String> entry : hopHeaders.entrySet())
            request.header(entry.getKey(), entry.getValue());
        ContentResponse response = request
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testExpect100ContinueRespond100Continue() throws Exception
    {
        CountDownLatch serverLatch1 = new CountDownLatch(1);
        CountDownLatch serverLatch2 = new CountDownLatch(1);
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
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
        startProxy();
        startClient();

        byte[] content = new byte[1024];
        CountDownLatch contentLatch = new CountDownLatch(1);
        CountDownLatch clientLatch = new CountDownLatch(1);
        client.newRequest("localhost", serverConnector.getLocalPort())
                .header(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString())
                .content(new BytesContentProvider(content))
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
        Assert.assertTrue(serverLatch1.await(5, TimeUnit.SECONDS));
        // The client should not send the content yet.
        Assert.assertFalse(contentLatch.await(1, TimeUnit.SECONDS));

        // Make the server send the 100 Continue.
        serverLatch2.countDown();

        // The client has sent the content.
        Assert.assertTrue(contentLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testExpect100ContinueRespond100ContinueDelayedRequestContent() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                // Send the 100 Continue.
                ServletInputStream input = request.getInputStream();
                // Echo the content.
                IO.copy(input, response.getOutputStream());
            }
        });
        startProxy();
        startClient();

        byte[] content = new byte[1024];
        new Random().nextBytes(content);
        int chunk1 = content.length / 2;
        DeferredContentProvider contentProvider = new DeferredContentProvider();
        contentProvider.offer(ByteBuffer.wrap(content, 0, chunk1));
        CountDownLatch clientLatch = new CountDownLatch(1);
        client.newRequest("localhost", serverConnector.getLocalPort())
                .header(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString())
                .content(contentProvider)
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
        contentProvider.offer(ByteBuffer.wrap(content, chunk1, content.length - chunk1));
        contentProvider.close();

        Assert.assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testExpect100ContinueRespond100ContinueSomeRequestContentThenFailure() throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
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
        startProxy();
        startClient();

        long idleTimeout = 1000;
        client.setIdleTimeout(idleTimeout);

        byte[] content = new byte[1024];
        new Random().nextBytes(content);
        int chunk1 = content.length / 2;
        DeferredContentProvider contentProvider = new DeferredContentProvider();
        contentProvider.offer(ByteBuffer.wrap(content, 0, chunk1));
        CountDownLatch clientLatch = new CountDownLatch(1);
        client.newRequest("localhost", serverConnector.getLocalPort())
                .header(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString())
                .content(contentProvider)
                .send(result ->
                {
                    if (result.isFailed())
                        clientLatch.countDown();
                });

        // Wait more than the idle timeout to break the connection.
        Thread.sleep(2 * idleTimeout);

        Assert.assertTrue(serverLatch.await(555, TimeUnit.SECONDS));
        Assert.assertTrue(clientLatch.await(555, TimeUnit.SECONDS));
    }

    @Test
    public void testExpect100ContinueRespond417ExpectationFailed() throws Exception
    {
        CountDownLatch serverLatch1 = new CountDownLatch(1);
        CountDownLatch serverLatch2 = new CountDownLatch(1);
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
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
        startProxy();
        startClient();

        byte[] content = new byte[1024];
        CountDownLatch contentLatch = new CountDownLatch(1);
        CountDownLatch clientLatch = new CountDownLatch(1);
        client.newRequest("localhost", serverConnector.getLocalPort())
                .header(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString())
                .content(new BytesContentProvider(content))
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
        Assert.assertTrue(serverLatch1.await(5, TimeUnit.SECONDS));
        // The client should not send the content yet.
        Assert.assertFalse(contentLatch.await(1, TimeUnit.SECONDS));

        // Make the server send the 417 Expectation Failed.
        serverLatch2.countDown();

        // The client should not send the content.
        Assert.assertFalse(contentLatch.await(1, TimeUnit.SECONDS));
        Assert.assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }
}
