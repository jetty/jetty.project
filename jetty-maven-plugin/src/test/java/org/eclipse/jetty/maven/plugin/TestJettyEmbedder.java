//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.maven.plugin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 
 *
 */
public class TestJettyEmbedder
{

    @Test
    public void testJettyEmbedderFromDefaults() throws Exception
    {
        JettyWebAppContext webApp = new JettyWebAppContext();
        
        JettyEmbedder jetty = new JettyEmbedder();
        jetty.setExitVm(false);
        jetty.setServer(null);
        jetty.setContextHandlers(null);
        jetty.setRequestLog(null);
        jetty.setJettyXmlFiles(null);
        jetty.setHttpConnector(null);
        jetty.setJettyProperties(null);
        jetty.setLoginServices(null);
        jetty.setContextXml(MavenTestingUtils.getTestResourceFile("embedder-context.xml").getAbsolutePath());
        jetty.setWebApp(webApp);
        
        try
        {
            jetty.start();
            assertEquals("/embedder", webApp.getContextPath());
            assertTrue(webApp.isStarted());
            assertNotNull(jetty.getServer());
            assertTrue(jetty.getServer().isStarted());
            assertNotNull(jetty.getServer().getConnectors());
            assertNotNull(ServerSupport.findContextHandlerCollection(jetty.getServer()));
        }
        finally
        {
            jetty.stop();
        }
    }
    
    @Test
    public void testJettyEmbedder()
        throws Exception
    {
        JettyWebAppContext webApp = new JettyWebAppContext();
        Server server = new Server();
        Map<String,String> jettyProperties = new HashMap<>();
        jettyProperties.put("jetty.server.dumpAfterStart", "true");

        ContextHandler otherHandler = new ContextHandler();
        otherHandler.setContextPath("/other");
        otherHandler.setBaseResource(Resource.newResource(MavenTestingUtils.getTestResourceDir("root")));
        
        JettyEmbedder jetty = new JettyEmbedder();
        jetty.setExitVm(false);
        jetty.setServer(server);
        jetty.setContextHandlers(Arrays.asList(otherHandler));
        jetty.setRequestLog(null);
        jetty.setJettyXmlFiles(Arrays.asList(MavenTestingUtils.getTestResourceFile("embedder-jetty.xml")));
        jetty.setHttpConnector(null);
        jetty.setJettyProperties(jettyProperties);
        jetty.setLoginServices(null);
        jetty.setContextXml(MavenTestingUtils.getTestResourceFile("embedder-context.xml").getAbsolutePath());
        jetty.setWebApp(webApp);

        try
        {
            jetty.start();
            assertEquals("/embedder", webApp.getContextPath());
            assertTrue(webApp.isStarted());
            assertNotNull(jetty.getServer());
            assertTrue(jetty.getServer().isStarted());
            assertNotNull(jetty.getServer().getConnectors());
            ContextHandlerCollection contexts = ServerSupport.findContextHandlerCollection(jetty.getServer());
            assertNotNull(contexts);
            assertTrue(contexts.contains(otherHandler));
            assertTrue(contexts.contains(webApp));
        }
        finally
        {
            jetty.stop();
        }
    }
}
