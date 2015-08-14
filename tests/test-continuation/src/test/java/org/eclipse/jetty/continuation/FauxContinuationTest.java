//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.continuation;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

@Ignore
public class FauxContinuationTest extends AbstractContinuationTest
{
    protected Server _server = new Server();
    protected ServletHandler _servletHandler;
    protected ServerConnector _connector;
    FilterHolder _filter;

    @Before
    public void setUp() throws Exception
    {
        _connector = new ServerConnector(_server);
        _server.setConnectors(new Connector[]{ _connector });
        ServletContextHandler servletContext = new ServletContextHandler();
        _server.setHandler(servletContext);
        _servletHandler=servletContext.getServletHandler();
        ServletHolder holder=new ServletHolder(_servlet);
        _servletHandler.addServletWithMapping(holder,"/");

        _filter=_servletHandler.addFilterWithMapping(ContinuationFilter.class,"/*",null);
        _filter.setInitParameter("debug","true");
        _filter.setInitParameter("faux","true");
        _server.start();
        _port=_connector.getLocalPort();
    }

    @After
    public void tearDown() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testContinuation() throws Exception
    {
        testNormal("FauxContinuation");
    }
}
