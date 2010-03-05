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

import java.io.File;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ssl.SslSocketConnector;
import org.eclipse.jetty.util.log.Log;

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

        String keystore = System.getProperty("user.dir") + File.separator + "src" + File.separator + "test" + File.separator + "resources" + File.separator
                + "keystore";

        connector.setPort(0);
        connector.setKeystore(keystore);
        connector.setPassword("storepwd");
        connector.setKeyPassword("keypwd");
	connector.setAllowRenegotiate(true);

        _server.setConnectors(new Connector[]
        { connector });
        _connector=connector;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.client.HttpExchangeTest#testGetWithContentExchange()
     */
    @Override
    public void testGetWithContentExchange() throws Exception
    {
        // TODO Resolve problems on IBM JVM https://bugs.eclipse.org/bugs/show_bug.cgi?id=304532
        if (System.getProperty("java.vendor").toLowerCase().indexOf("ibm")<0)
            super.testGetWithContentExchange();
        else
            Log.warn("Skipped SSL testGetWithContentExchange on IBM JVM");
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.client.HttpExchangeTest#testPerf()
     */
    @Override
    public void testPerf() throws Exception
    {
        // TODO Resolve problems on IBM JVM https://bugs.eclipse.org/bugs/show_bug.cgi?id=304532
        if (System.getProperty("java.vendor").toLowerCase().indexOf("ibm")<0)
            super.testPerf();
        else
            Log.warn("Skipped SSL testPerf on IBM JVM");
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.client.HttpExchangeTest#testPostWithContentExchange()
     */
    @Override
    public void testPostWithContentExchange() throws Exception
    {
        // TODO Resolve problems on IBM JVM https://bugs.eclipse.org/bugs/show_bug.cgi?id=304532
        if (System.getProperty("java.vendor").toLowerCase().indexOf("ibm")<0)
            super.testPostWithContentExchange();
        else
            Log.warn("Skipped SSL testPostWithContentExchange on IBM JVM");
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.client.HttpExchangeTest#testReserveConnections()
     */
    @Override
    public void testReserveConnections() throws Exception
    {
        // TODO Resolve problems on IBM JVM https://bugs.eclipse.org/bugs/show_bug.cgi?id=304532
        if (System.getProperty("java.vendor").toLowerCase().indexOf("ibm")<0)
            super.testReserveConnections();
        else
            Log.warn("Skipped SSL testReserveConnections on IBM JVM");
    }
}
