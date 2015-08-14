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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ContinuationTest extends AbstractContinuationTest
{
    protected Server _server = new Server();
    protected ServletHandler _servletHandler;
    protected ServerConnector _connector;
    protected List<String> _log = new ArrayList<>();

    @Before
    public void setUp() throws Exception
    {
        _connector = new ServerConnector(_server);
        _server.setConnectors(new Connector[]{ _connector });

        _log.clear();
        RequestLogHandler requestLogHandler = new RequestLogHandler();
        requestLogHandler.setRequestLog(new Log());
        _server.setHandler(requestLogHandler);

        ServletContextHandler servletContext = new ServletContextHandler();
        requestLogHandler.setHandler(servletContext);

        _servletHandler=servletContext.getServletHandler();
        ServletHolder holder=new ServletHolder(_servlet);
        holder.setAsyncSupported(true);
        _servletHandler.addServletWithMapping(holder, "/");

        _server.start();
        _port=_connector.getLocalPort();
    }

    @After
    public void tearDown() throws Exception
    {
        _server.stop();
        Assert.assertEquals(1,_log.size());
        Assert.assertTrue(_log.get(0).startsWith("200 "));
        Assert.assertTrue(_log.get(0).endsWith(" /"));
    }

    @Test
    public void testContinuation() throws Exception
    {
        testNormal(Servlet3Continuation.class.getName());
    }

    class Log extends AbstractLifeCycle implements RequestLog
    {
        @Override
        public void log(Request request, Response response)
        {
            int status = response.getCommittedMetaData().getStatus();
            long written = response.getHttpChannel().getBytesWritten();
            _log.add(status+" "+written+" "+request.getRequestURI());
        }
    }
}
