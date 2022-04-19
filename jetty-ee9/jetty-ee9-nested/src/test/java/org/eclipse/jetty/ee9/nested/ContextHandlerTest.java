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

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;
import java.io.StringReader;
import java.util.Properties;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

public class ContextHandlerTest
{
    Server _server;
    ContextHandler _contextHandler;
    LocalConnector _connector;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        Handler.Collection handlers = new Handler.Collection();
        _server.setHandler(handlers);

        _contextHandler = new ContextHandler();
        handlers.setHandlers(_contextHandler.getCoreContextHandler(), new DefaultHandler());
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testHello() throws Exception
    {
        _contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setStatus(200);
                response.setContentType("text/plain");
                response.getOutputStream().print("Hello\n");
            }
        });
        _server.start();

        String rawResponse = _connector.getResponse("""
            GET / HTTP/1.0
            
            """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.getContent(), containsString("Hello"));
    }

    @Test
    public void testDump() throws Exception
    {
        _contextHandler.setContextPath("/context");
        _contextHandler.setHandler(new DumpHandler());
        _server.start();

        String rawResponse = _connector.getResponse("""
            GET /context/path/info HTTP/1.0
            
            """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.getContent(), containsString("contextPath=/context"));
        assertThat(response.getContent(), containsString("pathInfo=/path/info"));
    }

    @Test
    public void testDumpHeadersAndParameters() throws Exception
    {
        _contextHandler.setContextPath("/context");
        _contextHandler.setHandler(new DumpHandler());
        _server.start();

        String rawResponse = _connector.getResponse("""
            POST /context/path/info?A=1&B=2 HTTP/1.0
            Host: localhost
            HeaderName: headerValue
            Content-Type: %s
            Content-Length: 7
            
            C=3&D=4
            """.formatted(MimeTypes.Type.FORM_ENCODED.asString()));

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.getContent(), containsString("HeaderName: headerValue"));
        assertThat(response.getContent(), containsString("contextPath=/context"));
        assertThat(response.getContent(), containsString("pathInfo=/path/info"));
        assertThat(response.getContent(), containsString("contentType=application/x-www-form-urlencoded"));
        assertThat(response.getContent(), containsString("""
            A=1
            B=2
            C=3
            D=4
            """));
    }

    @Test
    public void testPersistentConnection() throws Exception
    {
        _contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                org.eclipse.jetty.server.Request coreRequest = baseRequest.getHttpChannel().getCoreRequest();

                baseRequest.setHandled(true);
                response.setStatus(200);
                response.setContentType("text/plain");
                response.getOutputStream().print("""
                    pathInContext=%s
                    baseRequest.hashCode=%x
                    coreRequest.id=%s
                    coreRequest.connectionMetaData.id=%s
                    coreRequest.connectionMetaData.persistent=%b
                    
                    """.formatted(
                        coreRequest.getPathInContext(),
                        baseRequest.hashCode(),
                        coreRequest.getId(),
                        coreRequest.getConnectionMetaData().getId(),
                        coreRequest.getConnectionMetaData().isPersistent()
                ));
            }
        });
        _server.start();

        LocalConnector.LocalEndPoint endPoint = _connector.connect();
        endPoint.addInput("""
            GET /one HTTP/1.1
            Host: localhost
            
            GET /two HTTP/1.1
            Host: localhost
            
            """);

        String rawResponse = endPoint.getResponse();
        System.err.println(rawResponse);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        Properties one = new Properties();
        one.load(new StringReader(response.getContent()));

        rawResponse = endPoint.getResponse();
        System.err.println(rawResponse);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        Properties two = new Properties();
        two.load(new StringReader(response.getContent()));

        assertThat(one.getProperty("baseRequest.hashCode"), notNullValue());
        assertThat(one.getProperty("baseRequest.hashCode"), equalTo(two.getProperty("baseRequest.hashCode")));

        assertThat(one.getProperty("coreRequest.connectionMetaData.id"), notNullValue());
        assertThat(one.getProperty("coreRequest.connectionMetaData.id"), equalTo(two.getProperty("coreRequest.connectionMetaData.id")));

        assertThat(one.getProperty("coreRequest.id"), notNullValue());
        assertThat(one.getProperty("coreRequest.id"), not(equalTo(two.getProperty("coreRequest.id"))));
    }
}
