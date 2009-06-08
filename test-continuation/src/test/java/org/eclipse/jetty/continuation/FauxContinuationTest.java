// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.continuation;

import java.io.IOException;
import java.io.InputStream;

import org.eclipse.jetty.continuation.test.ContinuationBase;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.IO;


public class FauxContinuationTest extends ContinuationBase
{
    protected Server _server = new Server();
    protected ServletHandler _servletHandler;
    protected SelectChannelConnector _connector;
    FilterHolder _filter;

    protected void setUp() throws Exception
    {
        _connector = new SelectChannelConnector();
        _server.setConnectors(new Connector[]{ _connector });
        ServletContextHandler servletContext = new ServletContextHandler(ServletContextHandler.NO_SECURITY|ServletContextHandler.NO_SESSIONS);
        _server.setHandler(servletContext);
        _servletHandler=servletContext.getServletHandler();
        ServletHolder holder=new ServletHolder(_servlet);
        _servletHandler.addServletWithMapping(holder,"/");
    }

    protected void tearDown() throws Exception
    {
        _server.stop();
    }

    public void testFaux() throws Exception
    {
        _filter=_servletHandler.addFilterWithMapping(ContinuationFilter.class,"/*",0);
        _filter.setInitParameter("debug","true");
        _filter.setInitParameter("faux","true");
        _server.start();
        _port=_connector.getLocalPort();
        
        doit("FauxContinuation");
    }

    public void testNoFauxDefaults() throws Exception
    {
        _filter=_servletHandler.addFilterWithMapping(ContinuationFilter.class,"/*",0);
        _filter.setInitParameter("debug","true");
        _server.start();
        _port=_connector.getLocalPort();
        
        doit("AsyncContinuation");
    }
    
    protected String toString(InputStream in) throws IOException
    {
        return IO.toString(in);
    }
    
}
