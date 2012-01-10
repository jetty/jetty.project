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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
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
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class HttpsProxyAuthenticationTest
{
    private Server _proxy = new Server();
    private HttpClient _client = new HttpClient();
    private boolean authHandlerSend;

    @Before
    public void init() throws Exception
    {
        SelectChannelConnector connector = new SelectChannelConnector();
        _proxy.addConnector(connector);
        _proxy.setHandler(new ConnectHandler()
        {
            @Override
            protected boolean handleAuthentication(HttpServletRequest request, HttpServletResponse response, String address) throws ServletException, IOException
            {
                String authHeader = request.getHeader("Authorization");
                if (authHeader != null && authHeader.length() > 0)
                    authHandlerSend = true;
                return super.handleAuthentication(request,response,address);
            }
        });
        _proxy.start();
        int proxyPort = connector.getLocalPort();

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

        _client.setProxy(new Address("localhost", proxyPort));
        _client.setProxyAuthentication(authentication);
        _client.start();
    }

    @After
    public void destroy() throws Exception
    {
        _client.stop();
        _proxy.stop();
        _proxy.join();
    }

    @Test
    public void httpsViaProxyThatReturns504ErrorTest() throws Exception
    {
        // Assume that we can connect to google
        String host = "google.com";
        int port = 443;
        Socket socket = new Socket();
        try
        {
            socket.connect(new InetSocketAddress(host, port), 1000);
        }
        catch (IOException x)
        {
            Assume.assumeNoException(x);
        }
        finally
        {
            socket.close();
        }

        HttpExchange exchange = new ContentExchange();
        exchange.setURL("https://" + host + ":" + port);
        exchange.addRequestHeader("behaviour", "google");
        _client.send(exchange);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, exchange.waitForDone());
        Assert.assertTrue("Authorization header not set!", authHandlerSend);
    }
}
