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

import junit.framework.TestCase;

import org.eclipse.jetty.io.bio.StringEndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;

public abstract class AbstractRuleTestCase extends TestCase
{
    protected Server _server=new Server();
    protected LocalConnector _connector;
    protected StringEndPoint _endpoint=new StringEndPoint();
    protected HttpConnection _connection;
    
    protected Request _request;
    protected Response _response;
    
    protected boolean _isSecure = false;
    
    public void setUp() throws Exception
    {
        start();
    }
    
    public void tearDown() throws Exception
    {
        stop();
    }
    
    public void start() throws Exception
    {
        _connector = new LocalConnector() {
            public boolean isConfidential(Request request)
            {
                return _isSecure;
            }
        };
        
        _server.setConnectors(new Connector[]{_connector});
        _server.start();
        reset();
    }
    
    public void stop() throws Exception
    {
        _server.stop();
        _request = null;
        _response = null;
    }
    
    public void reset()
    {
        _connection = new HttpConnection(_connector,_endpoint,_server);
        _request = new Request(_connection);
        _response = new Response(_connection);
        
        _request.setRequestURI("/test/");
    }
}
