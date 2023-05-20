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

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpField;
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
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
    public void testRedirectGET() throws Exception
    {
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
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
            public boolean handle(Request request, Response response, Callback callback) throws Exception
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
            public boolean handle(Request request, Response response, Callback callback) throws Exception
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
            public boolean handle(Request request, Response response, Callback callback) throws Exception
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
    public void testHttpFieldProcessor() throws Exception
    {
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                response.addHttpFieldProcessor(field ->
                    (field.is("TestA"))
                        ? new HttpField.LongValueHttpField(field.getName(), field.getLongValue() + 1)
                        : field);
                response.addHttpFieldProcessor(field ->
                    (field.is("TestB"))
                        ? new HttpField.LongValueHttpField("TestA", field.getLongValue() * 2)
                        : field);
                response.addHttpFieldProcessor(field ->
                    (field.is("TestC"))
                        ? null
                        : field);

                response.setStatus(200);
                response.getHeaders().add("TestA", "1");
                response.getHeaders().add("TestB", "42");
                response.getHeaders().add("TestC", "999");
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
        assertThat(response.getValuesList("TestA"), contains("2", "85"));
        assertFalse(response.contains("TestB"));
        assertFalse(response.contains("TestC"));
    }

    @Test
    public void testHttpCookieProcessor() throws Exception
    {
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                response.addHttpFieldProcessor(field ->
                    {
                        HttpCookie cookie = HttpCookieUtils.getSetCookie(field);
                        if (cookie == null)
                            return field;

                        return new HttpCookieUtils.SetCookieHttpField(
                            HttpCookie.build(cookie)
                                .domain("customized")
                                .sameSite(HttpCookie.SameSite.LAX)
                                .build(),
                            request.getConnectionMetaData().getHttpConfiguration().getResponseCookieCompliance());
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
