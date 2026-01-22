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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PermanentRedirectCacheTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test301Cached(Scenario scenario) throws Exception
    {
        AtomicInteger redirectCount = new AtomicInteger();
        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                String path = Request.getPathInContext(request);
                if ("/old".equals(path))
                {
                    redirectCount.incrementAndGet();
                    response.setStatus(HttpStatus.MOVED_PERMANENTLY_301);
                    response.getHeaders().put(HttpHeader.LOCATION, scenario.getScheme() + "://localhost:" + connector.getLocalPort() + "/new");
                    callback.succeeded();
                }
                else if ("/new".equals(path))
                {
                    response.setStatus(HttpStatus.OK_200);
                    response.write(true, ByteBuffer.wrap("ok".getBytes(StandardCharsets.UTF_8)), callback);
                }
                else
                {
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                    callback.succeeded();
                }
                return true;
            }
        });

        startClient(scenario, httpClient -> httpClient.setPermanentRedirectCache(new PermanentRedirectCache.Default(100)));

        // First request - should follow redirect and cache it
        ContentResponse response1 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/old")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response1.getStatus());
        assertEquals("ok", response1.getContentAsString());
        assertEquals(1, redirectCount.get());
        assertEquals(1, client.getPermanentRedirectCache().size());

        // Second request - should use cached redirect (no server redirect)
        ContentResponse response2 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/old")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response2.getStatus());
        assertEquals("ok", response2.getContentAsString());
        // Redirect count should still be 1 - the server was not asked to redirect
        assertEquals(1, redirectCount.get());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test308Cached(Scenario scenario) throws Exception
    {
        AtomicInteger redirectCount = new AtomicInteger();
        byte[] data = new byte[]{0, 1, 2, 3, 4, 5, 6, 7};

        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                String path = Request.getPathInContext(request);
                if ("/old".equals(path))
                {
                    redirectCount.incrementAndGet();
                    response.setStatus(HttpStatus.PERMANENT_REDIRECT_308);
                    response.getHeaders().put(HttpHeader.LOCATION, scenario.getScheme() + "://localhost:" + connector.getLocalPort() + "/new");
                    callback.succeeded();
                }
                else if ("/new".equals(path))
                {
                    // Verify POST method preserved
                    assertEquals("POST", request.getMethod());
                    response.setStatus(HttpStatus.OK_200);
                    // Echo back the content
                    ByteBuffer buffer = ByteBuffer.allocate(data.length);
                    while (buffer.hasRemaining())
                    {
                        org.eclipse.jetty.io.Content.Chunk chunk = request.read();
                        if (chunk == null)
                        {
                            Thread.sleep(10);
                            continue;
                        }
                        if (chunk.hasRemaining())
                            buffer.put(chunk.getByteBuffer());
                        chunk.release();
                        if (chunk.isLast())
                            break;
                    }
                    buffer.flip();
                    response.write(true, buffer, callback);
                }
                else
                {
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                    callback.succeeded();
                }
                return true;
            }
        });

        startClient(scenario, httpClient -> httpClient.setPermanentRedirectCache(new PermanentRedirectCache.Default(100)));

        // First request - POST with body, 308 preserves method
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

        // Second request - should use cached redirect
        ContentResponse response2 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .path("/old")
            .body(new ByteBufferRequestContent(ByteBuffer.wrap(data)))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response2.getStatus());
        assertArrayEquals(data, response2.getContent());
        // Redirect count should still be 1
        assertEquals(1, redirectCount.get());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test301PostToGet(Scenario scenario) throws Exception
    {
        AtomicInteger redirectCount = new AtomicInteger();

        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                String path = Request.getPathInContext(request);
                if ("/old".equals(path))
                {
                    redirectCount.incrementAndGet();
                    response.setStatus(HttpStatus.MOVED_PERMANENTLY_301);
                    response.getHeaders().put(HttpHeader.LOCATION, scenario.getScheme() + "://localhost:" + connector.getLocalPort() + "/new");
                    callback.succeeded();
                }
                else if ("/new".equals(path))
                {
                    // 301 converts POST to GET
                    assertEquals("GET", request.getMethod());
                    response.setStatus(HttpStatus.OK_200);
                    response.write(true, ByteBuffer.wrap("ok".getBytes(StandardCharsets.UTF_8)), callback);
                }
                else
                {
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                    callback.succeeded();
                }
                return true;
            }
        });

        startClient(scenario, httpClient -> httpClient.setPermanentRedirectCache(new PermanentRedirectCache.Default(100)));

        // First request - POST converted to GET
        ContentResponse response1 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .path("/old")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response1.getStatus());
        assertEquals(1, redirectCount.get());

        // Second request as POST - cached redirect should convert to GET
        ContentResponse response2 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .path("/old")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response2.getStatus());
        // Redirect count should still be 1
        assertEquals(1, redirectCount.get());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testCrossOriginRedirect(Scenario scenario) throws Exception
    {
        AtomicInteger redirectCount = new AtomicInteger();

        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                String path = Request.getPathInContext(request);
                String host = Request.getServerName(request);
                if ("/old".equals(path) && "localhost".equals(host))
                {
                    redirectCount.incrementAndGet();
                    response.setStatus(HttpStatus.MOVED_PERMANENTLY_301);
                    // Redirect to 127.0.0.1 (different host)
                    response.getHeaders().put(HttpHeader.LOCATION, scenario.getScheme() + "://127.0.0.1:" + connector.getLocalPort() + "/new");
                    callback.succeeded();
                }
                else if ("/new".equals(path))
                {
                    response.setStatus(HttpStatus.OK_200);
                    response.write(true, ByteBuffer.wrap("ok".getBytes(StandardCharsets.UTF_8)), callback);
                }
                else
                {
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                    callback.succeeded();
                }
                return true;
            }
        });

        startClient(scenario, httpClient -> httpClient.setPermanentRedirectCache(new PermanentRedirectCache.Default(100)));

        // First request
        ContentResponse response1 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/old")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response1.getStatus());
        assertEquals(1, redirectCount.get());
        assertEquals(1, client.getPermanentRedirectCache().size());

        // Second request - should use cached cross-origin redirect
        ContentResponse response2 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/old")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response2.getStatus());
        assertEquals(1, redirectCount.get());
    }

    @Test
    public void testCacheEviction() throws Exception
    {
        PermanentRedirectCache.Default cache = new PermanentRedirectCache.Default(2);

        cache.put("uri1", new PermanentRedirectCache.CachedRedirect(
            java.net.URI.create("http://example.com/1"), "GET", 301, System.currentTimeMillis()));
        cache.put("uri2", new PermanentRedirectCache.CachedRedirect(
            java.net.URI.create("http://example.com/2"), "GET", 301, System.currentTimeMillis()));

        assertEquals(2, cache.size());
        assertNotNull(cache.get("uri1"));
        assertNotNull(cache.get("uri2"));

        // Adding third entry should evict the least recently used
        cache.put("uri3", new PermanentRedirectCache.CachedRedirect(
            java.net.URI.create("http://example.com/3"), "GET", 301, System.currentTimeMillis()));

        assertEquals(2, cache.size());
        assertNotNull(cache.get("uri2"));
        assertNotNull(cache.get("uri3"));
    }

    @Test
    public void testCacheClearedOnStop() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        PermanentRedirectCache.Default cache = new PermanentRedirectCache.Default(100);
        httpClient.setPermanentRedirectCache(cache);
        httpClient.start();

        cache.put("uri1", new PermanentRedirectCache.CachedRedirect(
            java.net.URI.create("http://example.com/1"), "GET", 301, System.currentTimeMillis()));

        assertEquals(1, cache.size());

        httpClient.stop();

        assertEquals(0, cache.size());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testDisabledCache(Scenario scenario) throws Exception
    {
        AtomicInteger redirectCount = new AtomicInteger();

        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                String path = Request.getPathInContext(request);
                if ("/old".equals(path))
                {
                    redirectCount.incrementAndGet();
                    response.setStatus(HttpStatus.MOVED_PERMANENTLY_301);
                    response.getHeaders().put(HttpHeader.LOCATION, scenario.getScheme() + "://localhost:" + connector.getLocalPort() + "/new");
                    callback.succeeded();
                }
                else if ("/new".equals(path))
                {
                    response.setStatus(HttpStatus.OK_200);
                    response.write(true, ByteBuffer.wrap("ok".getBytes(StandardCharsets.UTF_8)), callback);
                }
                else
                {
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                    callback.succeeded();
                }
                return true;
            }
        });

        // No cache set - default behavior
        startClient(scenario, null);

        // First request
        ContentResponse response1 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/old")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response1.getStatus());
        assertEquals(1, redirectCount.get());

        // Second request - redirect should happen again (no caching)
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
    public void testCacheMiss(Scenario scenario) throws Exception
    {
        AtomicInteger redirectCount = new AtomicInteger();

        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                String path = Request.getPathInContext(request);
                if ("/old".equals(path))
                {
                    redirectCount.incrementAndGet();
                    response.setStatus(HttpStatus.MOVED_PERMANENTLY_301);
                    response.getHeaders().put(HttpHeader.LOCATION, scenario.getScheme() + "://localhost:" + connector.getLocalPort() + "/new");
                    callback.succeeded();
                }
                else if ("/new".equals(path) || "/direct".equals(path))
                {
                    response.setStatus(HttpStatus.OK_200);
                    response.write(true, ByteBuffer.wrap("ok".getBytes(StandardCharsets.UTF_8)), callback);
                }
                else
                {
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                    callback.succeeded();
                }
                return true;
            }
        });

        startClient(scenario, httpClient -> httpClient.setPermanentRedirectCache(new PermanentRedirectCache.Default(100)));

        // Request to uncached URI
        ContentResponse response1 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/direct")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response1.getStatus());
        assertEquals(0, redirectCount.get());
        assertEquals(0, client.getPermanentRedirectCache().size());

        // Now request to redirecting URI
        ContentResponse response2 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/old")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response2.getStatus());
        assertEquals(1, redirectCount.get());
        assertEquals(1, client.getPermanentRedirectCache().size());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testTemporaryRedirectNotCached(Scenario scenario) throws Exception
    {
        AtomicInteger redirectCount = new AtomicInteger();

        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                String path = Request.getPathInContext(request);
                if ("/old".equals(path))
                {
                    redirectCount.incrementAndGet();
                    response.setStatus(HttpStatus.TEMPORARY_REDIRECT_307);
                    response.getHeaders().put(HttpHeader.LOCATION, scenario.getScheme() + "://localhost:" + connector.getLocalPort() + "/new");
                    callback.succeeded();
                }
                else if ("/new".equals(path))
                {
                    response.setStatus(HttpStatus.OK_200);
                    response.write(true, ByteBuffer.wrap("ok".getBytes(StandardCharsets.UTF_8)), callback);
                }
                else
                {
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                    callback.succeeded();
                }
                return true;
            }
        });

        startClient(scenario, httpClient -> httpClient.setPermanentRedirectCache(new PermanentRedirectCache.Default(100)));

        // First request - 307 should not be cached
        ContentResponse response1 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/old")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response1.getStatus());
        assertEquals(1, redirectCount.get());
        assertEquals(0, client.getPermanentRedirectCache().size());

        // Second request - redirect should happen again (not cached)
        ContentResponse response2 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/old")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response2.getStatus());
        assertEquals(2, redirectCount.get());
    }

    @Override
    protected void startClient(Scenario scenario) throws Exception
    {
        // Don't auto-start client - tests will configure and start it via startClient(scenario, config)
    }

    protected void startClient(Scenario scenario, java.util.function.Consumer<HttpClient> config) throws Exception
    {
        super.startClient(scenario, config);
    }
}
