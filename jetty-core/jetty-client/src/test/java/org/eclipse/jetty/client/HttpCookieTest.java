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

package org.eclipse.jetty.client;

import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.HttpCookieStore;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Disabled("fix this test. the problem is on the server side, which caches the cookies so different requests return the same cookies")
public class HttpCookieTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testCookieIsStored(Scenario scenario) throws Exception
    {
        final String name = "foo";
        final String value = "bar";
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response)
            {
                org.eclipse.jetty.server.Response.addCookie(response, new org.eclipse.jetty.http.HttpCookie(name, value));
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();
        String path = "/path";
        String uri = scenario.getScheme() + "://" + host + ":" + port + path;
        Response response = client.GET(uri);
        assertEquals(200, response.getStatus());

        List<HttpCookie> cookies = client.getCookieStore().get(URI.create(uri));
        assertNotNull(cookies);
        assertEquals(1, cookies.size());
        HttpCookie cookie = cookies.get(0);
        assertEquals(name, cookie.getName());
        assertEquals(value, cookie.getValue());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testCookieIsSent(Scenario scenario) throws Exception
    {
        final String name = "foo";
        final String value = "bar";
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response)
            {
                List<org.eclipse.jetty.http.HttpCookie> cookies = Request.getCookies(request);
                assertNotNull(cookies);
                assertEquals(1, cookies.size());
                org.eclipse.jetty.http.HttpCookie cookie = cookies.get(0);
                assertEquals(name, cookie.getName());
                assertEquals(value, cookie.getValue());
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();
        String path = "/path";
        String uri = scenario.getScheme() + "://" + host + ":" + port;
        HttpCookie cookie = new HttpCookie(name, value);
        client.getCookieStore().add(URI.create(uri), cookie);

        Response response = client.GET(scenario.getScheme() + "://" + host + ":" + port + path);
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testCookieWithoutValue(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response)
            {
                response.getHeaders().add("Set-Cookie", "");
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .send();
        assertEquals(200, response.getStatus());
        assertTrue(client.getCookieStore().getCookies().isEmpty());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testPerRequestCookieIsSent(Scenario scenario) throws Exception
    {
        testPerRequestCookieIsSent(scenario, null);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testPerRequestCookieIsSentWithEmptyCookieStore(Scenario scenario) throws Exception
    {
        testPerRequestCookieIsSent(scenario, new HttpCookieStore.Empty());
    }

    private void testPerRequestCookieIsSent(Scenario scenario, CookieStore cookieStore) throws Exception
    {
        final String name = "foo";
        final String value = "bar";
        startServer(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response)
            {
                List<org.eclipse.jetty.http.HttpCookie> cookies = Request.getCookies(request);
                assertNotNull(cookies);
                assertEquals(1, cookies.size());
                org.eclipse.jetty.http.HttpCookie cookie = cookies.get(0);
                assertEquals(name, cookie.getName());
                assertEquals(value, cookie.getValue());
            }
        });
        startClient(scenario, client ->
        {
            if (cookieStore != null)
                client.setCookieStore(cookieStore);
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .cookie(new HttpCookie(name, value))
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testSetCookieWithoutPathRequestURIWithOneSegment(Scenario scenario) throws Exception
    {
        String headerName = "X-Request";
        String cookieName = "a";
        String cookieValue = "1";
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response)
            {
                String target = Request.getPathInContext(request);
                int r = (int)request.getHeaders().getLongField(headerName);
                if ("/foo".equals(target) && r == 0)
                {
                    org.eclipse.jetty.http.HttpCookie cookie = new org.eclipse.jetty.http.HttpCookie(cookieName, cookieValue);
                    org.eclipse.jetty.server.Response.addCookie(response, cookie);
                }
                else
                {
                    List<org.eclipse.jetty.http.HttpCookie> cookies = Request.getCookies(request);
                    switch (target)
                    {
                        case "/", "/foo", "/foo/bar" ->
                        {
                            assertEquals(1, cookies.size(), target);
                            org.eclipse.jetty.http.HttpCookie cookie = cookies.get(0);
                            assertEquals(cookieName, cookie.getName(), target);
                            assertEquals(cookieValue, cookie.getValue(), target);
                        }
                        default -> fail("Unrecognized target: " + target);
                    }
                }
            }
        });

        ContentResponse response = send(client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/foo")
            .headers(headers -> headers.put(headerName, "0"))
            .timeout(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.OK_200, response.getStatus());

        Arrays.asList("/", "/foo", "/foo/bar").forEach(path ->
        {
            ContentResponse r = send(client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .path(path)
                .headers(headers -> headers.put(headerName, "1"))
                .timeout(5, TimeUnit.SECONDS));
            assertEquals(HttpStatus.OK_200, r.getStatus());
        });
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testSetCookieWithoutPathRequestURIWithTwoSegments(Scenario scenario) throws Exception
    {
        String headerName = "X-Request";
        String cookieName = "a";
        String cookieValue = "1";
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response)
            {
                String target = Request.getPathInContext(request);
                int r = (int)request.getHeaders().getLongField(headerName);
                if ("/foo/bar".equals(target) && r == 0)
                {
                    org.eclipse.jetty.http.HttpCookie cookie = new org.eclipse.jetty.http.HttpCookie(cookieName, cookieValue);
                    org.eclipse.jetty.server.Response.addCookie(response, cookie);
                }
                else
                {
                    List<org.eclipse.jetty.http.HttpCookie> cookies = Request.getCookies(request);
                    switch (target)
                    {
                        case "/", "/foo", "/foobar" -> assertEquals(0, cookies.size(), target);
                        case "/foo/", "/foo/bar", "/foo/bar/baz" ->
                        {
                            assertEquals(1, cookies.size(), target);
                            org.eclipse.jetty.http.HttpCookie cookie = cookies.get(0);
                            assertEquals(cookieName, cookie.getName(), target);
                            assertEquals(cookieValue, cookie.getValue(), target);
                        }
                        default -> fail("Unrecognized Target: " + target);
                    }
                }
            }
        });

        ContentResponse response = send(client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/foo/bar")
            .headers(headers -> headers.put(headerName, "0"))
            .timeout(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.OK_200, response.getStatus());

        Arrays.asList("/", "/foo", "/foo/", "/foobar", "/foo/bar", "/foo/bar/baz").forEach(path ->
        {
            ContentResponse r = send(client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .path(path)
                .headers(headers -> headers.put(headerName, "1"))
                .timeout(5, TimeUnit.SECONDS));
            assertEquals(HttpStatus.OK_200, r.getStatus());
        });
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testSetCookieWithLongerPath(Scenario scenario) throws Exception
    {
        String headerName = "X-Request";
        String cookieName = "a";
        String cookieValue = "1";
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response)
            {
                String target = Request.getPathInContext(request);
                int r = (int)request.getHeaders().getLongField(headerName);
                if ("/foo".equals(target) && r == 0)
                {
                    org.eclipse.jetty.http.HttpCookie cookie = new org.eclipse.jetty.http.HttpCookie(cookieName, cookieValue, null, "/foo/bar");
                    org.eclipse.jetty.server.Response.addCookie(response, cookie);
                }
                else
                {
                    List<org.eclipse.jetty.http.HttpCookie> cookies = Request.getCookies(request);
                    switch (target)
                    {
                        case "/", "/foo", "/foo/barbaz" -> assertEquals(0, cookies.size(), target);
                        case "/foo/bar", "/foo/bar/" ->
                        {
                            assertEquals(1, cookies.size(), target);
                            org.eclipse.jetty.http.HttpCookie cookie = cookies.get(0);
                            assertEquals(cookieName, cookie.getName(), target);
                            assertEquals(cookieValue, cookie.getValue(), target);
                        }
                        default -> fail("Unrecognized Target: " + target);
                    }
                }
            }
        });

        ContentResponse response = send(client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/foo")
            .headers(headers -> headers.put(headerName, "0"))
            .timeout(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.OK_200, response.getStatus());

        Arrays.asList("/", "/foo", "/foo/bar", "/foo/bar/", "/foo/barbaz").forEach(path ->
        {
            ContentResponse r = send(client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .path(path)
                .headers(headers -> headers.put(headerName, "1"))
                .timeout(5, TimeUnit.SECONDS));
            assertEquals(HttpStatus.OK_200, r.getStatus());
        });
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testSetCookieWithShorterPath(Scenario scenario) throws Exception
    {
        String headerName = "X-Request";
        String cookieName = "a";
        String cookieValue = "1";
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response)
            {
                String target = Request.getPathInContext(request);
                int r = (int)request.getHeaders().getLongField(headerName);
                if ("/foo/bar".equals(target) && r == 0)
                {
                    org.eclipse.jetty.http.HttpCookie cookie = new org.eclipse.jetty.http.HttpCookie(cookieName, cookieValue, null, "/foo");
                    org.eclipse.jetty.server.Response.addCookie(response, cookie);
                }
                else
                {
                    List<org.eclipse.jetty.http.HttpCookie> cookies = Request.getCookies(request);
                    switch (target)
                    {
                        case "/", "/foobar" -> assertEquals(0, cookies.size(), target);
                        case "/foo", "/foo/", "/foo/bar" ->
                        {
                            assertEquals(1, cookies.size(), target);
                            org.eclipse.jetty.http.HttpCookie cookie = cookies.get(0);
                            assertEquals(cookieName, cookie.getName(), target);
                            assertEquals(cookieValue, cookie.getValue(), target);
                        }
                        default -> fail("Unrecognized Target: " + target);
                    }
                }
            }
        });

        ContentResponse response = send(client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/foo/bar")
            .headers(headers -> headers.put(headerName, "0"))
            .timeout(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.OK_200, response.getStatus());

        Arrays.asList("/", "/foo", "/foo/", "/foobar", "/foo/bar").forEach(path ->
        {
            ContentResponse r = send(client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .path(path)
                .headers(headers -> headers.put(headerName, "1"))
                .timeout(5, TimeUnit.SECONDS));
            assertEquals(HttpStatus.OK_200, r.getStatus());
        });
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testTwoSetCookieWithSameNameSamePath(Scenario scenario) throws Exception
    {
        String headerName = "X-Request";
        String cookieName = "a";
        String cookieValue1 = "1";
        String cookieValue2 = "2";
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response)
            {
                String target = Request.getPathInContext(request);
                int r = (int)request.getHeaders().getLongField(headerName);
                if ("/foo".equals(target) && r == 0)
                {
                    org.eclipse.jetty.http.HttpCookie cookie = new org.eclipse.jetty.http.HttpCookie(cookieName, cookieValue1, null, "/foo");
                    org.eclipse.jetty.server.Response.addCookie(response, cookie);
                    cookie = new org.eclipse.jetty.http.HttpCookie(cookieName, cookieValue2, null, "/foo");
                    org.eclipse.jetty.server.Response.addCookie(response, cookie);
                }
                else
                {
                    List<org.eclipse.jetty.http.HttpCookie> cookies = Request.getCookies(request);
                    switch (target)
                    {
                        case "/" -> assertEquals(0, cookies.size(), target);
                        case "/foo", "/foo/bar" ->
                        {
                            assertEquals(1, cookies.size(), target);
                            org.eclipse.jetty.http.HttpCookie cookie = cookies.get(0);
                            assertEquals(cookieName, cookie.getName(), target);
                            assertEquals(cookieValue2, cookie.getValue(), target);
                        }
                        default -> fail("Unrecognized Target: " + target);
                    }
                }
            }
        });

        ContentResponse response = send(client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/foo")
            .headers(headers -> headers.put(headerName, "0"))
            .timeout(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.OK_200, response.getStatus());

        Arrays.asList("/", "/foo", "/foo/bar").forEach(path ->
        {
            ContentResponse r = send(client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .path(path)
                .headers(headers -> headers.put(headerName, "1"))
                .timeout(5, TimeUnit.SECONDS));
            assertEquals(HttpStatus.OK_200, r.getStatus());
        });
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testTwoSetCookieWithSameNameDifferentPath(Scenario scenario) throws Exception
    {
        String headerName = "X-Request";
        String cookieName = "a";
        String cookieValue1 = "1";
        String cookieValue2 = "2";
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response)
            {
                String target = Request.getPathInContext(request);
                int r = (int)request.getHeaders().getLongField(headerName);
                if ("/foo".equals(target) && r == 0)
                {
                    org.eclipse.jetty.http.HttpCookie cookie = new org.eclipse.jetty.http.HttpCookie(cookieName, cookieValue1, null, "/foo");
                    org.eclipse.jetty.server.Response.addCookie(response, cookie);
                    cookie = new org.eclipse.jetty.http.HttpCookie(cookieName, cookieValue2, null, "/bar");
                    org.eclipse.jetty.server.Response.addCookie(response, cookie);
                }
                else
                {
                    List<org.eclipse.jetty.http.HttpCookie> cookies = Request.getCookies(request);
                    switch (target)
                    {
                        case "/" -> assertEquals(0, cookies.size(), target);
                        case "/foo", "/foo/bar" ->
                        {
                            assertEquals(1, cookies.size(), target);
                            org.eclipse.jetty.http.HttpCookie cookie1 = cookies.get(0);
                            assertEquals(cookieName, cookie1.getName(), target);
                            assertEquals(cookieValue1, cookie1.getValue(), target);
                        }
                        case "/bar", "/bar/foo" ->
                        {
                            assertEquals(1, cookies.size(), target);
                            org.eclipse.jetty.http.HttpCookie cookie2 = cookies.get(0);
                            assertEquals(cookieName, cookie2.getName(), target);
                            assertEquals(cookieValue2, cookie2.getValue(), target);
                        }
                        default -> fail("Unrecognized Target: " + target);
                    }
                }
            }
        });

        ContentResponse response = send(client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/foo")
            .headers(headers -> headers.put(headerName, "0"))
            .timeout(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.OK_200, response.getStatus());

        Arrays.asList("/", "/foo", "/foo/bar", "/bar", "/bar/foo").forEach(path ->
        {
            ContentResponse r = send(client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .path(path)
                .headers(headers -> headers.put(headerName, "1"))
                .timeout(5, TimeUnit.SECONDS));
            assertEquals(HttpStatus.OK_200, r.getStatus());
        });
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testTwoSetCookieWithSameNamePath1PrefixOfPath2(Scenario scenario) throws Exception
    {
        String headerName = "X-Request";
        String cookieName = "a";
        String cookieValue1 = "1";
        String cookieValue2 = "2";
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response)
            {
                String target = Request.getPathInContext(request);
                int r = (int)request.getHeaders().getLongField(headerName);
                if ("/foo".equals(target) && r == 0)
                {
                    org.eclipse.jetty.http.HttpCookie cookie = new org.eclipse.jetty.http.HttpCookie(cookieName, cookieValue1, null, "/foo");
                    org.eclipse.jetty.server.Response.addCookie(response, cookie);
                    cookie = new org.eclipse.jetty.http.HttpCookie(cookieName, cookieValue2, null, "/foo/bar");
                    org.eclipse.jetty.server.Response.addCookie(response, cookie);
                }
                else
                {
                    List<org.eclipse.jetty.http.HttpCookie> cookies = Request.getCookies(request);
                    switch (target)
                    {
                        case "/" -> assertEquals(0, cookies.size(), target);
                        case "/foo" ->
                        {
                            assertEquals(1, cookies.size(), target);
                            org.eclipse.jetty.http.HttpCookie cookie = cookies.get(0);
                            assertEquals(cookieName, cookie.getName(), target);
                            assertEquals(cookieValue1, cookie.getValue(), target);
                        }
                        case "/foo/bar" ->
                        {
                            assertEquals(2, cookies.size(), target);
                            org.eclipse.jetty.http.HttpCookie cookie1 = cookies.get(0);
                            org.eclipse.jetty.http.HttpCookie cookie2 = cookies.get(1);
                            assertEquals(cookieName, cookie1.getName(), target);
                            assertEquals(cookieName, cookie2.getName(), target);
                            Set<String> values = new HashSet<>();
                            values.add(cookie1.getValue());
                            values.add(cookie2.getValue());
                            assertThat(target, values, containsInAnyOrder(cookieValue1, cookieValue2));
                        }
                        default -> fail("Unrecognized Target: " + target);
                    }
                }
            }
        });

        ContentResponse response = send(client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/foo")
            .headers(headers -> headers.put(headerName, "0"))
            .timeout(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.OK_200, response.getStatus());

        Arrays.asList("/", "/foo", "/foo/bar").forEach(path ->
        {
            ContentResponse r = send(client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .path(path)
                .headers(headers -> headers.put(headerName, "1"))
                .timeout(5, TimeUnit.SECONDS));
            assertEquals(HttpStatus.OK_200, r.getStatus());
        });
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testCookiePathWithTrailingSlash(Scenario scenario) throws Exception
    {
        String headerName = "X-Request";
        String cookieName = "a";
        String cookieValue = "1";
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response)
            {
                String target = Request.getPathInContext(request);
                int r = (int)request.getHeaders().getLongField(headerName);
                if ("/foo/bar".equals(target) && r == 0)
                {
                    org.eclipse.jetty.http.HttpCookie cookie = new org.eclipse.jetty.http.HttpCookie(cookieName, cookieValue, null, "/foo/");
                    org.eclipse.jetty.server.Response.addCookie(response, cookie);
                }
                else
                {
                    List<org.eclipse.jetty.http.HttpCookie> cookies = Request.getCookies(request);
                    switch (target)
                    {
                        case "/", "/foo", "/foobar" -> assertEquals(0, cookies.size(), target);
                        case "/foo/", "/foo/bar" ->
                        {
                            assertEquals(1, cookies.size(), target);
                            org.eclipse.jetty.http.HttpCookie cookie = cookies.get(0);
                            assertEquals(cookieName, cookie.getName(), target);
                            assertEquals(cookieValue, cookie.getValue(), target);
                        }
                        default -> fail("Unrecognized Target: " + target);
                    }
                }
            }
        });

        ContentResponse response = send(client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/foo/bar")
            .headers(headers -> headers.put(headerName, "0"))
            .timeout(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.OK_200, response.getStatus());

        Arrays.asList("/", "/foo", "/foo/", "/foobar", "/foo/bar").forEach(path ->
        {
            ContentResponse r = send(client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .path(path)
                .headers(headers -> headers.put(headerName, "1"))
                .timeout(5, TimeUnit.SECONDS));
            assertEquals(HttpStatus.OK_200, r.getStatus());
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
