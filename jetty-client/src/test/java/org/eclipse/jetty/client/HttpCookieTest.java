//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import java.net.HttpCookie;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class HttpCookieTest extends AbstractHttpClientServerTest
{
    private static final Cookie[] EMPTY_COOKIES = new Cookie[0];

    public HttpCookieTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void test_CookieIsStored() throws Exception
    {
        final String name = "foo";
        final String value = "bar";
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.addCookie(new Cookie(name, value));
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();
        String path = "/path";
        String uri = scheme + "://" + host + ":" + port + path;
        Response response = client.GET(uri);
        Assert.assertEquals(200, response.getStatus());

        List<HttpCookie> cookies = client.getCookieStore().get(URI.create(uri));
        Assert.assertNotNull(cookies);
        Assert.assertEquals(1, cookies.size());
        HttpCookie cookie = cookies.get(0);
        Assert.assertEquals(name, cookie.getName());
        Assert.assertEquals(value, cookie.getValue());
    }

    @Test
    public void test_CookieIsSent() throws Exception
    {
        final String name = "foo";
        final String value = "bar";
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                Cookie[] cookies = request.getCookies();
                Assert.assertNotNull(cookies);
                Assert.assertEquals(1, cookies.length);
                Cookie cookie = cookies[0];
                Assert.assertEquals(name, cookie.getName());
                Assert.assertEquals(value, cookie.getValue());
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();
        String path = "/path";
        String uri = scheme + "://" + host + ":" + port;
        HttpCookie cookie = new HttpCookie(name, value);
        client.getCookieStore().add(URI.create(uri), cookie);

        Response response = client.GET(scheme + "://" + host + ":" + port + path);
        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void test_CookieWithoutValue() throws Exception
    {
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.addHeader("Set-Cookie", "");
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(client.getCookieStore().getCookies().isEmpty());
    }

    @Test
    public void test_PerRequestCookieIsSent() throws Exception
    {
        final String name = "foo";
        final String value = "bar";
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                Cookie[] cookies = request.getCookies();
                Assert.assertNotNull(cookies);
                Assert.assertEquals(1, cookies.length);
                Cookie cookie = cookies[0];
                Assert.assertEquals(name, cookie.getName());
                Assert.assertEquals(value, cookie.getValue());
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .cookie(new HttpCookie(name, value))
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void test_SetCookieWithoutPath_RequestURIWithOneSegment() throws Exception
    {
        String headerName = "X-Request";
        String cookieName = "a";
        String cookieValue = "1";
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                int r = request.getIntHeader(headerName);
                if ("/foo".equals(target) && r == 0)
                {
                    Cookie cookie = new Cookie(cookieName, cookieValue);
                    response.addCookie(cookie);
                }
                else
                {
                    Cookie[] cookies = Optional.ofNullable(request.getCookies()).orElse(EMPTY_COOKIES);
                    switch (target)
                    {
                        case "/":
                        case "/foo":
                        case "/foo/bar":
                            Assert.assertEquals(target, 1, cookies.length);
                            Cookie cookie = cookies[0];
                            Assert.assertEquals(target, cookieName, cookie.getName());
                            Assert.assertEquals(target, cookieValue, cookie.getValue());
                            break;
                        default:
                            Assert.fail();
                    }
                }
            }
        });

        ContentResponse response = send(client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/foo")
                .header(headerName, "0")
                .timeout(5, TimeUnit.SECONDS));
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        Arrays.asList("/", "/foo", "/foo/bar").forEach(path ->
        {
            ContentResponse r = send(client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .path(path)
                    .header(headerName, "1")
                    .timeout(5, TimeUnit.SECONDS));
            Assert.assertEquals(HttpStatus.OK_200, r.getStatus());
        });
    }

    @Test
    public void test_SetCookieWithoutPath_RequestURIWithTwoSegments() throws Exception
    {
        String headerName = "X-Request";
        String cookieName = "a";
        String cookieValue = "1";
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                int r = request.getIntHeader(headerName);
                if ("/foo/bar".equals(target) && r == 0)
                {
                    Cookie cookie = new Cookie(cookieName, cookieValue);
                    response.addCookie(cookie);
                }
                else
                {
                    Cookie[] cookies = Optional.ofNullable(request.getCookies()).orElse(EMPTY_COOKIES);
                    switch (target)
                    {
                        case "/":
                        case "/foo":
                        case "/foobar":
                            Assert.assertEquals(target, 0, cookies.length);
                            break;
                        case "/foo/":
                        case "/foo/bar":
                        case "/foo/bar/baz":
                            Assert.assertEquals(target, 1, cookies.length);
                            Cookie cookie = cookies[0];
                            Assert.assertEquals(target, cookieName, cookie.getName());
                            Assert.assertEquals(target, cookieValue, cookie.getValue());
                            break;
                        default:
                            Assert.fail();
                    }
                }
            }
        });

        ContentResponse response = send(client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/foo/bar")
                .header(headerName, "0")
                .timeout(5, TimeUnit.SECONDS));
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        Arrays.asList("/", "/foo", "/foo/", "/foobar", "/foo/bar", "/foo/bar/baz").forEach(path ->
        {
            ContentResponse r = send(client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .path(path)
                    .header(headerName, "1")
                    .timeout(5, TimeUnit.SECONDS));
            Assert.assertEquals(HttpStatus.OK_200, r.getStatus());
        });
    }

    @Test
    public void test_SetCookieWithLongerPath() throws Exception
    {
        String headerName = "X-Request";
        String cookieName = "a";
        String cookieValue = "1";
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                int r = request.getIntHeader(headerName);
                if ("/foo".equals(target) && r == 0)
                {
                    Cookie cookie = new Cookie(cookieName, cookieValue);
                    cookie.setPath("/foo/bar");
                    response.addCookie(cookie);
                }
                else
                {
                    Cookie[] cookies = Optional.ofNullable(request.getCookies()).orElse(EMPTY_COOKIES);
                    switch (target)
                    {
                        case "/":
                        case "/foo":
                        case "/foo/barbaz":
                            Assert.assertEquals(target, 0, cookies.length);
                            break;
                        case "/foo/bar":
                        case "/foo/bar/":
                            Assert.assertEquals(target, 1, cookies.length);
                            Cookie cookie = cookies[0];
                            Assert.assertEquals(target, cookieName, cookie.getName());
                            Assert.assertEquals(target, cookieValue, cookie.getValue());
                            break;
                        default:
                            Assert.fail();
                    }
                }
            }
        });

        ContentResponse response = send(client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/foo")
                .header(headerName, "0")
                .timeout(5, TimeUnit.SECONDS));
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        Arrays.asList("/", "/foo", "/foo/bar", "/foo/bar/", "/foo/barbaz").forEach(path ->
        {
            ContentResponse r = send(client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .path(path)
                    .header(headerName, "1")
                    .timeout(5, TimeUnit.SECONDS));
            Assert.assertEquals(HttpStatus.OK_200, r.getStatus());
        });
    }

    @Test
    public void test_SetCookieWithShorterPath() throws Exception
    {
        String headerName = "X-Request";
        String cookieName = "a";
        String cookieValue = "1";
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                int r = request.getIntHeader(headerName);
                if ("/foo/bar".equals(target) && r == 0)
                {
                    Cookie cookie = new Cookie(cookieName, cookieValue);
                    cookie.setPath("/foo");
                    response.addCookie(cookie);
                }
                else
                {
                    Cookie[] cookies = Optional.ofNullable(request.getCookies()).orElse(EMPTY_COOKIES);
                    switch (target)
                    {
                        case "/":
                        case "/foobar":
                            Assert.assertEquals(target, 0, cookies.length);
                            break;
                        case "/foo":
                        case "/foo/":
                        case "/foo/bar":
                            Assert.assertEquals(target, 1, cookies.length);
                            Cookie cookie = cookies[0];
                            Assert.assertEquals(target, cookieName, cookie.getName());
                            Assert.assertEquals(target, cookieValue, cookie.getValue());
                            break;
                        default:
                            Assert.fail();
                    }
                }
            }
        });

        ContentResponse response = send(client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/foo/bar")
                .header(headerName, "0")
                .timeout(5, TimeUnit.SECONDS));
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        Arrays.asList("/", "/foo", "/foo/", "/foobar", "/foo/bar").forEach(path ->
        {
            ContentResponse r = send(client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .path(path)
                    .header(headerName, "1")
                    .timeout(5, TimeUnit.SECONDS));
            Assert.assertEquals(HttpStatus.OK_200, r.getStatus());
        });
    }

    @Test
    public void test_TwoSetCookieWithSameNameSamePath() throws Exception
    {
        String headerName = "X-Request";
        String cookieName = "a";
        String cookieValue1 = "1";
        String cookieValue2 = "2";
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                int r = request.getIntHeader(headerName);
                if ("/foo".equals(target) && r == 0)
                {
                    Cookie cookie = new Cookie(cookieName, cookieValue1);
                    cookie.setPath("/foo");
                    response.addCookie(cookie);
                    cookie = new Cookie(cookieName, cookieValue2);
                    cookie.setPath("/foo");
                    response.addCookie(cookie);
                }
                else
                {
                    Cookie[] cookies = Optional.ofNullable(request.getCookies()).orElse(EMPTY_COOKIES);
                    switch (target)
                    {
                        case "/":
                            Assert.assertEquals(target, 0, cookies.length);
                            break;
                        case "/foo":
                        case "/foo/bar":
                            Assert.assertEquals(target, 1, cookies.length);
                            Cookie cookie = cookies[0];
                            Assert.assertEquals(target, cookieName, cookie.getName());
                            Assert.assertEquals(target, cookieValue2, cookie.getValue());
                            break;
                        default:
                            Assert.fail();
                    }
                }
            }
        });

        ContentResponse response = send(client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/foo")
                .header(headerName, "0")
                .timeout(5, TimeUnit.SECONDS));
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        Arrays.asList("/", "/foo", "/foo/bar").forEach(path ->
        {
            ContentResponse r = send(client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .path(path)
                    .header(headerName, "1")
                    .timeout(5, TimeUnit.SECONDS));
            Assert.assertEquals(HttpStatus.OK_200, r.getStatus());
        });
    }

    @Test
    public void test_TwoSetCookieWithSameNameDifferentPath() throws Exception
    {
        String headerName = "X-Request";
        String cookieName = "a";
        String cookieValue1 = "1";
        String cookieValue2 = "2";
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                int r = request.getIntHeader(headerName);
                if ("/foo".equals(target) && r == 0)
                {
                    Cookie cookie = new Cookie(cookieName, cookieValue1);
                    cookie.setPath("/foo");
                    response.addCookie(cookie);
                    cookie = new Cookie(cookieName, cookieValue2);
                    cookie.setPath("/bar");
                    response.addCookie(cookie);
                }
                else
                {
                    Cookie[] cookies = Optional.ofNullable(request.getCookies()).orElse(EMPTY_COOKIES);
                    switch (target)
                    {
                        case "/":
                            Assert.assertEquals(target, 0, cookies.length);
                            break;
                        case "/foo":
                        case "/foo/bar":
                            Assert.assertEquals(target, 1, cookies.length);
                            Cookie cookie1 = cookies[0];
                            Assert.assertEquals(target, cookieName, cookie1.getName());
                            Assert.assertEquals(target, cookieValue1, cookie1.getValue());
                            break;
                        case "/bar":
                        case "/bar/foo":
                            Assert.assertEquals(target, 1, cookies.length);
                            Cookie cookie2 = cookies[0];
                            Assert.assertEquals(target, cookieName, cookie2.getName());
                            Assert.assertEquals(target, cookieValue2, cookie2.getValue());
                            break;
                        default:
                            Assert.fail();
                    }
                }
            }
        });

        ContentResponse response = send(client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/foo")
                .header(headerName, "0")
                .timeout(5, TimeUnit.SECONDS));
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        Arrays.asList("/", "/foo", "/foo/bar", "/bar", "/bar/foo").forEach(path ->
        {
            ContentResponse r = send(client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .path(path)
                    .header(headerName, "1")
                    .timeout(5, TimeUnit.SECONDS));
            Assert.assertEquals(HttpStatus.OK_200, r.getStatus());
        });
    }

    @Test
    public void test_TwoSetCookieWithSameNamePath1PrefixOfPath2() throws Exception
    {
        String headerName = "X-Request";
        String cookieName = "a";
        String cookieValue1 = "1";
        String cookieValue2 = "2";
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                int r = request.getIntHeader(headerName);
                if ("/foo".equals(target) && r == 0)
                {
                    Cookie cookie = new Cookie(cookieName, cookieValue1);
                    cookie.setPath("/foo");
                    response.addCookie(cookie);
                    cookie = new Cookie(cookieName, cookieValue2);
                    cookie.setPath("/foo/bar");
                    response.addCookie(cookie);
                }
                else
                {
                    Cookie[] cookies = Optional.ofNullable(request.getCookies()).orElse(EMPTY_COOKIES);
                    switch (target)
                    {
                        case "/":
                            Assert.assertEquals(target, 0, cookies.length);
                            break;
                        case "/foo":
                            Assert.assertEquals(target, 1, cookies.length);
                            Cookie cookie = cookies[0];
                            Assert.assertEquals(target, cookieName, cookie.getName());
                            Assert.assertEquals(target, cookieValue1, cookie.getValue());
                            break;
                        case "/foo/bar":
                            Assert.assertEquals(target, 2, cookies.length);
                            Cookie cookie1 = cookies[0];
                            Cookie cookie2 = cookies[1];
                            Assert.assertEquals(target, cookieName, cookie1.getName());
                            Assert.assertEquals(target, cookieName, cookie2.getName());
                            Set<String> values = new HashSet<>();
                            values.add(cookie1.getValue());
                            values.add(cookie2.getValue());
                            Assert.assertThat(target, values, Matchers.containsInAnyOrder(cookieValue1, cookieValue2));
                            break;
                        default:
                            Assert.fail();
                    }
                }
            }
        });

        ContentResponse response = send(client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/foo")
                .header(headerName, "0")
                .timeout(5, TimeUnit.SECONDS));
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        Arrays.asList("/", "/foo", "/foo/bar").forEach(path ->
        {
            ContentResponse r = send(client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .path(path)
                    .header(headerName, "1")
                    .timeout(5, TimeUnit.SECONDS));
            Assert.assertEquals(HttpStatus.OK_200, r.getStatus());
        });
    }

    @Test
    public void test_CookiePathWithTrailingSlash() throws Exception
    {
        String headerName = "X-Request";
        String cookieName = "a";
        String cookieValue = "1";
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                int r = request.getIntHeader(headerName);
                if ("/foo/bar".equals(target) && r == 0)
                {
                    Cookie cookie = new Cookie(cookieName, cookieValue);
                    cookie.setPath("/foo/");
                    response.addCookie(cookie);
                }
                else
                {
                    Cookie[] cookies = Optional.ofNullable(request.getCookies()).orElse(EMPTY_COOKIES);
                    switch (target)
                    {
                        case "/":
                        case "/foo":
                        case "/foobar":
                            Assert.assertEquals(target, 0, cookies.length);
                            break;
                        case "/foo/":
                        case "/foo/bar":
                            Assert.assertEquals(target, 1, cookies.length);
                            Cookie cookie = cookies[0];
                            Assert.assertEquals(target, cookieName, cookie.getName());
                            Assert.assertEquals(target, cookieValue, cookie.getValue());
                            break;
                        default:
                            Assert.fail();
                    }
                }
            }
        });

        ContentResponse response = send(client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/foo/bar")
                .header(headerName, "0")
                .timeout(5, TimeUnit.SECONDS));
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        Arrays.asList("/", "/foo", "/foo/", "/foobar", "/foo/bar").forEach(path ->
        {
            ContentResponse r = send(client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .path(path)
                    .header(headerName, "1")
                    .timeout(5, TimeUnit.SECONDS));
            Assert.assertEquals(HttpStatus.OK_200, r.getStatus());
        });
    }

    private ContentResponse send(org.eclipse.jetty.client.api.Request request)
    {
        try
        {
            return request.send();
        }
        catch (RuntimeException x)
        {
            throw x;
        }
        catch (Exception x)
        {
            throw new RuntimeException(x);
        }
    }
}
