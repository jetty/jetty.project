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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;

import org.eclipse.jetty.client.Address;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.toolchain.test.SimpleRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ProxyRuleTest extends AbstractRuleTestCase
{
    private ProxyRule _rule;
    private RewriteHandler _handler;

    @Before
    public void init() throws Exception
    {
        start(false);
        _rule = new ProxyRule(); 
        
        _handler = new RewriteHandler();
        _handler.setRewriteRequestURI(true);

        _handler.setRules(new Rule[] { _rule } );

        _server.setHandler(_handler);
    }

    @After
    public void destroy()
    {
        _rule = null;
    }

    @Test
    public void testProxy() throws Exception
    {
//        HttpClient httpClient = new HttpClient();
//        httpClient.setProxy(new Address("localhost", proxyPort()));
//        httpClient.start();
//
//        try
//        {
//            ContentExchange exchange = new ContentExchange(true);
//            exchange.setMethod(HttpMethods.GET);
//            String body = "BODY";
//            exchange.setURL("https://localhost:" + serverConnector.getLocalPort() + "/echo?body=" + URLEncoder.encode(body, "UTF-8"));
//
//            httpClient.send(exchange);
//            assertEquals(HttpExchange.STATUS_COMPLETED, exchange.waitForDone());
//        //_rule.matchAndApply(null, _request, _response);
//        
//        //assertEquals(location, _response.getHeader(HttpHeaders.LOCATION));
    }
}
