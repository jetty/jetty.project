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
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(WorkDirExtension.class)
public class DeploymentManagerTest
{
    /**
     * Cleanup after any tests that modify the Environments singleton
     */
    @AfterEach
    public void clearEnvironments()
    {
        List<String> envnames = Environment.getAll().stream()
            .map(Environment::getName)
            .toList();
        for (String envname : envnames)
        {
            Environment.remove(envname);
        }
        assertEquals(0, Environment.getAll().size());
    }

    @Test
    public void testReceiveApp() throws Exception
    {
        DeploymentManager depman = new DeploymentManager();
        depman.setContexts(new ContextHandlerCollection());
        depman.setDefaultLifeCycleGoal(null); // no default
        AppLifeCyclePathCollector pathtracker = new AppLifeCyclePathCollector();
        MockAppProvider mockProvider = new MockAppProvider();
        mockProvider.setDeploymentManager(depman);

        depman.addLifeCycleBinding(pathtracker);
        depman.addBean(mockProvider);

        // Start DepMan
        depman.start();

        try
        {
            // Trigger new App
            mockProvider.createWebapp("foo-webapp-1.war");

            // Test app tracking
            Collection<App> apps = depman.getApps();
            assertNotNull(apps, "Should never be null");
            assertEquals(1, apps.size(), "Expected App Count");

            // Test app get
            App app = apps.stream().findFirst().orElse(null);
            assertNotNull(app);
            App actual = depman.getApp(app.getName());
            assertNotNull(actual, "Should have gotten app (by id)");
            assertThat(actual.getName(), is("foo-webapp-1"));
        }
        finally
        {
            LifeCycle.stop(depman);
        }
    }

    @Test
    public void testBinding()
    {
        AppLifeCyclePathCollector pathtracker = new AppLifeCyclePathCollector();
        DeploymentManager depman = new DeploymentManager();
        depman.addLifeCycleBinding(pathtracker);

        Set<AppLifeCycle.Binding> allbindings = depman.getLifeCycle().getBindings();
        assertNotNull(allbindings, "All Bindings should never be null");
        assertEquals(1, allbindings.size(), "All Bindings.size");

        Set<AppLifeCycle.Binding> deploybindings = depman.getLifeCycle().getBindings("deploying");
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
