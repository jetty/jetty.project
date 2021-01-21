//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.deploy;

import java.util.Collection;
import java.util.Set;

import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

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
        mockProvider.findWebapp("foo-webapp-1.war");

        // Test app tracking
        Collection<App> apps = depman.getApps();
        assertNotNull(apps, "Should never be null");
        assertEquals(1, apps.size(), "Expected App Count");

        // Test app get
        App actual = depman.getAppByOriginId("mock-foo-webapp-1.war");
        assertNotNull(actual, "Should have gotten app (by id)");
        assertEquals("mock-foo-webapp-1.war", actual.getOriginId(), "Should have gotten app (by id)");
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
