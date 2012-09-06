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
import java.net.URLEncoder;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.Assert;
import org.junit.Test;

public class HttpClientTest extends AbstractHttpClientServerTest
{
    @Test
    public void testStoppingClosesConnections() throws Exception
    {
        start(new EmptyHandler());

        String scheme = "http";
        String host = "localhost";
        int port = connector.getLocalPort();
        String path = "/";
        Response response = client.GET(scheme + "://" + host + ":" + port + path).get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        HttpDestination destination = (HttpDestination)client.getDestination(scheme, host, port);
        HttpConnection connection = (HttpConnection)destination.getIdleConnections().peek();
        Assert.assertNotNull(connection);

        client.getCookieStore().addCookie(destination, new HttpCookie("foo", "bar", null, path));

        client.stop();

        Assert.assertEquals(0, client.getDestinations().size());
        Assert.assertEquals(0, destination.getIdleConnections().size());
        Assert.assertEquals(0, destination.getActiveConnections().size());
        Assert.assertEquals(0, client.getCookieStore().getCookies(destination, path).size());
        Assert.assertFalse(connection.getEndPoint().isOpen());
    }

    @Test
    public void test_DestinationCount() throws Exception
    {
        start(new EmptyHandler());

        String scheme = "http";
        String host = "localhost";
        int port = connector.getLocalPort();
        client.GET(scheme + "://" + host + ":" + port).get(5, TimeUnit.SECONDS);

        List<Destination> destinations = client.getDestinations();
        Assert.assertNotNull(destinations);
        Assert.assertEquals(1, destinations.size());
        Destination destination = destinations.get(0);
        Assert.assertNotNull(destination);
        Assert.assertEquals(scheme, destination.scheme());
        Assert.assertEquals(host, destination.host());
        Assert.assertEquals(port, destination.port());
    }

    @Test
    public void test_GET_ResponseWithContent() throws Exception
    {
        start(new EmptyHandler());

        Response response = client.GET("http://localhost:" + connector.getLocalPort()).get(5, TimeUnit.SECONDS);

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
    }

    @Test
    public void test_GET_ResponseWithoutContent() throws Exception
    {
        final byte[] data = new byte[]{0, 1, 2, 3, 4, 5, 6, 7};
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.getOutputStream().write(data);
                baseRequest.setHandled(true);
            }
        });

        ContentResponse response = client.GET("http://localhost:" + connector.getLocalPort()).get(5, TimeUnit.SECONDS);

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        byte[] content = response.content();
        Assert.assertArrayEquals(data, content);
    }

    @Test
    public void test_GET_WithParameters_ResponseWithContent() throws Exception
    {
        final String paramName1 = "a";
        final String paramName2 = "b";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setCharacterEncoding("UTF-8");
                ServletOutputStream output = response.getOutputStream();
                String paramValue1 = request.getParameter(paramName1);
                output.write(paramValue1.getBytes("UTF-8"));
                String paramValue2 = request.getParameter(paramName2);
                Assert.assertEquals("", paramValue2);
                output.write("empty".getBytes("UTF-8"));
                baseRequest.setHandled(true);
            }
        });

        String value1 = "\u20AC";
        String paramValue1 = URLEncoder.encode(value1, "UTF-8");
        String query = paramName1 + "=" + paramValue1 + "&" + paramName2;
        ContentResponse response = client.GET("http://localhost:" + connector.getLocalPort() + "/?" + query).get(5, TimeUnit.SECONDS);

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        String content = new String(response.content(), "UTF-8");
        Assert.assertEquals(value1 + "empty", content);
    }

    @Test
    public void test_GET_WithParametersMultiValued_ResponseWithContent() throws Exception
    {
        final String paramName1 = "a";
        final String paramName2 = "b";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setCharacterEncoding("UTF-8");
                ServletOutputStream output = response.getOutputStream();
                String[] paramValues1 = request.getParameterValues(paramName1);
                for (String paramValue : paramValues1)
                    output.write(paramValue.getBytes("UTF-8"));
                String paramValue2 = request.getParameter(paramName2);
                output.write(paramValue2.getBytes("UTF-8"));
                baseRequest.setHandled(true);
            }
        });

        String value11 = "\u20AC";
        String value12 = "\u20AA";
        String value2 = "&";
        String paramValue11 = URLEncoder.encode(value11, "UTF-8");
        String paramValue12 = URLEncoder.encode(value12, "UTF-8");
        String paramValue2 = URLEncoder.encode(value2, "UTF-8");
        String query = paramName1 + "=" + paramValue11 + "&" + paramName1 + "=" + paramValue12 + "&" + paramName2 + "=" + paramValue2;
        ContentResponse response = client.GET("http://localhost:" + connector.getLocalPort() + "/?" + query).get(5, TimeUnit.SECONDS);

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        String content = new String(response.content(), "UTF-8");
        Assert.assertEquals(value11 + value12 + value2, content);
    }
}
