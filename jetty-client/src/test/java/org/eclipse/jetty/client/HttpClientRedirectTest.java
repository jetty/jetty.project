//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.ByteBufferContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.fail;

public class HttpClientRedirectTest extends AbstractHttpClientServerTest
{
    public HttpClientRedirectTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Before
    public void prepare() throws Exception
    {
        start(new RedirectHandler());
    }

    @Test
    public void test_303() throws Exception
    {
        Response response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/303/localhost/done")
                .send().get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        Assert.assertFalse(response.headers().containsKey(HttpHeader.LOCATION.asString()));
    }

    @Test
    public void test_303_302() throws Exception
    {
        Response response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/303/localhost/302/localhost/done")
                .send().get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        Assert.assertFalse(response.headers().containsKey(HttpHeader.LOCATION.asString()));
    }

    @Test
    public void test_303_302_OnDifferentDestinations() throws Exception
    {
        Response response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/303/127.0.0.1/302/localhost/done")
                .send().get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        Assert.assertFalse(response.headers().containsKey(HttpHeader.LOCATION.asString()));
    }

    @Test
    public void test_301() throws Exception
    {
        Response response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .method(HttpMethod.HEAD)
                .path("/301/localhost/done")
                .send().get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        Assert.assertFalse(response.headers().containsKey(HttpHeader.LOCATION.asString()));
    }

    @Test
    public void test_301_WithWrongMethod() throws Exception
    {
        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .method(HttpMethod.POST)
                    .path("/301/localhost/done")
                    .send().get(5, TimeUnit.SECONDS);
            fail();
        }
        catch (ExecutionException x)
        {
            HttpResponseException xx = (HttpResponseException)x.getCause();
            Response response = xx.getResponse();
            Assert.assertNotNull(response);
            Assert.assertEquals(301, response.status());
            Assert.assertTrue(response.headers().containsKey(HttpHeader.LOCATION.asString()));
        }
    }

    @Test
    public void test_307_WithRequestContent() throws Exception
    {
        byte[] data = new byte[]{0, 1, 2, 3, 4, 5, 6, 7};
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .method(HttpMethod.POST)
                .path("/307/localhost/done")
                .content(new ByteBufferContentProvider(ByteBuffer.wrap(data)))
                .send().get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        Assert.assertFalse(response.headers().containsKey(HttpHeader.LOCATION.asString()));
        Assert.assertArrayEquals(data, response.content());
    }

    @Test
    public void testMaxRedirections() throws Exception
    {
        client.setMaxRedirects(1);

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .path("/303/localhost/302/localhost/done")
                    .send().get(5, TimeUnit.SECONDS);
            fail();
        }
        catch (ExecutionException x)
        {
            HttpResponseException xx = (HttpResponseException)x.getCause();
            Response response = xx.getResponse();
            Assert.assertNotNull(response);
            Assert.assertEquals(302, response.status());
            Assert.assertTrue(response.headers().containsKey(HttpHeader.LOCATION.asString()));
        }
    }

    @Test
    public void test_303_WithConnectionClose_WithBigRequest() throws Exception
    {
        Response response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/303/localhost/done?close=true")
                .send().get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        Assert.assertFalse(response.headers().containsKey(HttpHeader.LOCATION.asString()));
    }

    @Test
    public void testDontFollowRedirects() throws Exception
    {
        Response response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .followRedirects(false)
                .path("/303/localhost/done?close=true")
                .send().get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(303, response.status());
        Assert.assertTrue(response.headers().containsKey(HttpHeader.LOCATION.asString()));
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
                response.setHeader("Location", request.getScheme() + "://" + host + ":" + request.getServerPort() + "/" + paths[3]);

                String close = request.getParameter("close");
                if (Boolean.parseBoolean(close))
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
