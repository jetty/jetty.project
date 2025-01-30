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

package org.eclipse.jetty.deploy;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;

import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(WorkDirExtension.class)
public class DeploymentManagerTest extends AbstractCleanEnvironmentTest
{
    @Test
    public void testAddContext() throws Exception
    {
        DeploymentManager depman = new DeploymentManager();
        depman.setContexts(new ContextHandlerCollection());
        ContextHandlerLifeCyclePathCollector pathtracker = new ContextHandlerLifeCyclePathCollector();
        PhonyContextProvider provider = new PhonyContextProvider(depman);
        depman.addLifeCycleBinding(pathtracker);

        // Start DepMan
        depman.start();

        try
        {
            // Trigger new context
            ContextHandler foo = provider.createWebapp("foo-webapp-1.war");
            provider.getContextHandlerManagement().addContextHandler(foo, ContextHandlerLifeCycle.UNDEPLOYED);

            // Test context tracking
            Collection<ContextHandler> contexts = depman.getContextHandlers();
            assertNotNull(contexts, "Should never be null");
            assertEquals(1, contexts.size(), "Expected Context Count");

            // Test context find
            ContextHandler context = contexts.stream().findFirst().orElse(null);
            assertNotNull(context);
            ContextHandler actual = depman.findContextHandler(context.getID());
            assertNotNull(actual, "Should have gotten ContextHandler (by id)");
            assertThat(actual.getID(), is("foo-webapp-1"));
        }
        finally
        {
            LifeCycle.stop(depman);
        }
    }

    @Test
    public void testBinding()
    {
        ContextHandlerLifeCyclePathCollector pathtracker = new ContextHandlerLifeCyclePathCollector();
        DeploymentManager depman = new DeploymentManager();
        depman.addLifeCycleBinding(pathtracker);

        Set<ContextHandlerLifeCycle.Binding> allbindings = depman.getLifeCycle().getBindings();
        assertNotNull(allbindings, "All Bindings should never be null");
        assertEquals(1, allbindings.size(), "All Bindings.size");

        Set<ContextHandlerLifeCycle.Binding> deploybindings = depman.getLifeCycle().getBindings("deploying");
        assertNotNull(deploybindings, "'deploying' Bindings should not be null");
        assertEquals(1, deploybindings.size(), "'deploying' Bindings.size");
    }

    @Test
    public void testXmlConfigured(WorkDir workDir) throws Exception
    {
        Path testdir = workDir.getEmptyPathDir();
        XmlConfiguredJetty jetty = null;
        try
        {
            jetty = new XmlConfiguredJetty(testdir);
            jetty.addConfiguration(MavenPaths.findTestResourceFile("jetty.xml"));
            jetty.addConfiguration(MavenPaths.findTestResourceFile("jetty-http.xml"));
            jetty.addConfiguration(MavenPaths.projectBase().resolve("src/main/config/etc/jetty-deployment-manager.xml"));
            jetty.addConfiguration(MavenPaths.projectBase().resolve("src/main/config/etc/jetty-deploy.xml"));
            jetty.addConfiguration(MavenPaths.findTestResourceFile("jetty-core-deploy-custom.xml"));

            // Should not throw an Exception
            jetty.load();

            // Start it
            jetty.start();
        }
        finally
        {
            if (jetty != null)
            {
                jetty.stop();
            }
        }
    }
}
