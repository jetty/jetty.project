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

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;

import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.junit.jupiter.api.Test;

public class DeploymentManagerLifeCycleRouteTest
{
    @Test
    public void testStateTransitionNewToDeployed() throws Exception
    {
        DeploymentManager depman = new DeploymentManager();
        depman.setContexts(new ContextHandlerCollection());
        ContextHandlerLifeCyclePathCollector pathtracker = new ContextHandlerLifeCyclePathCollector();
        PhonyContextProvider provider = new PhonyContextProvider(depman);

        depman.addLifeCycleBinding(pathtracker);
        depman.setContexts(new ContextHandlerCollection());

        // Start DepMan
        depman.start();

        // Trigger new ContextHandler
        ContextHandler foo = provider.createWebapp("foo-webapp-1.war");
        String id = foo.getID();
        provider.getContextHandlerManagement().addContextHandler(foo, ContextHandlerLifeCycle.UNDEPLOYED);

        // Request Deploy of ContextHandler
        ContextHandler app = depman.findContextHandler(id);
        depman.requestContextHandlerGoal(app, ContextHandlerLifeCycle.DEPLOYED);

        // Setup Expectations.
        List<String> expected = new ArrayList<>();
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
        ContextHandlerLifeCyclePathCollector pathtracker = new ContextHandlerLifeCyclePathCollector();
        PhonyContextProvider provider = new PhonyContextProvider(depman);
        depman.addLifeCycleBinding(pathtracker);

        // Start DepMan
        depman.start();

        // Create new App
        ContextHandler contextHandler = provider.createWebapp("foo-webapp-1.war");
        provider.getContextHandlerManagement().addContextHandler(contextHandler, ContextHandlerLifeCycle.UNDEPLOYED);

        // Perform no goal request.

        // Setup Expectations.
        List<String> expected = new ArrayList<>();

        pathtracker.assertExpected("Test StateTransition / New only", expected);
    }

    @Test
    public void testStateTransitionDeployedToUndeployed() throws Exception
    {
        DeploymentManager depman = new DeploymentManager();
        ContextHandlerLifeCyclePathCollector pathtracker = new ContextHandlerLifeCyclePathCollector();
        PhonyContextProvider mockProvider = new PhonyContextProvider(depman);

        // Setup JMX
        MBeanContainer mbContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        depman.addBean(mbContainer);

        depman.addLifeCycleBinding(pathtracker);
        depman.setContexts(new ContextHandlerCollection());

        // Start DepMan
        depman.start();

        // Create new ContextHandler
        ContextHandler foo = mockProvider.createWebapp("foo-webapp-1");
        String id = foo.getID();
        mockProvider.getContextHandlerManagement().addContextHandler(foo, ContextHandlerLifeCycle.UNDEPLOYED);

        // Request Deploy of ContextHandler
        ContextHandler app = depman.findContextHandler(id);
        depman.requestContextHandlerGoal(app, ContextHandlerLifeCycle.DEPLOYED);

        JmxServiceConnection jmxConnection = new JmxServiceConnection();
        jmxConnection.connect();

        MBeanServerConnection mbsConnection = jmxConnection.getConnection();
        ObjectName dmObjName = new ObjectName("org.eclipse.jetty.deploy:type=deploymentmanager,id=0");
        String[] params = new String[]{"foo-webapp-1", "undeployed"};
        String[] signature = new String[]{"java.lang.String", "java.lang.String"};
        mbsConnection.invoke(dmObjName, "requestContextHandlerGoal", params, signature);

        // Setup Expectations.
        List<String> expected = new ArrayList<>();
        // SHOULD NOT SEE THIS NODE VISITED - expected.add("undeployed");
        expected.add("deploying");
        expected.add("deployed");
        expected.add("undeploying");
        expected.add("undeployed");

        pathtracker.assertExpected("Test JMX StateTransition / Deployed -> Undeployed", expected);
    }
}
