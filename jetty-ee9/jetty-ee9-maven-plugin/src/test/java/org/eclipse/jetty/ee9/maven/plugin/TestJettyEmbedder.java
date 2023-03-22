//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.maven.plugin;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Disabled
@ExtendWith(WorkDirExtension.class)
public class TestJettyEmbedder
{
    public WorkDir workDir;

    @Test
    public void testJettyEmbedderFromDefaults() throws Exception
    {
        Path baseResource = workDir.getEmptyPathDir();
        MavenWebAppContext webApp = new MavenWebAppContext();
        webApp.setBaseResource(webApp.getResourceFactory().newResource(baseResource));
        MavenServerConnector connector = new MavenServerConnector();
        connector.setPort(0);

        JettyEmbedder jetty = new JettyEmbedder();
        jetty.setHttpConnector(connector);
        jetty.setExitVm(false);
        jetty.setServer(null);
        jetty.setContextHandlers(null);
        jetty.setRequestLog(null);
        jetty.setJettyXmlFiles(null);
        jetty.setJettyProperties(null);
        jetty.setLoginServices(null);
        jetty.setContextXml(MavenTestingUtils.getTargetPath("test-classes/embedder-context.xml").toFile().getAbsolutePath());
        jetty.setWebApp(webApp);

        try
        {
            jetty.start();
            assertEquals("/embedder", webApp.getContextPath());
            assertTrue(webApp.isAvailable());
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
        MavenWebAppContext webApp = new MavenWebAppContext();
        Path baseResource = workDir.getEmptyPathDir();
        webApp.setBaseResource(webApp.getResourceFactory().newResource(baseResource));
        Server server = new Server();
        Map<String, String> jettyProperties = new HashMap<>();
        jettyProperties.put("jetty.server.dumpAfterStart", "false");

        ContextHandler otherHandler = new ContextHandler();
        otherHandler.setContextPath("/other");
        otherHandler.setBaseResource(webApp.getResourceFactory().newResource(MavenTestingUtils.getTargetPath("test-classes/root")));

        MavenServerConnector connector = new MavenServerConnector();
        connector.setPort(0);

        JettyEmbedder jetty = new JettyEmbedder();
        jetty.setHttpConnector(connector);
        jetty.setExitVm(false);
        jetty.setServer(server);
        jetty.setContextHandlers(List.of(otherHandler));
        jetty.setRequestLog(null);
        jetty.setJettyXmlFiles(Collections.singletonList(MavenTestingUtils.getTargetFile("test-classes/embedder-jetty.xml")));
        jetty.setJettyProperties(jettyProperties);
        jetty.setLoginServices(null);
        jetty.setContextXml(MavenTestingUtils.getTargetFile("test-classes/embedder-context.xml").getAbsolutePath());
        jetty.setWebApp(webApp);

        try
        {
            jetty.start();
            assertEquals("/embedder", webApp.getContextPath());
            assertTrue(webApp.isAvailable());
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
