//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class DeploymentManagerTest
{
    @Rule
    public TestingDir testdir = new TestingDir();

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
        Assert.assertNotNull("Should never be null",apps);
        Assert.assertEquals("Expected App Count",1,apps.size());

        // Test app get
        App actual = depman.getAppByOriginId("mock-foo-webapp-1.war");
        Assert.assertNotNull("Should have gotten app (by id)",actual);
        Assert.assertEquals("Should have gotten app (by id)","mock-foo-webapp-1.war",actual.getOriginId());
    }

    @Test
    public void testBinding()
    {
        AppLifeCyclePathCollector pathtracker = new AppLifeCyclePathCollector();
        DeploymentManager depman = new DeploymentManager();
        depman.addLifeCycleBinding(pathtracker);

        Set<AppLifeCycle.Binding> allbindings = depman.getLifeCycle().getBindings();
        Assert.assertNotNull("All Bindings should never be null",allbindings);
        Assert.assertEquals("All Bindings.size",1,allbindings.size());

        Set<AppLifeCycle.Binding> deploybindings = depman.getLifeCycle().getBindings("deploying");
        Assert.assertNotNull("'deploying' Bindings should not be null",deploybindings);
        Assert.assertEquals("'deploying' Bindings.size",1,deploybindings.size());
    }

    @Test
    public void testXmlConfigured() throws Exception
    {
        XmlConfiguredJetty jetty = null;
        try
        {
            jetty = new XmlConfiguredJetty(testdir);
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
