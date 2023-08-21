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

package org.eclipse.jetty.server;

import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ResponseTest
{
    private Server server;
    private LocalConnector connector;

    @BeforeEach
    public void prepare() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        connector.setIdleTimeout(60000);
        server.addConnector(connector);
    }

    @AfterEach
    public void dispose() throws Exception
    {
        LifeCycle.stop(server);
        connector = null;
    }

    @Test
    public void testGET() throws Exception
    {
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });
        server.start();

        String request = """
                GET /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testServerDateFieldsFrozen() throws Exception
    {
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.getHeaders().add("Temp", "field");
                response.getHeaders().add("Test", "before reset");
                assertThrows(IllegalStateException.class, () -> response.getHeaders().remove(HttpHeader.SERVER));
                assertThrows(IllegalStateException.class, () -> response.getHeaders().remove(HttpHeader.DATE));
                response.getHeaders().remove("Temp");

                response.getHeaders().add("Temp", "field");
                Iterator<HttpField> iterator = response.getHeaders().iterator();
                assertThat(iterator.next().getHeader(), is(HttpHeader.SERVER));
                assertThrows(IllegalStateException.class, iterator::remove);
                assertThat(iterator.next().getHeader(), is(HttpHeader.DATE));
                assertThrows(IllegalStateException.class, iterator::remove);
                assertThat(iterator.next().getName(), is("Test"));
                assertThat(iterator.next().getName(), is("Temp"));
                iterator.remove();
                assertFalse(response.getHeaders().contains("Temp"));
                assertThrows(IllegalStateException.class, () -> response.getHeaders().remove(HttpHeader.SERVER));
                assertFalse(iterator.hasNext());

                ListIterator<HttpField> listIterator = response.getHeaders().listIterator();
                assertThat(listIterator.next().getHeader(), is(HttpHeader.SERVER));
                assertThrows(IllegalStateException.class, listIterator::remove);
                assertThat(listIterator.next().getHeader(), is(HttpHeader.DATE));
                assertThrows(IllegalStateException.class, () -> listIterator.set(new HttpField("Something", "else")));
                listIterator.set(new HttpField(HttpHeader.DATE, "1970-01-01"));
                assertThat(listIterator.previous().getHeader(), is(HttpHeader.DATE));
                assertThrows(IllegalStateException.class, listIterator::remove);
                assertThat(listIterator.previous().getHeader(), is(HttpHeader.SERVER));
                assertThrows(IllegalStateException.class, listIterator::remove);
                assertThat(listIterator.next().getHeader(), is(HttpHeader.SERVER));
                assertThat(listIterator.next().getHeader(), is(HttpHeader.DATE));
                assertThrows(IllegalStateException.class, listIterator::remove);
                listIterator.add(new HttpField("Temp", "value"));
                assertThat(listIterator.previous().getName(), is("Temp"));
                listIterator.remove();
                assertFalse(response.getHeaders().contains("Temp"));

                response.getHeaders().add("Temp", "field");
                response.reset();
                response.getHeaders().add("Test", "after reset");
                callback.succeeded();
                return true;
            }
        });
        server.start();

        String request = """
                GET /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertThat(response.get(HttpHeader.SERVER), notNullValue());
        assertThat(response.get(HttpHeader.DATE), notNullValue());
        assertThat(response.get("Test"), is("after reset"));
    }

    @Test
    public void testRedirectGET() throws Exception
    {
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Response.sendRedirect(request, response, callback, "/somewhere/else");
                return true;
            }
        });
        server.start();

        String request = """
                GET /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_TEMPORARILY_302, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("http://hostname/somewhere/else"));

        request = """
                GET /path HTTP/1.1\r
                Host: hostname\r
                Connection: close\r
                \r
                """;
        response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_TEMPORARILY_302, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("http://hostname/somewhere/else"));
    }

    @Test
    public void testRelativeRedirectGET() throws Exception
    {
        server.getConnectors()[0].getConnectionFactory(HttpConnectionFactory.class)
            .getHttpConfiguration().setRelativeRedirectAllowed(true);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Response.sendRedirect(request, response, callback, "/somewhere/else");
                return true;
            }
        });
        server.start();

        String request = """
                GET /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_TEMPORARILY_302, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("/somewhere/else"));

        request = """
                GET /path HTTP/1.1\r
                Host: hostname\r
                Connection: close\r
                \r
                """;
        response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_TEMPORARILY_302, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("/somewhere/else"));
    }

    @Test
    public void testRedirectPOST() throws Exception
    {
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Response.sendRedirect(request, response, callback, "/somewhere/else");
                return true;
            }
        });
        server.start();

        String request = """
                POST /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_TEMPORARILY_302, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("http://hostname/somewhere/else"));

        request = """
                POST /path HTTP/1.1\r
                Host: hostname\r
                Connection: close\r
                \r
                """;
        response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.SEE_OTHER_303, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("http://hostname/somewhere/else"));
    }

    @Test
    public void testRelativeRedirectPOST() throws Exception
    {
        server.getConnectors()[0].getConnectionFactory(HttpConnectionFactory.class)
            .getHttpConfiguration().setRelativeRedirectAllowed(true);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Response.sendRedirect(request, response, callback, "/somewhere/else");
                return true;
            }
        });
        server.start();

        String request = """
                POST /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_TEMPORARILY_302, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("/somewhere/else"));

        request = """
                POST /path HTTP/1.1\r
                Host: hostname\r
                Connection: close\r
                \r
                """;
        response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.SEE_OTHER_303, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("/somewhere/else"));
    }

    @Test
    public void testXPoweredByDefault() throws Exception
    {
        server.getConnectors()[0].getConnectionFactory(HttpConnectionFactory.class)
            .getHttpConfiguration().setSendXPoweredBy(true);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, true, "Test", callback);
                return true;
            }
        });
        server.start();

        String request = """
                GET /test HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // ensure there are only 1 entry for each of these headers
        List<HttpHeader> expectedHeaders = List.of(HttpHeader.SERVER, HttpHeader.X_POWERED_BY, HttpHeader.DATE, HttpHeader.CONTENT_LENGTH);
        for (HttpHeader expectedHeader: expectedHeaders)
        {
            List<String> actualHeader = response.getValuesList(expectedHeader);
            assertThat(expectedHeader + " exists", actualHeader, is(notNullValue()));
            assertThat(expectedHeader + " header count", actualHeader.size(), is(1));
        }
        assertThat(response.get(HttpHeader.CONTENT_LENGTH), is("4"));
        assertThat(response.get(HttpHeader.X_POWERED_BY), is(HttpConfiguration.SERVER_VERSION));
    }

    @Test
    public void testXPoweredByOverride() throws Exception
    {
        server.getConnectors()[0].getConnectionFactory(HttpConnectionFactory.class)
            .getHttpConfiguration().setSendXPoweredBy(true);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // replace the X-Powered-By value
                response.getHeaders().put(HttpHeader.X_POWERED_BY, "SomeServer");
                Content.Sink.write(response, true, "Test", callback);
                return true;
            }
        });
        server.start();

        String request = """
                GET /test HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // ensure there are only 1 entry for each of these headers
        List<HttpHeader> expectedHeaders = List.of(HttpHeader.SERVER, HttpHeader.X_POWERED_BY, HttpHeader.DATE, HttpHeader.CONTENT_LENGTH);
        for (HttpHeader expectedHeader: expectedHeaders)
        {
            List<String> actualHeader = response.getValuesList(expectedHeader);
            assertThat(expectedHeader + " exists", actualHeader, is(notNullValue()));
            assertThat(expectedHeader + " header count", actualHeader.size(), is(1));
        }
        assertThat(response.get(HttpHeader.CONTENT_LENGTH), is("4"));
        assertThat(response.get(HttpHeader.X_POWERED_BY), is("SomeServer"));
    }

    @Test
    public void testHttpCookieProcessing() throws Exception
    {
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                request.addHttpStreamWrapper(httpStream -> new HttpStream.Wrapper(httpStream)
                {
                    @Override
                    public void prepareResponse(HttpFields.Mutable headers)
                    {
                        super.prepareResponse(headers);
                        for (ListIterator<HttpField> i = headers.listIterator(); i.hasNext();)
                        {
                            HttpField field = i.next();
                            if (field.getHeader() != HttpHeader.SET_COOKIE)
                                continue;

                            HttpCookie cookie = HttpCookieUtils.getSetCookie(field);
                            if (cookie == null)
                                continue;

                            i.set(new HttpCookieUtils.SetCookieHttpField(
                                HttpCookie.build(cookie)
                                    .domain("customized")
                                    .sameSite(HttpCookie.SameSite.LAX)
                                    .build(),
                                request.getConnectionMetaData().getHttpConfiguration().getResponseCookieCompliance()));
                        }
                    }
                });
                response.setStatus(200);
                Response.addCookie(response, HttpCookie.from("name", "test1"));
                response.getHeaders().add(HttpHeader.SET_COOKIE, "other=test2; Domain=wrong; SameSite=wrong; Attr=x");
                Content.Sink.write(response, true, "OK", callback);
                return true;
            }
        });
        server.start();

        String request = """
                POST /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertThat(response.getValuesList(HttpHeader.SET_COOKIE), containsInAnyOrder(
            "name=test1; Domain=customized; SameSite=Lax",
            "other=test2; Domain=customized; SameSite=Lax; Attr=x"));
    }
}
