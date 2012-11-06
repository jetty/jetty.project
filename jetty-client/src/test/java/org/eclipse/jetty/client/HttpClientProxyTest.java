//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class HttpClientProxyTest extends AbstractHttpClientServerTest
{

    private Server proxy;
    private ServerConnector proxyConnector;

    public HttpClientProxyTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Before
    public void prepareProxy() throws Exception
    {
        proxy = new Server();
        proxyConnector = new ServerConnector(proxy);
        proxy.addConnector(proxyConnector);
        proxy.start();
    }

    @After
    public void disposeProxy() throws Exception
    {
        proxy.stop();
    }

    private static final String PROXIED_HEADER = "X-Proxied";

    @Test
    public void testProxyWithExcludedHosts() throws Exception
    {
//        ProxyConfiguration proxyConfiguration = new ProxyConfiguration("localhost", proxy.getLocalPort());
//        proxyConfiguration.addExcludedHost("wikipedia.org");
//        proxyConfiguration.addExcludedHost(".wikipedia.org");
//        client.setProxyConfiguration(proxyConfiguration);
    }

    @Test
    public void testProxyWithAuthentication() throws Exception
    {
//        client.getAuthenticationStore().addAuthentication(new BasicAuthentication("/", "proxy-realm", "basic", "basic"));
    }

    @Test
    public void testHTTPTunnel() throws Exception
    {
        // Usually, plain text HTTP requests do not require a CONNECT to be sent to the proxy
        // However, I may want to do that anyway, for example to avoid that the proxy modifies
        // in any way the request

//        Destination destination = client.getDestination("http", proxyHost, proxyPort);
//        Connection connection = destination.newConnection().get(5, TimeUnit.SECONDS);

        // Create and send an explicit CONNECT
//        Request request = client.newRequest(serverHost, serverPort).method(HttpMethod.CONNECT);
//        connection.send(request, null);

        // Now anything I send over this connection is tunneled by the proxy
        // e.g. SMTP
//        ((HttpConnection)connection).getEndPoint().write(null, null, ByteBuffer.wrap("HELO localhost\r\n"));
    }
}
