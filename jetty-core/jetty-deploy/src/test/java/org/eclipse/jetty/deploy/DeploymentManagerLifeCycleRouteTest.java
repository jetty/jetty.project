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

import org.eclipse.jetty.deploy.internal.DeploymentGraph;
import org.eclipse.jetty.deploy.internal.DeploymentGraphNodeOrderCollector;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.toolchain.test.ExtraMatchers.ordered;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class DeploymentManagerLifeCycleRouteTest
{
    @Test
    public void testStateTransitionNewToDeployed() throws Exception
    {
        DeploymentManager depman = new DeploymentManager();
        depman.setContexts(new ContextHandlerCollection());
        DeploymentGraphNodeOrderCollector pathtracker = new DeploymentGraphNodeOrderCollector();

        depman.addLifeCycleBinding(pathtracker);
        depman.setContexts(new ContextHandlerCollection());

        // Start DepMan
        depman.start();

        // Trigger new ContextHandler
        ContextHandler foo = Util.createContextHandler("foo-webapp-1.war");
        depman.addUndeployed(foo);

        // Verify the undeployed state
        assertThat("Tracking.size", depman.getContextHandlers().size(), is(1));
        assertThat("ContextHandlerCollection.handlers.size", depman.getContexts().getHandlers().size(), is(0));

        List<ContextHandler> undeployedContexts = depman.getContextHandlers(DeploymentGraph.UNDEPLOYED)
            .stream()
            .toList();
        List<ContextHandler> expectedContexts = List.of(foo);
        assertThat(undeployedContexts, ordered(expectedContexts));

        // Move to Deployed of ContextHandler
        depman.move(foo, DeploymentGraph.DEPLOYED);

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
        DeploymentGraphNodeOrderCollector pathtracker = new DeploymentGraphNodeOrderCollector();
        depman.addLifeCycleBinding(pathtracker);

        // Start DepMan
        depman.start();

        // Create new ContextHandler
        ContextHandler contextHandler = Util.createContextHandler("foo-webapp-1.war");
        depman.addUndeployed(contextHandler);

        // Perform no goal request.

        // Setup Expectations.
        List<String> expected = new ArrayList<>();

        pathtracker.assertExpected("Test StateTransition / New only", expected);
    }

    @Test
    @Disabled("Not working yet, need to figure out how to reference the ContextHandler mbean")
    public void testMBeanStateTransitionToUndeployed() throws Exception
    {
        DeploymentManager depman = new DeploymentManager();
        DeploymentGraphNodeOrderCollector pathtracker = new DeploymentGraphNodeOrderCollector();

        // Setup JMX
        MBeanContainer mbContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        depman.addBean(mbContainer);

        depman.addLifeCycleBinding(pathtracker);
        depman.setContexts(new ContextHandlerCollection());

        // Start DepMan
        depman.start();

        // Create new ContextHandler
        ContextHandler foo = Util.createContextHandler("foo-webapp-1");
        depman.addUndeployed(foo);

        // Verify the undeployed state
        assertThat("Tracking.size", depman.getContextHandlers().size(), is(1));
        assertThat("ContextHandlerCollection.handlers.size", depman.getContexts().getHandlers().size(), is(0));

        List<ContextHandler> undeployedContexts = depman.getContextHandlers(DeploymentGraph.UNDEPLOYED)
            .stream()
            .toList();
        List<ContextHandler> expectedContexts = List.of(foo);
        assertThat(undeployedContexts, ordered(expectedContexts));

        // Move to Deployed of ContextHandler
        depman.move(foo, DeploymentGraph.DEPLOYED);

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
