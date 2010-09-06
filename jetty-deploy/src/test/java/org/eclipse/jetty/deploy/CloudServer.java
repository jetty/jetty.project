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

package org.eclipse.jetty.deploy;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.security.PermissionCollection;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.NCSARequestLog;
import org.eclipse.jetty.server.ResourceCache;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.webapp.ClasspathPattern;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;

public class CloudServer
{
    public static void main(String[] args) throws Exception
    {
        Log.getLog().setDebugEnabled(false);
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
        
        HandlerCollection handlers = new HandlerCollection();
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        RequestLogHandler requestLogHandler = new RequestLogHandler();
        handlers.setHandlers(new Handler[]
        { contexts, new DefaultHandler(), requestLogHandler });
        server.setHandler(handlers);

        

        HashLoginService login = new HashLoginService();
        login.setName("Test Realm");
        login.setConfig(jetty_root + "/test-jetty-webapp/src/main/config/etc/realm.properties");
        server.addBean(login);

        File log=File.createTempFile("jetty-yyyy_mm_dd-", ".log");
        NCSARequestLog requestLog = new NCSARequestLog(log.toString());
        requestLog.setExtended(false);
        requestLogHandler.setRequestLog(requestLog);

        server.setStopAtShutdown(true);
        server.setSendServerVersion(true);
        
        
        final Resource baseResource= Resource.newResource("../test-jetty-webapp/src/main/webapp");

        ResourceFactory resources = new ResourceFactory()
        {
            public Resource getResource(String path)
            {
                try
                {
                    return baseResource.addPath(path);
                }
                catch(IOException e)
                {
                    throw new RuntimeIOException(e);
                }
            }
        };
        
        WebAppClassLoader.Context loaderContext = new WebAppClassLoader.Context()
        {
            private ClasspathPattern _systemClasses = new ClasspathPattern(WebAppContext.__dftSystemClasses);
            private ClasspathPattern _serverClasses = new ClasspathPattern(WebAppContext.__dftServerClasses);
            
            public Resource newResource(String urlOrPath) throws IOException
            {
                return Resource.newResource(urlOrPath);
            }

            public PermissionCollection getPermissions()
            {
                return null;
            }

            public boolean isSystemClass(String clazz)
            {
                return _systemClasses.match(clazz);
            }

            public boolean isServerClass(String clazz)
            {
                return _serverClasses.match(clazz);
            }

            public boolean isParentLoaderPriority()
            {
                return false;
            }

            public String getExtraClasspath()
            {
                return null;
            }
        };
        
        WebAppClassLoader loader = new WebAppClassLoader(loaderContext);
        loader.setName("template");
        loader.addClassPath("../test-jetty-webapp/target/classes");
        loader.addJars(Resource.newResource("../test-jetty-webapp/target/test-jetty-webapp-7.2.0-SNAPSHOT/WEB-INF/lib"));
        
        
        // Non cloud deployment first
        for (int i=0;i<10;i++)
        {
            final WebAppContext webapp= new WebAppContext();
            webapp.setWar("../test-jetty-webapp/target/test-jetty-webapp-7.2.0-SNAPSHOT.war");
            webapp.setAttribute("instance",i);

            if (i>0)
                webapp.setVirtualHosts(new String[] {"127.0.0."+i});
            contexts.addHandler(webapp);
        }
        
        server.start();
        load();
        
        Runtime.getRuntime().gc();
        Runtime.getRuntime().gc();
        
        long used_normal = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        System.err.println(used_normal);
        
        server.stop();
        contexts.setHandlers(null);

        Runtime.getRuntime().gc();
        Runtime.getRuntime().gc();
        
        long used_stopped = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        System.err.println(used_stopped);


        /* Cloud deploy */
        boolean cloud=!Boolean.getBoolean("nocloud");
        Log.info("Cloud deploy");
        final WebAppContext template = new WebAppContext();
        template.setClassLoader(loader);
        template.setBaseResource(baseResource);
        template.setAttribute("instance","-1");
        template.setServer(server);
        template.preConfigure();
        template.configure();
        template.postConfigure();

        ResourceCache cache = new ResourceCache(resources,new MimeTypes(),true);
        
        for (int i=0;i<10;i++)
        {
            final WebAppContext webapp = new WebAppContext(template);
            webapp.setAttribute("resourceCache",cache);
            webapp.setAttribute("instance",i);
            CloudLoader cloud_loader = new CloudLoader((WebAppClassLoader)webapp.getClassLoader());
            // cloud_loader.addPattern("com.acme.");
            // cloud_loader.addPattern("org.eclipse.jetty.util.");
            webapp.setClassLoader(cloud_loader);

            if (i>0)
                webapp.setVirtualHosts(new String[] {"127.0.0."+i});
            contexts.addHandler(webapp);
        }
        server.start();
        load();


        Runtime.getRuntime().gc();
        Runtime.getRuntime().gc();
        
        long used_cloud = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        System.err.println(used_cloud);
        
        server.stop();
        
        System.err.println(used_normal-used_cloud);
    }
    
    private static void load() throws Exception
    {

        for (int j=0;j<10;j++)
        {
            for (int i=0;i<10;i++)
            {
                // generate some load
                for (String uri : new String[] {
                        "/",
                        "/d.txt",
                        "/da.txt",
                        "/dat.txt",
                        "/data.txt",
                        "/data.txt.gz",
                        "/dump/info"    
                })
                {
                    URL url = new URL("http://127.0.0."+(i==0?10:i)+":8080"+uri);
                    String content = String.valueOf(IO.toString(url.openStream()));
                    // System.err.println("GOT "+url+" "+content.length());
                }
            }
        }
    }

}
