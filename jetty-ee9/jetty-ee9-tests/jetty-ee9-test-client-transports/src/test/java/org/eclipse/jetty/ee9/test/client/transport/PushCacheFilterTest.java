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

package org.eclipse.jetty.ee9.test.client.transport;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.ee9.servlets.PushCacheFilter;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PushCacheFilterTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transportsWithPushSupport")
    public void testPush(Transport transport) throws Exception
    {
        String primaryResource = "/primary.html";
        String secondaryResource = "/secondary.png";
        byte[] secondaryData = "SECONDARY".getBytes(StandardCharsets.UTF_8);
        start(transport, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                String requestURI = req.getRequestURI();
                ServletOutputStream output = resp.getOutputStream();
                if (requestURI.endsWith(primaryResource))
                    output.print("<html><head></head><body>PRIMARY</body></html>");
                else if (requestURI.endsWith(secondaryResource))
                    output.write(secondaryData);
            }
        });
        servletContextHandler.addFilter(PushCacheFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));

        // Request for the primary and secondary resources to build the cache.
        URI uri = newURI(transport);
        ContentResponse response = client.newRequest(uri)
            .path(primaryResource)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        response = client.newRequest(uri)
            .path(secondaryResource)
            .headers(headers -> headers.put(HttpHeader.REFERER, uri.resolve(primaryResource).toString()))
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // Request again the primary resource, we should get the secondary resource pushed.
        CountDownLatch pushLatch = new CountDownLatch(2);
        response = client.newRequest(uri)
            .path(primaryResource)
            .onPush((request, pushed) ->
            {
                pushLatch.countDown();
                return new BufferingResponseListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        pushLatch.countDown();
                    }
                };
            })
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(pushLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsWithPushSupport")
    public void testPushReferrerNoPath(Transport transport) throws Exception
    {
        String primaryResource = "/primary.html";
        String secondaryResource = "/secondary.png";
        byte[] secondaryData = "SECONDARY".getBytes(StandardCharsets.UTF_8);
        start(transport, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                String requestURI = req.getRequestURI();
                ServletOutputStream output = resp.getOutputStream();
                if (requestURI.endsWith(primaryResource))
                    output.print("<html><head></head><body>PRIMARY</body></html>");
                else if (requestURI.endsWith(secondaryResource))
                    output.write(secondaryData);
            }
        });
        servletContextHandler.addFilter(PushCacheFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));

        // Request for the primary and secondary resources to build the cache.
        // The referrerURI does not point to the primary resource, so there will be no
        // resource association with the primary resource and therefore won't be pushed.
        URI uri = newURI(transport);
        ContentResponse response = client.newRequest(uri)
            .path(primaryResource)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        response = client.newRequest(uri)
            .path(secondaryResource)
            .headers(headers -> headers.put(HttpHeader.REFERER, uri.toString()))
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // Request again the primary resource, we should not get the secondary resource pushed.
        CountDownLatch pushLatch = new CountDownLatch(1);
        response = client.newRequest(uri)
            .path(primaryResource)
            .onPush((request, pushed) ->
            {
                pushLatch.countDown();
                return null;
            })
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertFalse(pushLatch.await(1, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsWithPushSupport")
    public void testPushIsReset(Transport transport) throws Exception
    {
        String primaryResource = "/primary.html";
        String secondaryResource = "/secondary.png";
        byte[] secondaryData = "SECONDARY".getBytes(StandardCharsets.UTF_8);
        start(transport, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                String requestURI = req.getRequestURI();
                ServletOutputStream output = resp.getOutputStream();
                if (requestURI.endsWith(primaryResource))
                    output.print("<html><head></head><body>PRIMARY</body></html>");
                else if (requestURI.endsWith(secondaryResource))
                    output.write(secondaryData);
            }
        });
        servletContextHandler.addFilter(PushCacheFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));

        // Request for the primary and secondary resources to build the cache.
        URI uri = newURI(transport);
        ContentResponse response = client.newRequest(uri)
            .path(primaryResource)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        response = client.newRequest(uri)
            .path(secondaryResource)
            .headers(headers -> headers.put(HttpHeader.REFERER, uri.resolve(primaryResource).toString()))
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // Request again the primary resource, we should get the secondary resource pushed.
        CountDownLatch pushLatch = new CountDownLatch(1);
        response = client.newRequest(uri)
            .path(primaryResource)
            .onPush((request, pushed) ->
            {
                pushLatch.countDown();
                // Cancel the push.
                return null;
            })
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(pushLatch.await(5, TimeUnit.SECONDS));

        // Make sure the connection is sane.
        HttpDestination destination = (HttpDestination)client.getDestinations().get(0);
        assertFalse(destination.getConnectionPool().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("transportsWithPushSupport")
    public void testPushWithoutPrimaryResponseContent(Transport transport) throws Exception
    {
        String primaryResource = "/primary.html";
        String secondaryResource = "/secondary.png";
        start(transport, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                String requestURI = request.getRequestURI();
                ServletOutputStream output = response.getOutputStream();
                if (requestURI.endsWith(secondaryResource))
                    output.write("SECONDARY".getBytes(StandardCharsets.UTF_8));
            }
        });
        servletContextHandler.addFilter(PushCacheFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));

        // Request for the primary and secondary resources to build the cache.
        URI uri = newURI(transport);
        ContentResponse response = client.newRequest(uri)
            .path(primaryResource)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        response = client.newRequest(uri)
            .path(secondaryResource)
            .headers(headers -> headers.put(HttpHeader.REFERER, uri.resolve(primaryResource).toString()))
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // Request again the primary resource, we should get the secondary resource pushed.
        CountDownLatch pushLatch = new CountDownLatch(2);
        response = client.newRequest(uri)
            .path(primaryResource)
            .onPush((request, pushed) ->
            {
                pushLatch.countDown();
                return new BufferingResponseListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        pushLatch.countDown();
                    }
                };
            })
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(pushLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsWithPushSupport")
    public void testRecursivePush(Transport transport) throws Exception
    {
        String primaryResource = "/primary.html";
        String secondaryResource1 = "/secondary1.css";
        String secondaryResource2 = "/secondary2.js";
        String tertiaryResource = "/tertiary.png";
        start(transport, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                String requestURI = request.getRequestURI();
                ServletOutputStream output = response.getOutputStream();
                if (requestURI.endsWith(primaryResource))
                    output.print("<html><head></head><body>PRIMARY</body></html>");
                else if (requestURI.endsWith(secondaryResource1))
                    output.print("body { background-image: url(\"" + tertiaryResource + "\"); }");
                else if (requestURI.endsWith(secondaryResource2))
                    output.print("(function() { window.alert('HTTP/2'); })()");
                if (requestURI.endsWith(tertiaryResource))
                    output.write("TERTIARY".getBytes(StandardCharsets.UTF_8));
            }
        });
        servletContextHandler.addFilter(PushCacheFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));

        // Request for the primary, secondary and tertiary resources to build the cache.
        URI uri = newURI(transport);
        ContentResponse response = client.newRequest(uri)
            .path(primaryResource)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        response = client.newRequest(uri)
            .path(secondaryResource1)
            .headers(headers -> headers.put(HttpHeader.REFERER, uri.resolve(primaryResource).toString()))
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        response = client.newRequest(uri)
            .path(secondaryResource2)
            .headers(headers -> headers.put(HttpHeader.REFERER, uri.resolve(primaryResource).toString()))
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        response = client.newRequest(uri)
            .path(tertiaryResource)
            .headers(headers -> headers.put(HttpHeader.REFERER, uri.resolve(secondaryResource1).toString()))
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // Request again the primary resource, we should get the secondary and tertiary resources pushed.
        CountDownLatch primaryPushLatch = new CountDownLatch(3);
        response = client.newRequest(uri)
            .path(primaryResource)
            .onPush((request, pushed) -> new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    primaryPushLatch.countDown();
                }
            })
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(primaryPushLatch.await(5, TimeUnit.SECONDS));

        // Make sure that explicitly requesting a secondary resource, we get the tertiary pushed.
        CountDownLatch secondaryPushLatch = new CountDownLatch(1);
        response = client.newRequest(uri)
            .path(primaryResource)
            .onPush((request, pushed) -> new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    secondaryPushLatch.countDown();
                }
            })
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(secondaryPushLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsWithPushSupport")
    public void testSelfPush(Transport transport) throws Exception
    {
        // The test case is that of a login page, for example.
        // When the user sends the credentials to the login page,
        // the login may fail and redirect to the same login page,
        // perhaps with different query parameters.
        // In this case a request for the login page will push
        // the login page itself, which will generate the pushed
        // request for the login page, which will push the login
        // page itself, etc. which is not the desired behavior.

        String primaryResource = "/login.html";
        start(transport, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                ServletOutputStream output = response.getOutputStream();
                String credentials = request.getParameter("credentials");
                if (credentials == null)
                {
                    output.print("<html><head></head><body>LOGIN</body></html>");
                }
                else if ("secret".equals(credentials))
                {
                    output.print("<html><head></head><body>OK</body></html>");
                }
                else
                {
                    response.setStatus(HttpStatus.TEMPORARY_REDIRECT_307);
                    response.setHeader(HttpHeader.LOCATION.asString(), primaryResource);
                }
            }
        });
        servletContextHandler.addFilter(PushCacheFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));

        // Login with the wrong credentials, causing a redirect to self.
        URI uri = newURI(transport);
        ContentResponse response = client.newRequest(uri)
            .path(primaryResource + "?credentials=wrong")
            .followRedirects(false)
            .send();
        assertEquals(HttpStatus.TEMPORARY_REDIRECT_307, response.getStatus());
        String location = response.getHeaders().get(HttpHeader.LOCATION);
        response = client.newRequest(uri)
            .path(location)
            .headers(headers -> headers.put(HttpHeader.REFERER, uri.resolve(primaryResource).toString()))
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // Login with the right credentials, there must be no push.
        CountDownLatch pushLatch = new CountDownLatch(1);
        response = client.newRequest(uri)
            .path(primaryResource + "?credentials=secret")
            .onPush((request, pushed) ->
            {
                pushLatch.countDown();
                return null;
            })
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertFalse(pushLatch.await(1, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsWithPushSupport")
    public void testPushWithQueryParameters(Transport transport) throws Exception
    {
        String name = "foo";
        String value = "bar";
        String query = name + "=" + value;
        String primaryResource = "/primary.html?" + query;
        String secondaryResource = "/secondary.html?" + query;
        start(transport, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response)
            {
                String requestURI = request.getRequestURI();
                if (requestURI.endsWith(primaryResource))
                {
                    response.setStatus(HttpStatus.OK_200);
                }
                else if (requestURI.endsWith(secondaryResource))
                {
                    String param = request.getParameter(name);
                    if (param == null)
                        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
                    else
                        response.setStatus(HttpStatus.OK_200);
                }
            }
        });
        servletContextHandler.addFilter(PushCacheFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));

        // Request for the primary and secondary resources to build the cache.
        URI uri = newURI(transport);
        ContentResponse response = client.newRequest(uri)
            .path(primaryResource)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        response = client.newRequest(uri)
            .path(secondaryResource)
            .headers(headers -> headers.put(HttpHeader.REFERER, uri.resolve(primaryResource).toString()))
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // Request again the primary resource, we should get the secondary resource pushed.
        CountDownLatch pushLatch = new CountDownLatch(1);
        response = client.newRequest(uri)
            .path(primaryResource)
            .onPush((request, pushed) ->
            {
                assertEquals(query, pushed.getURI().getQuery());
                return new BufferingResponseListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        pushLatch.countDown();
                    }
                };
            })
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(pushLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsWithPushSupport")
    public void testPOSTRequestIsNotPushed(Transport transport) throws Exception
    {
        String primaryResource = "/primary.html";
        String secondaryResource = "/secondary.png";
        byte[] secondaryData = "SECONDARY".getBytes(StandardCharsets.UTF_8);
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                String requestURI = req.getRequestURI();
                ServletOutputStream output = resp.getOutputStream();
                if (requestURI.endsWith(primaryResource))
                    output.print("<html><head></head><body>PRIMARY</body></html>");
                else if (requestURI.endsWith(secondaryResource))
                    output.write(secondaryData);
            }
        });
        servletContextHandler.addFilter(PushCacheFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));

        // Request for the primary and secondary resource to build the cache.
        URI uri = newURI(transport);
        ContentResponse response = client.newRequest(uri)
            .path(primaryResource)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        response = client.newRequest(uri)
            .path(secondaryResource)
            .headers(headers -> headers.put(HttpHeader.REFERER, uri.resolve(primaryResource).toString()))
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // Request again the primary resource with POST, we should not get the secondary resource pushed.
        CountDownLatch pushLatch = new CountDownLatch(1);
        response = client.newRequest(uri)
            .method(HttpMethod.POST)
            .path(primaryResource)
            .onPush((request, pushed) ->
            {
                pushLatch.countDown();
                return null;
            })
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertFalse(pushLatch.await(1, TimeUnit.SECONDS));
    }
}
