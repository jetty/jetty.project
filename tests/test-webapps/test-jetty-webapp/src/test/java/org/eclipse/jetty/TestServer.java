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

package org.eclipse.jetty;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NCSARequestLog;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.session.HashSessionManager;
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

        String jetty_root = "../../..";

        // Setup Threadpool
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMaxThreads(100);

        // Setup server
        Server server = new Server(threadPool);
        server.manage(threadPool);

        // Setup JMX
        MBeanContainer mbContainer=new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbContainer);
        server.addBean(Log.getLog());
        

        // Common HTTP configuration
        HttpConfiguration config = new HttpConfiguration();
        config.setSecurePort(8443);
        config.addCustomizer(new ForwardedRequestCustomizer());
        config.addCustomizer(new SecureRequestCustomizer());
        config.setSendDateHeader(true);
        config.setSendServerVersion(true);
        
        
        // Http Connector
        HttpConnectionFactory http = new HttpConnectionFactory(config);
        ServerConnector httpConnector = new ServerConnector(server,http);
        httpConnector.setPort(8080);
        httpConnector.setIdleTimeout(30000);
        server.addConnector(httpConnector);

        /*
        // SSL configurations
        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath(jetty_root + "/jetty-server/src/main/config/etc/keystore");
        sslContextFactory.setKeyStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        sslContextFactory.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");
        sslContextFactory.setTrustStorePath(jetty_root + "/jetty-server/src/main/config/etc/keystore");
        sslContextFactory.setTrustStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        sslContextFactory.setExcludeCipherSuites(
                "SSL_RSA_WITH_DES_CBC_SHA",
                "SSL_DHE_RSA_WITH_DES_CBC_SHA",
                "SSL_DHE_DSS_WITH_DES_CBC_SHA",
                "SSL_RSA_EXPORT_WITH_RC4_40_MD5",
                "SSL_RSA_EXPORT_WITH_DES40_CBC_SHA",
                "SSL_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA",
                "SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA");
        
        
        // Spdy Connector
        SPDYServerConnectionFactory.checkNPNAvailable();
        PushStrategy push = new ReferrerPushStrategy();
        HTTPSPDYServerConnectionFactory spdy2 = new HTTPSPDYServerConnectionFactory(2,config,push);
        spdy2.setInputBufferSize(8192);
        spdy2.setInitialWindowSize(32768);
        HTTPSPDYServerConnectionFactory spdy3 = new HTTPSPDYServerConnectionFactory(3,config,push);
        spdy2.setInputBufferSize(8192);
        NPNServerConnectionFactory npn = new NPNServerConnectionFactory(spdy3.getProtocol(),spdy2.getProtocol(),http.getProtocol());
        npn.setDefaultProtocol(http.getProtocol());
        npn.setInputBufferSize(1024); 
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory,npn.getProtocol()); 
        ServerConnector spdyConnector = new ServerConnector(server,ssl,npn,spdy3,spdy2,http);
        spdyConnector.setPort(8443);
        spdyConnector.setIdleTimeout(15000);
        server.addConnector(spdyConnector);
        
        */
        
        // Handlers
        HandlerCollection handlers = new HandlerCollection();
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        RequestLogHandler requestLogHandler = new RequestLogHandler();
        handlers.setHandlers(new Handler[]
        { contexts, new DefaultHandler(), requestLogHandler });

        // Add restart handler to test the ability to save sessions and restart
        RestartHandler restart = new RestartHandler();
        restart.setHandler(handlers);

        server.setHandler(restart);


        // Setup context
        HashLoginService login = new HashLoginService();
        login.setName("Test Realm");
        login.setConfig(jetty_root + "/tests/test-webapps/test-jetty-webapp/src/main/config/demo-base/etc/realm.properties");
        server.addBean(login);

        File log=File.createTempFile("jetty-yyyy_mm_dd", "log");
        NCSARequestLog requestLog = new NCSARequestLog(log.toString());
        requestLog.setExtended(false);
        requestLogHandler.setRequestLog(requestLog);

        server.setStopAtShutdown(true);

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
                    @Override
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
