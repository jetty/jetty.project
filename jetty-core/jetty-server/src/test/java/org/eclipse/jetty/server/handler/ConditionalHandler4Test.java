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

package org.eclipse.jetty.server.handler;

import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

public class ConditionalHandler4Test
{
    private Server _server;
    private LocalConnector _connector;
    private TestHandler _testHandler;
    private HelloHandler _helloHandler;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().addCustomizer(new ForwardedRequestCustomizer());
        _server.addConnector(_connector);
        _testHandler = new TestHandler();
        _helloHandler = new HelloHandler();
        _server.setHandler(_testHandler);
        _testHandler.setHandler(_helloHandler);
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testMethod() throws Exception
    {
        _testHandler.includeMethod("GET");
        _testHandler.excludeMethod("POST");
        _server.start();
        String response = _connector.getResponse("GET / HTTP/1.0\n\n");
        assertThat(response, containsString("200 OK"));
        assertThat(response, containsString("Test: applied"));

        response = _connector.getResponse("POST /foo HTTP/1.0\n\n");
        assertThat(response, containsString("200 OK"));
        assertThat(response, not(containsString("Test: applied")));
    }

    @Test
    public void testPath() throws Exception
    {
        _testHandler.includePath("/foo/*");
        _testHandler.excludePath("/foo/bar");
        _server.start();
        String response = _connector.getResponse("GET /foo HTTP/1.0\n\n");
        assertThat(response, containsString("200 OK"));
        assertThat(response, containsString("Test: applied"));

        response = _connector.getResponse("POST /foo/bar HTTP/1.0\n\n");
        assertThat(response, containsString("200 OK"));
        assertThat(response, not(containsString("Test: applied")));
    }

    @Test
    public void testInet() throws Exception
    {
        _testHandler.includeInetAddress("192.168.128.0-192.168.128.128");
        _testHandler.excludeInetAddress("192.168.128.30-192.168.128.39");
        _server.start();
        String response = _connector.getResponse("""
            GET /foo HTTP/1.0
            Forwarded: for=192.168.128.1
            
            """);
        assertThat(response, containsString("200 OK"));
        assertThat(response, containsString("Test: applied"));
        response = _connector.getResponse("""
            GET /foo HTTP/1.0
            Forwarded: for=192.168.128.31
            
            """);
        assertThat(response, containsString("200 OK"));
        assertThat(response, not(containsString("Test: applied")));
    }

    @Test
    public void testMethodPath() throws Exception
    {
        _testHandler.includeMethod("GET");
        _testHandler.excludeMethod("POST");
        _testHandler.includePath("/foo/*");
        _testHandler.excludePath("/foo/bar");
        _server.start();
        String response = _connector.getResponse("GET /foo HTTP/1.0\n\n");
        assertThat(response, containsString("200 OK"));
        assertThat(response, containsString("Test: applied"));

        response = _connector.getResponse("GET /foo/bar HTTP/1.0\n\n");
        assertThat(response, containsString("200 OK"));
        assertThat(response, not(containsString("Test: applied")));

        response = _connector.getResponse("POST /foo HTTP/1.0\n\n");
        assertThat(response, containsString("200 OK"));
        assertThat(response, not(containsString("Test: applied")));

        response = _connector.getResponse("POST /foo/bar HTTP/1.0\n\n");
        assertThat(response, containsString("200 OK"));
        assertThat(response, not(containsString("Test: applied")));
    }

    public static class TestHandler extends ConditionalHandler4
    {
        TestHandler()
        {
            super(true);
        }

        @Override
        public boolean doHandle(Request request, Response response, Callback callback) throws Exception
        {
            response.getHeaders().put("Test", "applied");
            return super.doHandle(request, response, callback);
        }
    }
}
