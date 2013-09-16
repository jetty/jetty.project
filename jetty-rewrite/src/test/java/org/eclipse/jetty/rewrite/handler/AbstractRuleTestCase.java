//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.rewrite.handler;

import org.eclipse.jetty.io.bio.StringEndPoint;
import org.eclipse.jetty.server.BlockingHttpConnection;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.AbstractHttpConnection;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.junit.After;

public abstract class AbstractRuleTestCase
{
    protected Server _server = new Server();
    protected LocalConnector _connector;
    protected StringEndPoint _endpoint = new StringEndPoint();
    protected AbstractHttpConnection _connection;
    protected Request _request;
    protected Response _response;
    protected boolean _isSecure = false;

    @After
    public void stopServer() throws Exception
    {
        stop();
    }

    protected void start(final boolean isSecure) throws Exception
    {
        _connector = new LocalConnector()
        {
            public boolean isConfidential(Request request)
            {
                return isSecure;
            }
        };
        _server.setConnectors(new Connector[]{_connector});
        _server.start();
        reset();
    }

    protected void stop() throws Exception
    {
        _server.stop();
        _server.join();
        _request = null;
        _response = null;
    }

    protected void reset()
    {
        _connection = new BlockingHttpConnection(_connector, _endpoint, _server);
        _request = new Request(_connection);
        _response = new Response(_connection);
        _request.setRequestURI("/test/");
    }
}
