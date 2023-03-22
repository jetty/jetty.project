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

import java.util.Collection;
import java.util.Set;

import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.Environment;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(WorkDirExtension.class)
public class DeploymentManagerTest
{
    public WorkDir testdir;

    @Test
    public void testReceiveApp() throws Exception
    {
        DeploymentManager depman = new DeploymentManager();
        depman.setContexts(new ContextHandlerCollection());
        depman.setDefaultLifeCycleGoal(null); // no default
        AppLifeCyclePathCollector pathtracker = new AppLifeCyclePathCollector();
        MockAppProvider mockProvider = new MockAppProvider();

        depman.addLifeCycleBinding(pathtracker);
        depman.addAppProvider(mockProvider);

        // Start DepMan
        depman.start();

        // Trigger new App
        mockProvider.createWebapp("foo-webapp-1.war");

        // Test app tracking
        Collection<App> apps = depman.getApps();
        assertNotNull(apps, "Should never be null");
        assertEquals(1, apps.size(), "Expected App Count");

        // Test app get
        App app = apps.stream().findFirst().orElse(null);
        assertNotNull(app);
        App actual = depman.getApp(app.getPath());
        assertNotNull(actual, "Should have gotten app (by id)");
        assertThat(actual.getPath().toString(), endsWith("mock-foo-webapp-1.war"));
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
    public void testDefaultEnvironment()
    {
        DeploymentManager depman = new DeploymentManager();
        assertThat(depman.getDefaultEnvironmentName(), Matchers.nullValue());

        Environment.ensure("ee7");
        depman.addAppProvider(new MockAppProvider()
        {
            @Override
            public String getEnvironmentName()
            {
                return "ee7";
            }
        });
        assertThat(depman.getDefaultEnvironmentName(), is("ee7"));

        Environment.ensure("ee12");
        depman.addAppProvider(new MockAppProvider()
        {
            @Override
            public String getEnvironmentName()
            {
                return "ee12";
            }
        });
        assertThat(depman.getDefaultEnvironmentName(), is("ee12"));

        Environment.ensure("ee10");
        depman.addAppProvider(new MockAppProvider()
        {
            @Override
            public String getEnvironmentName()
            {
                return "ee12";
            }
        });
        assertThat(depman.getDefaultEnvironmentName(), is("ee12"));

        Environment.ensure("somethingElse");
        depman.addAppProvider(new MockAppProvider()
        {
            @Override
            public String getEnvironmentName()
            {
                return "ee12";
            }
        });
        assertThat(depman.getDefaultEnvironmentName(), is("ee12"));
    }

    @Test
    public void testXmlConfigured() throws Exception
    {
        XmlConfiguredJetty jetty = null;
        try
        {
            jetty = new XmlConfiguredJetty(testdir.getEmptyPathDir());
            jetty.addConfiguration("jetty.xml");
            jetty.addConfiguration("jetty-http.xml");
            jetty.addConfiguration("jetty-deploymgr-contexts.xml");

            // Should not throw an Exception
            jetty.load();

            // Start it
            jetty.start();
        }
        finally
        {
            if (jetty != null)
            {
                try
                {
                    jetty.stop();
                }
                catch (Exception ignore)
                {
                    // ignore
                }
            }
        }
    }
}
