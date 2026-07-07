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

package org.eclipse.jetty.client;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class RedirectCacheTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test301Cached(Scenario scenario) throws Exception
    {
        AtomicInteger redirectCount = new AtomicInteger();
        startServer(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                String path = Request.getPathInContext(request);
                if ("/old".equals(path))
                {
                    redirectCount.incrementAndGet();
                    String location = scenario.getScheme() + "://localhost:" + connector.getLocalPort() + "/new";
                    org.eclipse.jetty.server.Response.sendRedirect(request, response, callback, HttpStatus.MOVED_PERMANENTLY_301, location, true);
                }
                else if ("/new".equals(path))
                {
                    response.setStatus(HttpStatus.OK_200);
                    response.write(true, ReadableBuffer.wrap(StandardCharsets.UTF_8.encode("ok")), callback);
                }
                else
                {
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                    callback.succeeded();
                }
                return true;
            }
        });

        startClient(scenario, httpClient -> httpClient.setRedirectCache(new RedirectCache.Default(100)));

        // First request - should follow redirect and cache it.
        ContentResponse response1 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/old")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response1.getStatus());
        assertEquals("ok", response1.getContentAsString());
        assertEquals(1, redirectCount.get());
        assertEquals(1, client.getRedirectCache().size());

        // Second request - should use cached redirect (no server redirect).
        ContentResponse response2 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/old")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response2.getStatus());
        assertEquals("ok", response2.getContentAsString());
        // Redirect count should still be 1 - the server was not asked to redirect.
        assertEquals(1, redirectCount.get());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test301NotCachedForDifferentQuery(Scenario scenario) throws Exception
    {
        AtomicInteger redirectCount = new AtomicInteger();
        startServer(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                String path = Request.getPathInContext(request);
                if ("/old".equals(path))
                {
                    redirectCount.incrementAndGet();
                    String location = scenario.getScheme() + "://localhost:" + connector.getLocalPort() + "/new";
                    org.eclipse.jetty.server.Response.sendRedirect(request, response, callback, HttpStatus.MOVED_PERMANENTLY_301, location, true);
                }
                else if ("/new".equals(path))
                {
                    response.setStatus(HttpStatus.OK_200);
                    response.write(true, ReadableBuffer.wrap(StandardCharsets.UTF_8.encode("ok")), callback);
                }
                else
                {
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                    callback.succeeded();
                }
                return true;
            }
        });

        startClient(scenario, httpClient -> httpClient.setRedirectCache(new RedirectCache.Default(100)));

        // First request - should follow redirect and cache it.
        ContentResponse response1 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/old?time=1")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response1.getStatus());
        assertEquals("ok", response1.getContentAsString());
        assertEquals(1, redirectCount.get());
        assertEquals(1, client.getRedirectCache().size());

        // Second request - different query, should not use the cache.
        ContentResponse response2 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/old?time=2")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response2.getStatus());
        assertEquals("ok", response2.getContentAsString());
        assertEquals(2, redirectCount.get());
        assertEquals(2, client.getRedirectCache().size());

        // Third request - same query as the first, should use the cache.
        ContentResponse response3 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/old?time=1")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response3.getStatus());
        assertEquals("ok", response3.getContentAsString());
        assertEquals(2, redirectCount.get());
        assertEquals(2, client.getRedirectCache().size());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test308Cached(Scenario scenario) throws Exception
    {
        AtomicInteger redirectCount = new AtomicInteger();
        byte[] data = new byte[]{0, 1, 2, 3, 4, 5, 6, 7};

        startServer(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                String path = Request.getPathInContext(request);
                if ("/old".equals(path))
                {
                    redirectCount.incrementAndGet();
                    String location = scenario.getScheme() + "://localhost:" + connector.getLocalPort() + "/new";
                    org.eclipse.jetty.server.Response.sendRedirect(request, response, callback, HttpStatus.PERMANENT_REDIRECT_308, location, true);
                }
                else if ("/new".equals(path))
                {
                    // Verify POST method preserved.
                    assertEquals("POST", request.getMethod());
                    response.setStatus(HttpStatus.OK_200);
                    // Echo back the content.
                    Content.copy(request, response, callback);
                }
                else
                {
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                    callback.succeeded();
                }
                return true;
            }
        });

        startClient(scenario, httpClient -> httpClient.setRedirectCache(new RedirectCache.Default(100)));

        // First request - POST with body, 308 preserves method.
        ContentResponse response1 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .path("/old")
            .body(new ByteBufferRequestContent(ByteBuffer.wrap(data)))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response1.getStatus());
        assertArrayEquals(data, response1.getContent());
        assertEquals(1, redirectCount.get());

        // Second request - should use cached redirect.
        var request2 = client.newRequest("localhost", connector.getLocalPort());
        ContentResponse response2 = request2
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .path("/old")
            .body(new ByteBufferRequestContent(ByteBuffer.wrap(data)))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals("/old", request2.getPath());

        assertEquals(HttpStatus.OK_200, response2.getStatus());
        assertArrayEquals(data, response2.getContent());
        // Redirect count should still be 1.
        assertEquals(1, redirectCount.get());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test301PostToGet(Scenario scenario) throws Exception
    {
        AtomicInteger redirectCount = new AtomicInteger();

        startServer(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                String path = Request.getPathInContext(request);
                if ("/old".equals(path))
                {
                    redirectCount.incrementAndGet();
                    String location = scenario.getScheme() + "://localhost:" + connector.getLocalPort() + "/new";
                    org.eclipse.jetty.server.Response.sendRedirect(request, response, callback, HttpStatus.MOVED_PERMANENTLY_301, location, true);
                }
                else if ("/new".equals(path))
                {
                    // 301 converts POST to GET.
                    assertEquals("GET", request.getMethod());
                    response.setStatus(HttpStatus.OK_200);
                    response.write(true, ReadableBuffer.wrap(StandardCharsets.UTF_8.encode("ok")), callback);
                }
                else
                {
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                    callback.succeeded();
                }
                return true;
            }
        });

        startClient(scenario, httpClient -> httpClient.setRedirectCache(new RedirectCache.Default(100)));

        // First request - POST converted to GET.
        ContentResponse response1 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .path("/old")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response1.getStatus());
        assertEquals(1, redirectCount.get());

        // Second request as POST - cached redirect should convert to GET.
        ContentResponse response2 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .path("/old")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response2.getStatus());
        // Redirect count should still be 1.
        assertEquals(1, redirectCount.get());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testCrossOriginRedirect(Scenario scenario) throws Exception
    {
        AtomicInteger redirectCount = new AtomicInteger();

        startServer(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                String path = Request.getPathInContext(request);
                String host = Request.getServerName(request);
                if ("/old".equals(path) && "localhost".equals(host))
                {
                    redirectCount.incrementAndGet();
                    // Redirect to 127.0.0.1 (different host)
                    String location = scenario.getScheme() + "://127.0.0.1:" + connector.getLocalPort() + "/new";
                    org.eclipse.jetty.server.Response.sendRedirect(request, response, callback, HttpStatus.MOVED_PERMANENTLY_301, location, true);
                }
                else if ("/new".equals(path))
                {
                    assertEquals("127.0.0.1", host);
                    response.setStatus(HttpStatus.OK_200);
                    response.write(true, ReadableBuffer.wrap(StandardCharsets.UTF_8.encode("ok")), callback);
                }
                else
                {
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                    callback.succeeded();
                }
                return true;
            }
        });

        startClient(scenario, httpClient -> httpClient.setRedirectCache(new RedirectCache.Default(100)));

        // First request.
        ContentResponse response1 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/old")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response1.getStatus());
        assertEquals(1, redirectCount.get());
        assertEquals(1, client.getRedirectCache().size());
        assertEquals(2, client.getDestinations().size());

        // Second request - should use cached cross-origin redirect.
        ContentResponse response2 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/old")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response2.getStatus());
        assertEquals(1, redirectCount.get());
    }

    @Test
    public void testCacheEviction()
    {
        RedirectCache.Default cache = new RedirectCache.Default(2);

        RedirectCache.MethodOriginTarget original1 = new RedirectCache.MethodOriginTarget("GET", URI.create("http://example.com"), "/old1");
        RedirectCache.MethodOriginTarget redirect1 = new RedirectCache.MethodOriginTarget("GET", URI.create("http://example.com"), "/new1");
        cache.put(original1, HttpStatus.PERMANENT_REDIRECT_308, redirect1);
        RedirectCache.MethodOriginTarget original2 = new RedirectCache.MethodOriginTarget("GET", URI.create("http://example.com"), "/old2");
        RedirectCache.MethodOriginTarget redirect2 = new RedirectCache.MethodOriginTarget("GET", URI.create("http://example.com"), "/new2");
        cache.put(original2, HttpStatus.PERMANENT_REDIRECT_308, redirect2);

        assertEquals(2, cache.size());
        assertNotNull(cache.get(original1));
        assertNotNull(cache.get(original2));

        // Adding a third entry should evict the least recently used.
        RedirectCache.MethodOriginTarget original3 = new RedirectCache.MethodOriginTarget("GET", URI.create("http://example.com"), "/old3");
        RedirectCache.MethodOriginTarget redirect3 = new RedirectCache.MethodOriginTarget("GET", URI.create("http://example.com"), "/new3");
        cache.put(original3, HttpStatus.PERMANENT_REDIRECT_308, redirect3);

        assertEquals(2, cache.size());
        assertNull(cache.get(original1));
        assertNotNull(cache.get(original2));
        assertNotNull(cache.get(original3));
    }

    @Test
    public void testCacheClearedOnStop() throws Exception
    {
        RedirectCache.Default cache = new RedirectCache.Default(100);
        try (HttpClient httpClient = new HttpClient())
        {
            httpClient.setRedirectCache(cache);
            httpClient.start();

            RedirectCache.MethodOriginTarget original1 = new RedirectCache.MethodOriginTarget("GET", URI.create("http://example.com"), "/old1");
            RedirectCache.MethodOriginTarget redirect1 = new RedirectCache.MethodOriginTarget("GET", URI.create("http://example.com"), "/new1");
            cache.put(original1, HttpStatus.PERMANENT_REDIRECT_308, redirect1);

            assertEquals(1, cache.size());
        }

        assertEquals(0, cache.size());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testNoCache(Scenario scenario) throws Exception
    {
        AtomicInteger redirectCount = new AtomicInteger();

        startServer(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                String path = Request.getPathInContext(request);
                if ("/old".equals(path))
                {
                    redirectCount.incrementAndGet();
                    String location = scenario.getScheme() + "://localhost:" + connector.getLocalPort() + "/new";
                    org.eclipse.jetty.server.Response.sendRedirect(request, response, callback, HttpStatus.MOVED_PERMANENTLY_301, location, true);
                }
                else if ("/new".equals(path))
                {
                    response.setStatus(HttpStatus.OK_200);
                    response.write(true, ReadableBuffer.wrap(StandardCharsets.UTF_8.encode("ok")), callback);
                }
                else
                {
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                    callback.succeeded();
                }
                return true;
            }
        });

        // No cache set - default behavior.
        startClient(scenario, null);

        // First request.
        ContentResponse response1 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/old")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response1.getStatus());
        assertEquals(1, redirectCount.get());

        // Second request - redirect should happen again (no caching).
        ContentResponse response2 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/old")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response2.getStatus());
        assertEquals(2, redirectCount.get());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testTemporaryRedirectNotCached(Scenario scenario) throws Exception
    {
        AtomicInteger redirectCount = new AtomicInteger();

        startServer(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                String path = Request.getPathInContext(request);
                if ("/old".equals(path))
                {
                    redirectCount.incrementAndGet();
                    String location = scenario.getScheme() + "://localhost:" + connector.getLocalPort() + "/new";
                    org.eclipse.jetty.server.Response.sendRedirect(request, response, callback, HttpStatus.TEMPORARY_REDIRECT_307, location, true);
                }
                else if ("/new".equals(path))
                {
                    response.setStatus(HttpStatus.OK_200);
                    response.write(true, ReadableBuffer.wrap(StandardCharsets.UTF_8.encode("ok")), callback);
                }
                else
                {
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                    callback.succeeded();
                }
                return true;
            }
        });

        startClient(scenario, httpClient -> httpClient.setRedirectCache(new RedirectCache.Default(100)));

        // First request - 307 should not be cached.
        ContentResponse response1 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/old")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response1.getStatus());
        assertEquals(1, redirectCount.get());
        assertEquals(0, client.getRedirectCache().size());

        // Second request - redirect should happen again (not cached).
        ContentResponse response2 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/old")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response2.getStatus());
        assertEquals(2, redirectCount.get());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testOptionsStar301Redirect(Scenario scenario) throws Exception
    {
        AtomicInteger redirectCount = new AtomicInteger();
        startServer(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.setStatus(HttpStatus.NOT_FOUND_404);
                if (HttpMethod.OPTIONS.is(request.getMethod()))
                {
                    String path = Request.getPathInContext(request);
                    if ("*".equals(path))
                    {
                        String host = Request.getServerName(request);
                        if ("localhost".equals(host))
                        {
                            redirectCount.incrementAndGet();
                            String location = scenario.getScheme() + "://127.0.0.1:" + connector.getLocalPort();
                            org.eclipse.jetty.server.Response.sendRedirect(request, response, callback, HttpStatus.MOVED_PERMANENTLY_301, location, true);
                            return true;
                        }
                        else
                        {
                            response.setStatus(HttpStatus.OK_200);
                        }
                    }
                }
                callback.succeeded();
                return true;
            }
        });

        startClient(scenario, httpClient -> httpClient.setRedirectCache(new RedirectCache.Default(100)));

        // First request - should follow redirect and cache it.
        ContentResponse response1 = client.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.OPTIONS)
            .scheme(scenario.getScheme())
            .path("*")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response1.getStatus());
        assertEquals(1, redirectCount.get());
        assertEquals(1, client.getRedirectCache().size());

        // Second request - should use cached redirect (no server redirect).
        ContentResponse response2 = client.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.OPTIONS)
            .scheme(scenario.getScheme())
            .path("*")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response2.getStatus());
        // Redirect count should still be 1 - the server was not asked to redirect.
        assertEquals(1, redirectCount.get());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testOptionsStar301RedirectToPath(Scenario scenario) throws Exception
    {
        AtomicInteger redirectCount = new AtomicInteger();
        startServer(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.setStatus(HttpStatus.NOT_FOUND_404);
                if (HttpMethod.OPTIONS.is(request.getMethod()))
                {
                    String path = Request.getPathInContext(request);
                    if ("*".equals(path))
                    {
                        String host = Request.getServerName(request);
                        if ("localhost".equals(host))
                        {
                            redirectCount.incrementAndGet();
                            String location = scenario.getScheme() + "://127.0.0.1:" + connector.getLocalPort() + "/path";
                            org.eclipse.jetty.server.Response.sendRedirect(request, response, callback, HttpStatus.MOVED_PERMANENTLY_301, location, true);
                            return true;
                        }
                    }
                    else if ("/path".equals(path))
                    {
                        response.setStatus(HttpStatus.OK_200);
                    }
                }
                callback.succeeded();
                return true;
            }
        });

        startClient(scenario, httpClient -> httpClient.setRedirectCache(new RedirectCache.Default(100)));

        // First request - should follow redirect and cache it.
        ContentResponse response1 = client.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.OPTIONS)
            .scheme(scenario.getScheme())
            .path("*")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response1.getStatus());
        assertEquals(1, redirectCount.get());
        assertEquals(1, client.getRedirectCache().size());

        // Second request - should use cached redirect (no server redirect).
        ContentResponse response2 = client.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.OPTIONS)
            .scheme(scenario.getScheme())
            .path("*")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response2.getStatus());
        // Redirect count should still be 1 - the server was not asked to redirect.
        assertEquals(1, redirectCount.get());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testConnectRedirectWithPath(Scenario scenario) throws Exception
    {
        AtomicInteger redirectCount = new AtomicInteger();
        startServer(scenario, new Handler.Abstract()
        {
            private final AtomicBoolean redirected = new AtomicBoolean();

            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.setStatus(HttpStatus.NOT_FOUND_404);
                if (HttpMethod.CONNECT.is(request.getMethod()))
                {
                    String authority = request.getHttpURI().getAuthority();
                    if ("localhost:8080".equals(authority))
                    {
                        if (redirected.compareAndSet(false, true))
                        {
                            redirectCount.incrementAndGet();
                            String location = scenario.getScheme() + "://127.0.0.1:" + connector.getLocalPort() + "/path";
                            org.eclipse.jetty.server.Response.sendRedirect(request, response, callback, HttpStatus.MOVED_PERMANENTLY_301, location, true);
                            return true;
                        }
                        else
                        {
                            response.setStatus(HttpStatus.OK_200);
                        }
                    }
                }
                callback.succeeded();
                return true;
            }
        });

        startClient(scenario, httpClient -> httpClient.setRedirectCache(new RedirectCache.Default(100)));

        // First request - should follow redirect and cache it.
        ContentResponse response1 = client.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.CONNECT)
            .scheme(scenario.getScheme())
            .path("localhost:8080")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response1.getStatus());
        assertEquals(1, redirectCount.get());
        assertEquals(1, client.getRedirectCache().size());

        // Second request - should use cached redirect (no server redirect).
        ContentResponse response2 = client.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.CONNECT)
            .scheme(scenario.getScheme())
            .path("localhost:8080")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response2.getStatus());
        // Redirect count should still be 1 - the server was not asked to redirect.
        assertEquals(1, redirectCount.get());
    }
}
