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

package org.eclipse.jetty.client;

import static org.hamcrest.Matchers.*;

import org.eclipse.jetty.http.ssl.SslContextFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ssl.SslSocketConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.Assume;

/**
 * Functional testing for HttpExchange.
 * 
 * 
 * 
 */
public class SslHttpExchangeTest extends HttpExchangeTest
{
    @Override
    protected void setUp() throws Exception
    {
        _scheme="https://";
        startServer();
        _httpClient=new HttpClient();
        _httpClient.setIdleTimeout(2000);
        _httpClient.setTimeout(2500);
        _httpClient.setConnectTimeout(1000);
        _httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        _httpClient.setConnectorType(HttpClient.CONNECTOR_SOCKET);
        _httpClient.setMaxConnectionsPerAddress(2);
        _httpClient.start();
    }

    @Override
    protected void newServer()
    {
        _server = new Server();
        //SslSelectChannelConnector connector = new SslSelectChannelConnector();
        SslSocketConnector connector = new SslSocketConnector();

        String keystore = MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath();

        connector.setPort(0);
        SslContextFactory cf = connector.getSslContextFactory();
        cf.setKeyStore(keystore);
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
	connector.setAllowRenegotiate(true);

        _server.setConnectors(new Connector[]
        { connector });
        _connector=connector;
    }
    
    private void IgnoreTestOnBuggyIBM() {
        // Use Junit 4.x to flag test as ignored if encountering IBM JVM
        // Will show up in various junit reports as an ignored test as well.
        Assume.assumeThat(System.getProperty("java.vendor").toLowerCase(),not(containsString("ibm")));
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.client.HttpExchangeTest#testGetWithContentExchange()
     */
    @Override
    public void testGetWithContentExchange() throws Exception
    {
        // TODO Resolve problems on IBM JVM https://bugs.eclipse.org/bugs/show_bug.cgi?id=304532
        IgnoreTestOnBuggyIBM();
        super.testGetWithContentExchange();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.client.HttpExchangeTest#testPerf()
     */
    @Override
    public void testPerf() throws Exception
    {
        // TODO Resolve problems on IBM JVM https://bugs.eclipse.org/bugs/show_bug.cgi?id=304532
        IgnoreTestOnBuggyIBM();
        super.testPerf();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.client.HttpExchangeTest#testPostWithContentExchange()
     */
    @Override
    public void testPostWithContentExchange() throws Exception
    {
        // TODO Resolve problems on IBM JVM https://bugs.eclipse.org/bugs/show_bug.cgi?id=304532
        IgnoreTestOnBuggyIBM();
        super.testPostWithContentExchange();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.client.HttpExchangeTest#testReserveConnections()
     */
    @Override
    public void testReserveConnections() throws Exception
    {
        // TODO Resolve problems on IBM JVM https://bugs.eclipse.org/bugs/show_bug.cgi?id=304532
        IgnoreTestOnBuggyIBM();
        super.testReserveConnections();
    }
}
