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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.ByteBufferContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class HttpClientRedirectTest extends AbstractHttpClientServerTest
{
    public HttpClientRedirectTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void test_303() throws Exception
    {
        start(new RedirectHandler());

        Response response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/303/localhost/done")
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertFalse(response.getHeaders().containsKey(HttpHeader.LOCATION.asString()));
    }

    @Test
    public void test_303_302() throws Exception
    {
        start(new RedirectHandler());

        Response response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/303/localhost/302/localhost/done")
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertFalse(response.getHeaders().containsKey(HttpHeader.LOCATION.asString()));
    }

    @Test
    public void test_303_302_OnDifferentDestinations() throws Exception
    {
        start(new RedirectHandler());

        Response response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/303/127.0.0.1/302/localhost/done")
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertFalse(response.getHeaders().containsKey(HttpHeader.LOCATION.asString()));
    }

    @Test
    public void test_301() throws Exception
    {
        start(new RedirectHandler());

        Response response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .method(HttpMethod.HEAD)
                .path("/301/localhost/done")
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertFalse(response.getHeaders().containsKey(HttpHeader.LOCATION.asString()));
    }

    @Test
    public void test_301_WithWrongMethod() throws Exception
    {
        start(new RedirectHandler());

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .method(HttpMethod.DELETE)
                    .path("/301/localhost/done")
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            HttpResponseException xx = (HttpResponseException)x.getCause();
            Response response = xx.getResponse();
            Assert.assertNotNull(response);
            Assert.assertEquals(301, response.getStatus());
            Assert.assertTrue(response.getHeaders().containsKey(HttpHeader.LOCATION.asString()));
        }
    }

    @Test
    public void test_307_WithRequestContent() throws Exception
    {
        start(new RedirectHandler());

        byte[] data = new byte[]{0, 1, 2, 3, 4, 5, 6, 7};
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .method(HttpMethod.POST)
                .path("/307/localhost/done")
                .content(new ByteBufferContentProvider(ByteBuffer.wrap(data)))
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertFalse(response.getHeaders().containsKey(HttpHeader.LOCATION.asString()));
        Assert.assertArrayEquals(data, response.getContent());
    }

    @Test
    public void testMaxRedirections() throws Exception
    {
        start(new RedirectHandler());
        client.setMaxRedirects(1);

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .path("/303/localhost/302/localhost/done")
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            HttpResponseException xx = (HttpResponseException)x.getCause();
            Response response = xx.getResponse();
            Assert.assertNotNull(response);
            Assert.assertEquals(302, response.getStatus());
            Assert.assertTrue(response.getHeaders().containsKey(HttpHeader.LOCATION.asString()));
        }
    }

    @Test
    public void test_303_WithConnectionClose_WithBigRequest() throws Exception
    {
        start(new RedirectHandler());

        Response response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/303/localhost/done?close=true")
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertFalse(response.getHeaders().containsKey(HttpHeader.LOCATION.asString()));
    }

    @Test
    public void testDontFollowRedirects() throws Exception
    {
        start(new RedirectHandler());

        Response response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .followRedirects(false)
                .path("/303/localhost/done?close=true")
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertNotNull(response);
        Assert.assertEquals(303, response.getStatus());
        Assert.assertTrue(response.getHeaders().containsKey(HttpHeader.LOCATION.asString()));
    }

    @Test
    public void testRelativeLocation() throws Exception
    {
        start(new RedirectHandler());

        Response response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/303/localhost/done?relative=true")
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertFalse(response.getHeaders().containsKey(HttpHeader.LOCATION.asString()));
    }

    @Test
    public void testAbsoluteURIPathWithSpaces() throws Exception
    {
        start(new RedirectHandler());

        Response response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/303/localhost/a+space?decode=true")
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertFalse(response.getHeaders().containsKey(HttpHeader.LOCATION.asString()));
    }

    @Test
    public void testRelativeURIPathWithSpaces() throws Exception
    {
        start(new RedirectHandler());

        Response response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/303/localhost/a+space?relative=true&decode=true")
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertFalse(response.getHeaders().containsKey(HttpHeader.LOCATION.asString()));
    }

    @Test
    public void testRedirectWithWrongScheme() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setStatus(303);
                response.setHeader("Location", "ssh://localhost:" + connector.getLocalPort() + "/path");
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/path")
                .timeout(5, TimeUnit.SECONDS)
                .send(result ->
                {
                    Assert.assertTrue(result.isFailed());
                    latch.countDown();
                });
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    @Ignore
    public void testRedirectFailed() throws Exception
    {
        // TODO this test is failing with timout after an ISP upgrade??  DNS dependent?
        start(new RedirectHandler());

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .path("/303/doesNotExist/done")
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
        }
        catch (ExecutionException x)
        {
            Assert.assertThat(x.getCause(), Matchers.instanceOf(UnresolvedAddressException.class));
        }
    }

    @Test
    public void test_HEAD_301() throws Exception
    {
        testSameMethodRedirect(HttpMethod.HEAD, HttpStatus.MOVED_PERMANENTLY_301);
    }

    @Test
    public void test_POST_301() throws Exception
    {
        testGETRedirect(HttpMethod.POST, HttpStatus.MOVED_PERMANENTLY_301);
    }

    @Test
    public void test_PUT_301() throws Exception
    {
        testSameMethodRedirect(HttpMethod.PUT, HttpStatus.MOVED_PERMANENTLY_301);
    }

    @Test
    public void test_HEAD_302() throws Exception
    {
        testSameMethodRedirect(HttpMethod.HEAD, HttpStatus.FOUND_302);
    }

    @Test
    public void test_POST_302() throws Exception
    {
        testGETRedirect(HttpMethod.POST, HttpStatus.FOUND_302);
    }

    @Test
    public void test_PUT_302() throws Exception
    {
        testSameMethodRedirect(HttpMethod.PUT, HttpStatus.FOUND_302);
    }

    @Test
    public void test_HEAD_303() throws Exception
    {
        testSameMethodRedirect(HttpMethod.HEAD, HttpStatus.SEE_OTHER_303);
    }

    @Test
    public void test_POST_303() throws Exception
    {
        testGETRedirect(HttpMethod.POST, HttpStatus.SEE_OTHER_303);
    }

    @Test
    public void test_PUT_303() throws Exception
    {
        testGETRedirect(HttpMethod.PUT, HttpStatus.SEE_OTHER_303);
    }

    @Test
    public void test_HEAD_307() throws Exception
    {
        testSameMethodRedirect(HttpMethod.HEAD, HttpStatus.TEMPORARY_REDIRECT_307);
    }

    @Test
    public void test_POST_307() throws Exception
    {
        testSameMethodRedirect(HttpMethod.POST, HttpStatus.TEMPORARY_REDIRECT_307);
    }

    @Test
    public void test_PUT_307() throws Exception
    {
        testSameMethodRedirect(HttpMethod.PUT, HttpStatus.TEMPORARY_REDIRECT_307);
    }

    @Test
    public void testHttpRedirector() throws Exception
    {
        start(new RedirectHandler());
        final HttpRedirector redirector = new HttpRedirector(client);

        org.eclipse.jetty.client.api.Request request1 = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/303/localhost/302/localhost/done")
                .timeout(5, TimeUnit.SECONDS)
                .followRedirects(false);
        ContentResponse response1 = request1.send();

        Assert.assertEquals(303, response1.getStatus());
        Assert.assertTrue(redirector.isRedirect(response1));

        Result result = redirector.redirect(request1, response1);
        org.eclipse.jetty.client.api.Request request2 = result.getRequest();
        Response response2 = result.getResponse();

        Assert.assertEquals(302, response2.getStatus());
        Assert.assertTrue(redirector.isRedirect(response2));

        final CountDownLatch latch = new CountDownLatch(1);
        redirector.redirect(request2, response2, r ->
        {
            Response response3 = r.getResponse();
            Assert.assertEquals(200, response3.getStatus());
            Assert.assertFalse(redirector.isRedirect(response3));
            latch.countDown();
        });
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRedirectWithCorruptedBody() throws Exception
    {
        byte[] bytes = "ok".getBytes(StandardCharsets.UTF_8);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if (target.startsWith("/redirect"))
                {
                    response.setStatus(HttpStatus.SEE_OTHER_303);
                    response.setHeader(HttpHeader.LOCATION.asString(), scheme + "://localhost:" + connector.getLocalPort() + "/ok");
                    // Say that we send gzipped content, but actually don't.
                    response.setHeader(HttpHeader.CONTENT_ENCODING.asString(), "gzip");
                    response.getOutputStream().write("redirect".getBytes(StandardCharsets.UTF_8));
                }
                else
                {
                    response.setStatus(HttpStatus.OK_200);
                    response.getOutputStream().write(bytes);
                }
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/redirect")
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals(bytes, response.getContent());
    }

    private void testSameMethodRedirect(final HttpMethod method, int redirectCode) throws Exception
    {
        testMethodRedirect(method, method, redirectCode);
    }

    private void testGETRedirect(final HttpMethod method, int redirectCode) throws Exception
    {
        testMethodRedirect(method, HttpMethod.GET, redirectCode);
    }

    private void testMethodRedirect(final HttpMethod requestMethod, final HttpMethod redirectMethod, int redirectCode) throws Exception
    {
        start(new RedirectHandler());

        final AtomicInteger passes = new AtomicInteger();
        client.getRequestListeners().add(new org.eclipse.jetty.client.api.Request.Listener.Adapter()
        {
            @Override
            public void onBegin(org.eclipse.jetty.client.api.Request request)
            {
                int pass = passes.incrementAndGet();
                if (pass == 1)
                {
                    if (!requestMethod.is(request.getMethod()))
                        request.abort(new Exception());
                }
                else if (pass == 2)
                {
                    if (!redirectMethod.is(request.getMethod()))
                        request.abort(new Exception());
                }
                else
                {
                    request.abort(new Exception());
                }
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .method(requestMethod)
                .path("/" + redirectCode + "/localhost/done")
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
    }

    private class RedirectHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            try
            {
                String[] paths = target.split("/", 4);

                int status = Integer.parseInt(paths[1]);
                response.setStatus(status);

                String host = paths[2];
                String path = paths[3];
                boolean relative = Boolean.parseBoolean(request.getParameter("relative"));
                String location = relative ? "" : request.getScheme() + "://" + host + ":" + request.getServerPort();
                location += "/" + path;

                if (Boolean.parseBoolean(request.getParameter("decode")))
                    location = URLDecoder.decode(location, "UTF-8");

                response.setHeader("Location", location);

                if (Boolean.parseBoolean(request.getParameter("close")))
                    response.setHeader("Connection", "close");
            }
            catch (NumberFormatException x)
            {
                response.setStatus(200);
                // Echo content back
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
            finally
            {
                baseRequest.setHandled(true);
            }
        }
    }
}
