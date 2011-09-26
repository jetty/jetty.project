// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================
package org.eclipse.jetty.rewrite.handler;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.URLEncoder;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ProxyRuleTest
{
    private ProxyRule _rule;
    private RewriteHandler _handler;
    private Server _proxyServer = new Server();
    private Connector _proxyServerConnector = new SelectChannelConnector();
    private Server _targetServer = new Server();
    private Connector _targetServerConnector= new SelectChannelConnector();
    private HttpClient httpClient = new HttpClient();

    @Before
    public void init() throws Exception
    {
        _targetServer.addConnector(_targetServerConnector);
        _targetServer.setHandler(new AbstractHandler()
        {
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException,
            ServletException
            {
                baseRequest.setHandled(true);
                response.setStatus(201);
            }
        });
        _targetServer.start();

        _rule = new ProxyRule();
        _rule.setPattern("/foo/*");
        _rule.setProxyTo("http://localhost:" + _targetServerConnector.getLocalPort());
        _handler = new RewriteHandler();
        _handler.setRewriteRequestURI(true);
        _handler.setRules(new Rule[]
        { _rule });

        _proxyServer.addConnector(_proxyServerConnector);
        _proxyServer.setHandler(_handler);
        _proxyServer.start();

        httpClient.start();
    }

    @After
    public void destroy()
    {
        _rule = null;
    }

    @Test
    public void testProxy() throws Exception
    {

        ContentExchange exchange = new ContentExchange(true);
        exchange.setMethod(HttpMethods.GET);
        String body = "BODY";
        String url = "http://localhost:" + _proxyServerConnector.getLocalPort() + "/foo?body=" + URLEncoder.encode(body,"UTF-8");
        exchange.setURL(url);

        httpClient.send(exchange);
        assertEquals(HttpExchange.STATUS_COMPLETED,exchange.waitForDone());
        assertEquals(201,exchange.getResponseStatus());
    }

    @Test
    public void testProxyNoMatch() throws Exception
    {

        ContentExchange exchange = new ContentExchange(true);
        exchange.setMethod(HttpMethods.GET);
        String body = "BODY";
        String url = "http://localhost:" + _proxyServerConnector.getLocalPort() + "/foobar?body=" + URLEncoder.encode(body,"UTF-8");
        exchange.setURL(url);

        httpClient.send(exchange);
        assertEquals(HttpExchange.STATUS_COMPLETED,exchange.waitForDone());
        assertEquals(404,exchange.getResponseStatus());
    }
}
