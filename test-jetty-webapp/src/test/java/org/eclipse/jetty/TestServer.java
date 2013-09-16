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

package org.eclipse.jetty;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.NCSARequestLog;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.bio.SocketConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.nio.BlockingChannelConnector;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.session.HashSessionManager;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StdErrLog;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.Ignore;

@Ignore("Not a test case")
public class TestServer
{
    private static final Logger LOG = Log.getLogger(TestServer.class);

    public static void main(String[] args) throws Exception
    {
        ((StdErrLog)Log.getLog()).setSource(false);
        
        String jetty_root = "..";

        Server server = new Server();
        server.setSendDateHeader(true);
        
        // Setup JMX
        MBeanContainer mbContainer=new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.getContainer().addEventListener(mbContainer);
        server.addBean(mbContainer);
        mbContainer.addBean(Log.getLog());
        
        // Setup Threadpool
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMaxThreads(100);
        server.setThreadPool(threadPool);

        // Setup Connectors
        SelectChannelConnector connector0 = new SelectChannelConnector();
        connector0.setPort(8080);
        connector0.setMaxIdleTime(30000);
        connector0.setConfidentialPort(8443);
        connector0.setUseDirectBuffers(true);
        server.addConnector(connector0);
        
        // Setup Connectors
        SelectChannelConnector connector1 = new SelectChannelConnector();
        connector1.setPort(8081);
        connector1.setMaxIdleTime(30000);
        connector1.setConfidentialPort(8443);
        connector1.setUseDirectBuffers(false);
        server.addConnector(connector1);
        
        // Setup Connectors
        SocketConnector connector2 = new SocketConnector();
        connector2.setPort(8082);
        connector2.setMaxIdleTime(30000);
        connector2.setConfidentialPort(8443);
        server.addConnector(connector2);
        
        // Setup Connectors
        BlockingChannelConnector connector3 = new BlockingChannelConnector();
        connector3.setPort(8083);
        connector3.setMaxIdleTime(30000);
        connector3.setConfidentialPort(8443);
        server.addConnector(connector3);

        SslSelectChannelConnector ssl_connector = new SslSelectChannelConnector();
        ssl_connector.setPort(8443);
        SslContextFactory cf = ssl_connector.getSslContextFactory();
        cf.setKeyStore(jetty_root + "/jetty-server/src/main/config/etc/keystore");
        cf.setKeyStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        cf.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");
        cf.setTrustStore(jetty_root + "/jetty-server/src/main/config/etc/keystore");
        cf.setTrustStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        server.addConnector(ssl_connector);

        HandlerCollection handlers = new HandlerCollection();
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        RequestLogHandler requestLogHandler = new RequestLogHandler();
        handlers.setHandlers(new Handler[]
        { contexts, new DefaultHandler(), requestLogHandler });
        
        // Add restart handler to test the ability to save sessions and restart
        RestartHandler restart = new RestartHandler();
        restart.setHandler(handlers);
        
        server.setHandler(restart);

        
        // Setup deployers

        HashLoginService login = new HashLoginService();
        login.setName("Test Realm");
        login.setConfig(jetty_root + "/test-jetty-webapp/src/main/config/etc/realm.properties");
        server.addBean(login);

        File log=File.createTempFile("jetty-yyyy_mm_dd", "log");
        NCSARequestLog requestLog = new NCSARequestLog(log.toString());
        requestLog.setExtended(false);
        requestLogHandler.setRequestLog(requestLog);

        server.setStopAtShutdown(true);
        server.setSendServerVersion(true);
        
        WebAppContext webapp = new WebAppContext();
        webapp.setParentLoaderPriority(true);
        webapp.setResourceBase("./src/main/webapp");
        webapp.setAttribute("testAttribute","testValue");
        File sessiondir=File.createTempFile("sessions",null);
        if (sessiondir.exists())
            sessiondir.delete();
        sessiondir.mkdir();
        sessiondir.deleteOnExit();
        ((HashSessionManager)webapp.getSessionHandler().getSessionManager()).setStoreDirectory(sessiondir);
        ((HashSessionManager)webapp.getSessionHandler().getSessionManager()).setSavePeriod(10);
        
        contexts.addHandler(webapp);
        
        ContextHandler srcroot = new ContextHandler();
        srcroot.setResourceBase(".");
        srcroot.setHandler(new ResourceHandler());
        srcroot.setContextPath("/src");
        contexts.addHandler(srcroot);
        
        server.start();
        server.join();
    }
    
    private static class RestartHandler extends HandlerWrapper
    {

        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.server.handler.HandlerWrapper#handle(java.lang.String, org.eclipse.jetty.server.Request, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
         */
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            super.handle(target,baseRequest,request,response);
            if (Boolean.valueOf(request.getParameter("restart")))
            {
                final Server server=getServer();
                
                new Thread()
                {
                    public void run()
                    {
                        try
                        {
                            Thread.sleep(100);
                            server.stop();
                            Thread.sleep(100);
                            server.start();
                        }
                        catch(Exception e)
                        {
                            LOG.warn(e);
                        }
                    }
                }.start();
            }
        }
        
    }

}
