// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.deploy;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.junit.Test;

public class DeploymentManagerLifeCyclePathTest
{
    @Test
    public void testStateTransition_NewToDeployed() throws Exception
    {
        DeploymentManager depman = new DeploymentManager();
        depman.setDefaultLifeCycleGoal(null); // no default
        AppLifeCyclePathCollector pathtracker = new AppLifeCyclePathCollector();
        MockAppProvider mockProvider = new MockAppProvider();

        depman.addLifeCycleBinding(pathtracker);
        depman.addAppProvider(mockProvider);
        depman.setContexts(new ContextHandlerCollection());

        // Start DepMan
        depman.start();

        // Trigger new App
        mockProvider.findWebapp("foo-webapp-1.war");

        App app = depman.getAppByOriginId("mock-foo-webapp-1.war");

        // Request Deploy of App
        depman.requestAppGoal(app,"deployed");

        // Setup Expectations.
        List<String> expected = new ArrayList<String>();
        // SHOULD NOT SEE THIS NODE VISITED - expected.add("undeployed");
        expected.add("deploying");
        expected.add("deployed");

        pathtracker.assertExpected("Test StateTransition / New -> Deployed",expected);
    }

    @Test
    public void testStateTransition_Receive() throws Exception
    {
        DeploymentManager depman = new DeploymentManager();
        depman.setDefaultLifeCycleGoal(null); // no default
        AppLifeCyclePathCollector pathtracker = new AppLifeCyclePathCollector();
        MockAppProvider mockProvider = new MockAppProvider();

        depman.addLifeCycleBinding(pathtracker);
        depman.addAppProvider(mockProvider);

        // Start DepMan
        depman.start();

        // Trigger new App
        mockProvider.findWebapp("foo-webapp-1.war");

        // Perform no goal request.

        // Setup Expectations.
        List<String> expected = new ArrayList<String>();

        pathtracker.assertExpected("Test StateTransition / New only",expected);
    }
}
