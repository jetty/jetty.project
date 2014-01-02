//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;

import junit.framework.Assert;

import org.eclipse.jetty.continuation.test.ContinuationBase;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.AbstractLifeCycle;



public class ContinuationTest extends ContinuationBase
{
    protected Server _server = new Server();
    protected ServletHandler _servletHandler;
    protected SelectChannelConnector _connector;
    FilterHolder _filter;
    protected List<String> _log = new ArrayList<String>();

    @Override
    protected void setUp() throws Exception
    {
        _connector = new SelectChannelConnector();
        _server.setConnectors(new Connector[]{ _connector });

        _log.clear();
        RequestLogHandler requestLogHandler = new RequestLogHandler();
        requestLogHandler.setRequestLog(new Log());
        _server.setHandler(requestLogHandler);
        
        ServletContextHandler servletContext = new ServletContextHandler(ServletContextHandler.NO_SECURITY|ServletContextHandler.NO_SESSIONS);
        requestLogHandler.setHandler(servletContext);
        
        _servletHandler=servletContext.getServletHandler();
        ServletHolder holder=new ServletHolder(_servlet);
        holder.setAsyncSupported(true);
        _servletHandler.addServletWithMapping(holder,"/");
        
        _server.start();
        _port=_connector.getLocalPort();
        
    }

    @Override
    protected void tearDown() throws Exception
    {
        Assert.assertEquals(1,_log.size());
        Assert.assertTrue(_log.get(0).startsWith("200 "));
        Assert.assertTrue(_log.get(0).endsWith(" /"));
        _server.stop();
    }
    
    public void testContinuation() throws Exception
    {
        doNormal("AsyncContinuation");
    }
    
    public void testSleep() throws Exception
    {
        doSleep();
    }

    public void testSuspend() throws Exception
    {
        doSuspend();
    }

    public void testSuspendWaitResume() throws Exception
    {
        doSuspendWaitResume();
    }

    public void testSuspendResume() throws Exception
    {
        doSuspendResume();
    }

    public void testSuspendWaitComplete() throws Exception
    {
        doSuspendWaitComplete();
    }

    public void testSuspendComplete() throws Exception
    {
        doSuspendComplete();
    }

    public void testSuspendWaitResumeSuspendWaitResume() throws Exception
    {
        doSuspendWaitResumeSuspendWaitResume();
    }
    
    public void testSuspendWaitResumeSuspendComplete() throws Exception
    {
        doSuspendWaitResumeSuspendComplete();
    }

    public void testSuspendWaitResumeSuspend() throws Exception
    {
        doSuspendWaitResumeSuspend();
    }

    public void testSuspendTimeoutSuspendResume() throws Exception
    {
        doSuspendTimeoutSuspendResume();
    }

    public void testSuspendTimeoutSuspendComplete() throws Exception
    {
        doSuspendTimeoutSuspendComplete();
    }

    public void testSuspendTimeoutSuspend() throws Exception
    {
        doSuspendTimeoutSuspend();
    }

    public void testSuspendThrowResume() throws Exception
    {
        doSuspendThrowResume();
    }

    public void testSuspendResumeThrow() throws Exception
    {
        doSuspendResumeThrow();
    }

    public void testSuspendThrowComplete() throws Exception
    {
        doSuspendThrowComplete();
    }

    public void testSuspendCompleteThrow() throws Exception
    {
        doSuspendCompleteThrow();
    }
    
    
    protected String toString(InputStream in) throws IOException
    {
        return IO.toString(in);
    }
    
    class Log extends AbstractLifeCycle implements RequestLog
    {
        public void log(Request request, Response response)
        {
            _log.add(response.getStatus()+" "+response.getContentCount()+" "+request.getRequestURI());
        }
        
    }
}
