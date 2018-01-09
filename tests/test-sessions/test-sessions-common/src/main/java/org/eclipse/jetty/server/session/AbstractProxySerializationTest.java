//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.server.session;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;

import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.junit.Test;

/**
 * AbstractProxySerializationTest
 *
 * For SessionDataStores that passivate with serialization.
 */
public abstract class AbstractProxySerializationTest extends AbstractTestBase
{
    
    public abstract void customizeContext (ServletContextHandler c);
     
    
    /**
     * @param msec milliseconds to sleep
     */
    public void pause(int msec)
    {
        try
        {
            Thread.sleep(msec);
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }
    
    @Test
    public void testProxySerialization() throws Exception
    {
        String contextPath = "/";
        String servletMapping = "/server";
        int scavengePeriod = 10;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)storeFactory).setGracePeriodSec(scavengePeriod);
        
        TestServer server = new TestServer(0, 20, scavengePeriod,
                                                           cacheFactory, storeFactory);
        ServletContextHandler context = server.addContext(contextPath);

        InputStream is = this.getClass().getClassLoader().getResourceAsStream("proxy-serialization.jar");
        
        File testDir = MavenTestingUtils.getTargetTestingDir("proxy-serialization");
        testDir.mkdirs();
        
        File extractedJar = new File (testDir, "proxy-serialization.jar");
        extractedJar.createNewFile();
        IO.copy(is, new FileOutputStream(extractedJar));
 
        
        URLClassLoader loader = new URLClassLoader(new URL[] {extractedJar.toURI().toURL()}, Thread.currentThread().getContextClassLoader());
        context.setClassLoader(loader);
        context.addServlet("TestServlet", servletMapping);
        customizeContext(context);
        
        try
        {
            server.start();
            int port=server.getPort();
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                ContentResponse response = client.GET("http://localhost:" + port + contextPath + servletMapping.substring(1) + "?action=create");
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);

                //stop the context to be sure the sesssion will be passivated
                context.stop();
                
                //restart the context
                context.start();
               
                // Make another request using the session id from before
                Request request = client.newRequest("http://localhost:" + port + contextPath + servletMapping.substring(1) + "?action=test");
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            server.stop();
        }

    }
}
