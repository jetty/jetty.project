//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.HttpCookie;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
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
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpContentResponse;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
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

    @Parameterized.Parameters
    public static Iterable<Object[]> data()
    {
        return Arrays.asList(new Object[][]{
                {ProxyServlet.class},
                {AsyncProxyServlet.class}
        });
    }

    @Rule
    public final TestTracker tracker = new TestTracker();
    private HttpClient client;
    private Server proxy;
    private ServerConnector proxyConnector;
    private ProxyServlet proxyServlet;
    private Server server;
    private ServerConnector serverConnector;

    public ProxyServletTest(Class<?> proxyServletClass) throws Exception
    {
        this.proxyServlet = (ProxyServlet)proxyServletClass.newInstance();
    }

    private void prepareProxy() throws Exception
    {
        prepareProxy(new HashMap<String, String>());
    }

    private void prepareProxy(Map<String, String> initParams) throws Exception
    {
        proxy = new Server();
        proxyConnector = new ServerConnector(proxy);
        proxy.addConnector(proxyConnector);

        ServletContextHandler proxyCtx = new ServletContextHandler(proxy, "/", true, false);
        ServletHolder proxyServletHolder = new ServletHolder(proxyServlet);
        proxyServletHolder.setInitParameters(initParams);
        proxyCtx.addServlet(proxyServletHolder, "/*");

        proxy.start();

        client = prepareClient();
    }

    private HttpClient prepareClient() throws Exception
    {
        HttpClient result = new HttpClient();
        result.getProxyConfiguration().getProxies().add(new HttpProxy("localhost", proxyConnector.getLocalPort()));
        result.start();
        return result;
    }

    private void prepareServer(HttpServlet servlet) throws Exception
    {
        server = new Server();
        serverConnector = new ServerConnector(server);
        server.addConnector(serverConnector);

        ServletContextHandler appCtx = new ServletContextHandler(server, "/", true, false);
        ServletHolder appServletHolder = new ServletHolder(servlet);
        appCtx.addServlet(appServletHolder, "/*");

        server.start();
    }

    @After
    public void disposeProxy() throws Exception
    {
        client.stop();
        proxy.stop();
    }

    @After
    public void disposeServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testProxyDown() throws Exception
    {
        prepareProxy();
        prepareServer(new EmptyHttpServlet());

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
        prepareProxy();
        prepareServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
            }
        });

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @Test
    public void testProxyWithResponseContent() throws Exception
    {
        prepareProxy();

        HttpClient result = new HttpClient();
        result.getProxyConfiguration().getProxies().add(new HttpProxy("localhost", proxyConnector.getLocalPort()));
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName("foo");
        threadPool.setMaxThreads(20);
        result.setExecutor(threadPool);
        result.start();

        ContentResponse[] responses = new ContentResponse[10];

        final byte[] content = new byte[1024];
        new Random().nextBytes(content);
        prepareServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                resp.getOutputStream().write(content);
            }
        });

        for (int i = 0; i < 10; ++i)
        {
            // Request is for the target server
            responses[i] = result.newRequest("localhost", serverConnector.getLocalPort())
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
        prepareProxy();
        prepareServer(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                IO.copy(req.getInputStream(), resp.getOutputStream());
            }
        });

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
        prepareProxy();
        prepareServer(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
            }
        });

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

        prepareProxy();
        prepareServer(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                InputStream input = req.getInputStream();
                int index = 0;
                while (true)
                {
                    int value = input.read();
                    if (value < 0)
                        break;
                    Assert.assertEquals("Content mismatch at index=" + index, content[index] & 0xFF, value);
                    ++index;
                }
            }
        });

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .method(HttpMethod.POST)
                .content(new BytesContentProvider(content))
                .timeout(555, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @Slow
    @Test
    public void testProxyWithBigResponseContentWithSlowReader() throws Exception
    {
        prepareProxy();

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

        prepareServer(new HttpServlet()
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
        prepareProxy();
        String query = "a=1&b=%E2%82%AC";
        prepareServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                resp.getOutputStream().print(req.getQueryString());
            }
        });

        ContentResponse response = client.newRequest("http://localhost:" + serverConnector.getLocalPort() + "/?" + query)
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals(query, response.getContentAsString());
    }

    @Slow
    @Test
    public void testProxyLongPoll() throws Exception
    {
        prepareProxy();
        final long timeout = 1000;
        prepareServer(new HttpServlet()
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

        Response response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(2 * timeout, TimeUnit.MILLISECONDS)
                .send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
    }

    @Test
    public void testProxyXForwardedHostHeaderIsPresent() throws Exception
    {
        prepareProxy();
        prepareServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                PrintWriter writer = resp.getWriter();
                writer.write(req.getHeader("X-Forwarded-Host"));
                writer.flush();
            }
        });

        ContentResponse response = client.GET("http://localhost:" + serverConnector.getLocalPort());
        Assert.assertThat("Response expected to contain content of X-Forwarded-Host Header from the request",
                response.getContentAsString(),
                Matchers.equalTo("localhost:" + serverConnector.getLocalPort()));
    }

    @Test
    public void testProxyWhiteList() throws Exception
    {
        prepareProxy();
        prepareServer(new EmptyHttpServlet());
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
        prepareProxy();
        prepareServer(new EmptyHttpServlet());
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
        prepareProxy();
        prepareServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
            }
        });
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
        prepareServer(new HttpServlet()
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
        prepareProxy(params);

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
        final String target = "/test";
        final String query = "a=1&b=2";
        prepareServer(new HttpServlet()
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
        prepareProxy(params);

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
        prepareServer(new HttpServlet()
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
        prepareProxy(initParams);

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
        prepareServer(new HttpServlet()
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
            protected void onResponseSuccess(HttpServletRequest request, HttpServletResponse response, Response proxyResponse)
            {
                byte[] content = temp.remove(request.getRequestURI()).toByteArray();
                ContentResponse cached = new HttpContentResponse(proxyResponse, content, null, null);
                cache.put(request.getRequestURI(), cached);
                super.onResponseSuccess(request, response, proxyResponse);
            }
        };
        prepareProxy();

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
        prepareProxy();
        prepareServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                resp.sendRedirect("/");
            }
        });

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
        prepareProxy();
        prepareServer(new HttpServlet()
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

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(response.getHeaders().containsKey(PROXIED_HEADER));
        Assert.assertArrayEquals(content, response.getContent());
    }

    @Test(expected = TimeoutException.class)
    public void testWrongContentLength() throws Exception
    {
        prepareProxy();
        prepareServer(new HttpServlet()
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

        client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(1, TimeUnit.SECONDS)
                .send();

        Assert.fail();
    }

    @Test
    public void testCookiesFromDifferentClientsAreNotMixed() throws Exception
    {
        final String name = "biscuit";
        prepareProxy();
        prepareServer(new HttpServlet()
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

    // TODO: test proxy authentication
}
