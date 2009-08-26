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
import org.mortbay.jetty.Connector;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.bio.SocketConnector;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.FilterHolder;
import org.mortbay.jetty.servlet.ServletHandler;
import org.mortbay.jetty.servlet.ServletHolder;
import org.mortbay.util.IO;


public class Jetty6ContinuationTest extends ContinuationBase
{
    protected Server _server = new Server();
    protected ServletHandler _servletHandler;
    protected SelectChannelConnector _selectChannelConnector;
    protected SocketConnector _socketConnector;
    FilterHolder _filter;

    protected void setUp() throws Exception
    {
        _selectChannelConnector = new SelectChannelConnector();
        _socketConnector = new SocketConnector();
        _server.setConnectors(new Connector[]{ _selectChannelConnector,_socketConnector });
        Context servletContext = new Context(Context.NO_SECURITY|Context.NO_SESSIONS);
        _server.setHandler(servletContext);
        _servletHandler=servletContext.getServletHandler();
        ServletHolder holder=new ServletHolder(_servlet);
        _servletHandler.addServletWithMapping(holder,"/");
        _filter=_servletHandler.addFilterWithMapping(ContinuationFilter.class,"/*",0);
    }

    protected void tearDown() throws Exception
    {
        _server.stop();
    }

    public void testJetty6Nio() throws Exception
    {
        _filter.setInitParameter("debug","true");
        //_filter.setInitParameter("faux","false");
        _server.start();
        
        _port=_selectChannelConnector.getLocalPort();
        doit("Jetty6Continuation");
    }

    public void testFauxNio() throws Exception
    {
        _filter.setInitParameter("debug","true");
        _filter.setInitParameter("faux","true");
        _server.start();
        
        _port=_selectChannelConnector.getLocalPort();
        doit("FauxContinuation");
    }

    public void testJetty6Bio() throws Exception
    {
        _filter.setInitParameter("debug","true");
        //_filter.setInitParameter("faux","false");
        _server.start();
        
        _port=_socketConnector.getLocalPort();
        doit("FauxContinuation");
    }

    public void testFauxBio() throws Exception
    {
        _filter.setInitParameter("debug","true");
        _filter.setInitParameter("faux","true");
        _server.start();
        
        _port=_socketConnector.getLocalPort();
        doit("FauxContinuation");
    }
    
    protected String toString(InputStream in) throws IOException
    {
        return IO.toString(in);
    }
    
}
