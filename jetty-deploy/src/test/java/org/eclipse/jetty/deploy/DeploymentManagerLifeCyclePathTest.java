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

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;

import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.junit.jupiter.api.Test;

public class DeploymentManagerLifeCyclePathTest
{
    @Test
    public void testStateTransitionNewToDeployed() throws Exception
    {
        DeploymentManager depman = new DeploymentManager();
        depman.setContexts(new ContextHandlerCollection());
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
        depman.requestAppGoal(app, "deployed");

        // Setup Expectations.
        List<String> expected = new ArrayList<String>();
        // SHOULD NOT SEE THIS NODE VISITED - expected.add("undeployed");
        expected.add("deploying");
        expected.add("deployed");

        pathtracker.assertExpected("Test StateTransition / New -> Deployed", expected);
    }

    @Test
    public void testStateTransitionReceive() throws Exception
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

        // Perform no goal request.

        // Setup Expectations.
        List<String> expected = new ArrayList<String>();

        pathtracker.assertExpected("Test StateTransition / New only", expected);
    }

    @Test
    public void testStateTransitionDeployedToUndeployed() throws Exception
    {
        DeploymentManager depman = new DeploymentManager();
        depman.setDefaultLifeCycleGoal(null); // no default
        AppLifeCyclePathCollector pathtracker = new AppLifeCyclePathCollector();
        MockAppProvider mockProvider = new MockAppProvider();

        // Setup JMX
        MBeanContainer mbContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        depman.addBean(mbContainer);

        depman.addLifeCycleBinding(pathtracker);
        depman.addAppProvider(mockProvider);
        depman.setContexts(new ContextHandlerCollection());

        // Start DepMan
        depman.start();

        // Trigger new App
        mockProvider.findWebapp("foo-webapp-1.war");

        App app = depman.getAppByOriginId("mock-foo-webapp-1.war");

        // Request Deploy of App
        depman.requestAppGoal(app, "deployed");

        JmxServiceConnection jmxConnection = new JmxServiceConnection();
        jmxConnection.connect();

        MBeanServerConnection mbsConnection = jmxConnection.getConnection();
        ObjectName dmObjName = new ObjectName("org.eclipse.jetty.deploy:type=deploymentmanager,id=0");
        String[] params = new String[]{"mock-foo-webapp-1.war", "undeployed"};
        String[] signature = new String[]{"java.lang.String", "java.lang.String"};
        mbsConnection.invoke(dmObjName, "requestAppGoal", params, signature);

        // Setup Expectations.
        List<String> expected = new ArrayList<String>();
        // SHOULD NOT SEE THIS NODE VISITED - expected.add("undeployed");
        expected.add("deploying");
        expected.add("deployed");
        expected.add("undeploying");
        expected.add("undeployed");

        pathtracker.assertExpected("Test JMX StateTransition / Deployed -> Undeployed", expected);
    }
}
