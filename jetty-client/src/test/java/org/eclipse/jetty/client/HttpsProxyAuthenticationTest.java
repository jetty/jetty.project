// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.security.Authentication;
import org.eclipse.jetty.client.security.BasicAuthentication;
import org.eclipse.jetty.client.security.Realm;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ConnectHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/* ------------------------------------------------------------ */
/**
 * This UnitTest class executes two tests. Both will send a http request to https://google.com through a misbehaving proxy server.
 * <p/>
 * The first test runs against a proxy which simply closes the connection (as nginx does) for a connect request. The second proxy server always responds with a
 * 500 error.
 * <p/>
 * The expected result for both tests is an exception and the HttpExchange should have status HttpExchange.STATUS_EXCEPTED.
 */
public class HttpsProxyAuthenticationTest
{
    private Server _proxy = new Server();
    private HttpClient _client = new HttpClient();
    private boolean authHandlerSend;

    @Before
    public void init() throws Exception
    {
        // setup proxies with different behaviour
        _proxy.addConnector(new SelectChannelConnector());
        _proxy.setHandler(new ConnectHandler()
        {
            @Override
            protected boolean handleAuthentication(HttpServletRequest request, HttpServletResponse response, String address) throws ServletException,
                    IOException
            {
                if(!request.getHeader("Authorization").isEmpty()){
                    authHandlerSend = true;
                }
                return super.handleAuthentication(request,response,address);
            }
        });
        _proxy.start();
        int proxyPort = _proxy.getConnectors()[0].getLocalPort();

        Authentication authentication = new BasicAuthentication(new Realm()
        {
            public String getId()
            {
                return "MyRealm";
            }

            public String getPrincipal()
            {
                return "jetty";
            }

            public String getCredentials()
            {
                return "jetty";
            }
        });

        _client.setProxy(new Address("localhost",proxyPort));
        _client.setProxyAuthentication(authentication);
        _client.start();
    }

    @After
    public void destroy() throws Exception
    {
        _client.stop();
        _proxy.stop();
    }

    @Test
    public void httpsViaProxyThatReturns504ErrorTest() throws Exception
    {
        sendRequestThroughProxy(new ContentExchange(),"google",7);
        assertTrue("Authorization header not set!",authHandlerSend);
    }

    private void sendRequestThroughProxy(HttpExchange exchange, String desiredBehaviour, int exptectedStatus) throws Exception
    {
        String url = "https://" + desiredBehaviour + ".com/";
        exchange.setURL(url);
        exchange.addRequestHeader("behaviour",desiredBehaviour);
        _client.send(exchange);
        assertEquals(HttpExchange.toState(exptectedStatus) + " status awaited",exptectedStatus,exchange.waitForDone());
    }

}
