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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import org.eclipse.jetty.client.helperClasses.ServerAndClientCreator;
import org.eclipse.jetty.client.helperClasses.SslServerAndClientCreator;
import org.eclipse.jetty.server.Connector;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Functional testing for HttpExchange.
 */
public class SslHttpExchangeTest extends HttpExchangeTest
{
    protected static ServerAndClientCreator serverAndClientCreator = new SslServerAndClientCreator();
    
    /* ------------------------------------------------------------ */
    @Before
    public void setUpOnce() throws Exception
    {
        _scheme="https";
        _server = serverAndClientCreator.createServer();
        _httpClient = serverAndClientCreator.createClient(3000L,3500L,2000);
        Connector[] connectors = _server.getConnectors();
        _port = connectors[0].getLocalPort();
    }

    /* ------------------------------------------------------------ */
    private void IgnoreTestOnBuggyIBM()
    {
        // Use Junit 4.x to flag test as ignored if encountering IBM JVM
        // Will show up in various junit reports as an ignored test as well.
        Assume.assumeThat(System.getProperty("java.vendor").toLowerCase(),not(containsString("ibm")));
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.client.HttpExchangeTest#testGetWithContentExchange()
     */
    @Test
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
    @Test
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
    @Test
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
    @Test
    @Override
    public void testReserveConnections() throws Exception
    {
        // TODO Resolve problems on IBM JVM https://bugs.eclipse.org/bugs/show_bug.cgi?id=304532
        IgnoreTestOnBuggyIBM();
        super.testReserveConnections();
    }
}
