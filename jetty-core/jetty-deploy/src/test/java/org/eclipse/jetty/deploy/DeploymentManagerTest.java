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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.deploy.internal.DeploymentLifeCyclePathCollector;
import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.eclipse.jetty.toolchain.test.ExtraMatchers.ordered;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class DeploymentManagerTest extends AbstractCleanEnvironmentTest
{
    @Test
    public void testAddUndeployed() throws Exception
    {
        DeploymentManager depman = new DeploymentManager();
        depman.setContexts(new ContextHandlerCollection());
        DeploymentLifeCyclePathCollector pathtracker = new DeploymentLifeCyclePathCollector();
        depman.addLifeCycleBinding(pathtracker);

        // Start DepMan
        depman.start();

        try
        {
            // Trigger new context
            ContextHandler foo = Util.createContextHandler("foo-webapp-1.war");
            depman.addUndeployed(foo);
            assertFalse(foo.isStarted());

            // Test context tracking
            Collection<ContextHandler> contextHandlers = depman.getContextHandlers();
            assertThat("contextHandlers.size", contextHandlers.size(), is(1));
            ContextHandler first = contextHandlers.iterator().next();
            assertThat("contextHandler", first, equalTo(foo));

            // Verify that context is in expected graph node
            List<ContextHandler> undeployedContexts = depman.getContextHandlers("undeployed")
                .stream()
                .toList();
            List<ContextHandler> expectedContexts = List.of(foo);
            assertThat(undeployedContexts, ordered(expectedContexts));
        }
        finally
        {
            LifeCycle.stop(depman);
        }
    }

    @Test
    public void testDeploy() throws Exception
    {
        DeploymentManager depman = new DeploymentManager();
        depman.setContexts(new ContextHandlerCollection());
        DeploymentLifeCyclePathCollector pathtracker = new DeploymentLifeCyclePathCollector();
        depman.addLifeCycleBinding(pathtracker);

        // Start DepMan
        depman.start();

        try
        {
            // Trigger new context
            ContextHandler foo = Util.createContextHandler("foo-webapp-1.war");
            depman.deploy(foo);
            assertTrue(foo.isStarted());

            // Test context tracking
            Collection<ContextHandler> contextHandlers = depman.getContextHandlers();
            assertThat("contextHandlers.size", contextHandlers.size(), is(1));
            ContextHandler first = contextHandlers.iterator().next();
            assertThat("contextHandler", first, equalTo(foo));

            // Verify that context is in expected graph node
            List<ContextHandler> startedContexts = depman.getContextHandlers("started")
                .stream()
                .toList();
            List<ContextHandler> expectedContexts = List.of(foo);
            assertThat(startedContexts, ordered(expectedContexts));

            // Verify that the graph only has one entry, and it's on started.
            List<String> state = getGraphState(depman);
            List<String> expected = List.of(
                "started|/foo-webapp-1"
            );
            assertThat(state, ordered(expected));
        }
        finally
        {
            LifeCycle.stop(depman);
        }
    }

    @Test
    public void testUndeploy() throws Exception
    {
        DeploymentManager depman = new DeploymentManager();
        depman.setContexts(new ContextHandlerCollection());
        DeploymentLifeCyclePathCollector pathtracker = new DeploymentLifeCyclePathCollector();
        depman.addLifeCycleBinding(pathtracker);

        // Start DepMan
        depman.start();

        try
        {
            // Trigger deploy
            ContextHandler foo = Util.createContextHandler("foo-webapp-1.war");
            depman.deploy(foo);
            assertTrue(foo.isStarted());

            // Test context tracking
            Collection<ContextHandler> contextHandlers = depman.getContextHandlers();
            assertThat("contextHandlers.size", contextHandlers.size(), is(1));
            ContextHandler first = contextHandlers.iterator().next();
            assertThat("contextHandler", first, equalTo(foo));

            List<ContextHandler> expectedContexts = List.of(foo);

            // Verify that context is in expected graph node
            List<ContextHandler> startedContexts = depman.getContextHandlers("started")
                .stream()
                .toList();
            assertThat(startedContexts, ordered(expectedContexts));

            // Trigger undeploy
            depman.undeploy(foo);

            // Test context tracking (the context should have been removed)
            contextHandlers = depman.getContextHandlers();
            assertThat("contextHandlers.size", contextHandlers.size(), is(0));

            // Verify that the graph is empty now (that was the last context undeployed)
            List<String> graphState = getGraphState(depman);
            assertTrue(graphState.isEmpty());
        }
        finally
        {
            LifeCycle.stop(depman);
        }
    }

    @Test
    public void testMove() throws Exception
    {
        DeploymentManager depman = new DeploymentManager();
        depman.setContexts(new ContextHandlerCollection());
        DeploymentLifeCyclePathCollector pathtracker = new DeploymentLifeCyclePathCollector();
        depman.addLifeCycleBinding(pathtracker);

        // Start DepMan
        depman.start();

        try
        {
            // Trigger deploy
            ContextHandler foo = Util.createContextHandler("foo-webapp-1.war");
            depman.deploy(foo);
            assertTrue(foo.isStarted());

            // Test context tracking
            Collection<ContextHandler> contextHandlers = depman.getContextHandlers();
            assertThat("contextHandlers.size", contextHandlers.size(), is(1));
            ContextHandler first = contextHandlers.iterator().next();
            assertThat("contextHandler", first, equalTo(foo));

            List<ContextHandler> expectedContexts = List.of(foo);

            // Verify that context is in expected graph node
            List<ContextHandler> startedContexts = depman.getContextHandlers("started")
                .stream()
                .toList();
            assertThat(startedContexts, ordered(expectedContexts));

            // Trigger undeploy
            depman.move(foo, "deployed");

            // Test context tracking (the context should have been removed)
            contextHandlers = depman.getContextHandlers();
            assertThat("contextHandlers.size", contextHandlers.size(), is(1));
            first = contextHandlers.iterator().next();
            assertThat("contextHandler", first, equalTo(foo));

            // Verify that the graph only has one entry, and it's on deployed.
            List<String> state = getGraphState(depman);
            List<String> expected = List.of(
                "deployed|/foo-webapp-1"
            );
            assertThat(state, ordered(expected));
        }
        finally
        {
            LifeCycle.stop(depman);
        }
    }

    private List<String> getGraphState(DeploymentManager depman)
    {
        List<String> state = new ArrayList<>();
        for (String nodeName : depman.getNodeNames())
        {
            for (ContextHandler contextHandler : depman.getContextHandlers(nodeName))
            {
                state.add("%s|%s".formatted(nodeName, contextHandler.getContextPath()));
            }
        }
        Collections.sort(state);
        return state;
    }

    @Test
    public void testBinding()
    {
        DeploymentLifeCyclePathCollector pathtracker = new DeploymentLifeCyclePathCollector();
        DeploymentManager depman = new DeploymentManager();
        depman.addLifeCycleBinding(pathtracker);

        Set<DeploymentNodeBinding> allbindings = depman.getBindings();
        assertNotNull(allbindings, "All Bindings should never be null");
        assertEquals(1, allbindings.size(), "All Bindings.size");

        Set<DeploymentNodeBinding> deploybindings = depman.getBindings("deploying");
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
