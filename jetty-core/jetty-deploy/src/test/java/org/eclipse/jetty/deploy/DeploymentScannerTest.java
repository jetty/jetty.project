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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.eclipse.jetty.deploy.DeploymentScanner.DeployAction;
import org.eclipse.jetty.deploy.DeploymentScanner.PathsApp;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.toolchain.test.ExtraMatchers;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Scanner;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(WorkDirExtension.class)
public class DeploymentScannerTest extends AbstractCleanEnvironmentTest
{
    public WorkDir workDir;

    public static class AssertActionListDeploymentScanner extends DeploymentScanner
    {
        Consumer<List<DeployAction>> assertActionList;

        public AssertActionListDeploymentScanner()
        {
            super(new Server(), new StandardDeployer(new ContextHandlerCollection()));
        }

        @Override
        protected void performActions(List<DeployAction> actions)
        {
            assertActionList.accept(actions);

            // Perform post performActions cleanup that normally happens
            for (DeployAction action : actions)
            {
                resetAppState(action.name());
            }
        }

        public PathsApp findApp(String name)
        {
            return super.findApp(name);
        }
    }

    @Test
    public void testActionListNewXmlOnly() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);

        AssertActionListDeploymentScanner deploymentScanner = new AssertActionListDeploymentScanner();
        deploymentScanner.addMonitoredDirectory(dir);

        Map<Path, Scanner.Notification> changeSet = new HashMap<>();
        changeSet.put(xml, Scanner.Notification.ADDED);

        deploymentScanner.assertActionList = (actions) ->
        {
            assertThat("actions.size", actions.size(), is(1));
            Iterator<DeployAction> iterator = actions.iterator();
            DeployAction action;

            action = iterator.next();
            assertThat("action.name", action.name(), is("bar"));
            assertThat("action.type", action.type(), is(DeployAction.Type.DEPLOY));
            PathsApp app = deploymentScanner.findApp(action.name());
            assertThat("action.app.state", app.getState(), is(PathsApp.State.ADDED));
            assertThat("action.app.paths", app.getPaths().keySet(), Matchers.contains(xml));
            assertThat("action.app.mainPath", app.getMainPath(), is(xml));
        };

        deploymentScanner.pathsChanged(changeSet);
    }

    @Test
    public void testActionListXmlThenRemoved() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path xml = dir.resolve("foo.xml");
        Files.writeString(xml, "XML for foo", UTF_8);

        AssertActionListDeploymentScanner deploymentScanner = new AssertActionListDeploymentScanner();
        deploymentScanner.addMonitoredDirectory(dir);

        // Initial deployment.
        Map<Path, Scanner.Notification> changeSet = new HashMap<>();
        changeSet.put(xml, Scanner.Notification.ADDED);

        deploymentScanner.assertActionList = (actions) ->
        {
            assertThat("actions.size", actions.size(), is(1));
            Iterator<DeployAction> iterator = actions.iterator();
            DeployAction action;

            action = iterator.next();
            assertThat("action.name", action.name(), is("foo"));
            assertThat("action.type", action.type(), is(DeployAction.Type.DEPLOY));
            PathsApp app = deploymentScanner.findApp(action.name());
            assertThat("action.app.state", app.getState(), is(PathsApp.State.ADDED));
            assertThat("action.app.paths", app.getPaths().keySet(), contains(xml));
            assertThat("action.app.paths[xml].state", app.getPaths().get(xml), is(PathsApp.State.ADDED));
            assertThat("action.app.mainPath", app.getMainPath(), is(xml));
        };

        deploymentScanner.pathsChanged(changeSet);

        // Removed only deployment file.
        Files.deleteIfExists(xml);
        changeSet.clear();
        changeSet.put(xml, Scanner.Notification.REMOVED);

        deploymentScanner.assertActionList = (actions) ->
        {
            assertThat("actions.size", actions.size(), is(1));
            Iterator<DeployAction> iterator = actions.iterator();
            DeployAction action;

            action = iterator.next();
            assertThat("action.name", action.name(), is("foo"));
            assertThat("action.type", action.type(), is(DeployAction.Type.UNDEPLOY));
            PathsApp app = deploymentScanner.findApp(action.name());
            assertThat("action.app.state", app.getState(), is(PathsApp.State.REMOVED));
            assertThat("action.app.paths", app.getPaths().keySet(), contains(xml));
            assertThat("action.app.paths[xml].state", app.getPaths().get(xml), is(PathsApp.State.REMOVED));
            assertThat("action.app.mainPath", app.getMainPath(), is(nullValue()));
        };

        deploymentScanner.pathsChanged(changeSet);
    }

    @Test
    public void testActionListNewXmlAndWarOnly() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);
        Path war = dir.resolve("bar.war");
        Files.writeString(war, "WAR for bar", UTF_8);

        AssertActionListDeploymentScanner deploymentScanner = new AssertActionListDeploymentScanner();
        deploymentScanner.addMonitoredDirectory(dir);

        // Initial deployment
        Map<Path, Scanner.Notification> changeSet = new HashMap<>();
        changeSet.put(xml, Scanner.Notification.ADDED);
        changeSet.put(war, Scanner.Notification.ADDED);

        deploymentScanner.assertActionList = (actions) ->
        {
            assertThat("actions.size", actions.size(), is(1));
            Iterator<DeployAction> iterator = actions.iterator();
            DeployAction action;

            action = iterator.next();

            assertThat("action.name", action.name(), is("bar"));
            assertThat("action.type", action.type(), is(DeployAction.Type.DEPLOY));
            PathsApp app = deploymentScanner.findApp(action.name());
            assertThat("action.app.state", app.getState(), is(PathsApp.State.ADDED));
            assertThat("action.app.paths", app.getPaths().keySet(), containsInAnyOrder(xml, war));
            assertThat("action.app.paths[xml].state", app.getPaths().get(xml), is(PathsApp.State.ADDED));
            assertThat("action.app.paths[war].state", app.getPaths().get(war), is(PathsApp.State.ADDED));
            assertThat("action.app.mainPath", app.getMainPath(), is(xml));
        };

        deploymentScanner.pathsChanged(changeSet);
    }

    @Test
    public void testActionListXmlAndWarWithXmlUpdate() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);
        Path war = dir.resolve("bar.war");
        Files.writeString(war, "WAR for bar", UTF_8);

        AssertActionListDeploymentScanner deploymentScanner = new AssertActionListDeploymentScanner();
        deploymentScanner.addMonitoredDirectory(dir);

        // Initial deployment
        Map<Path, Scanner.Notification> changeSet = new HashMap<>();
        changeSet.put(xml, Scanner.Notification.ADDED);
        changeSet.put(war, Scanner.Notification.ADDED);

        deploymentScanner.assertActionList = (actions) ->
        {
            assertThat("actions.size", actions.size(), is(1));
            Iterator<DeployAction> iterator = actions.iterator();
            DeployAction action;

            action = iterator.next();
            assertThat("action.name", action.name(), is("bar"));
            assertThat("action.type", action.type(), is(DeployAction.Type.DEPLOY));
            PathsApp app = deploymentScanner.findApp(action.name());
            assertThat("action.app.state", app.getState(), is(PathsApp.State.ADDED));
            assertThat("action.app.paths", app.getPaths().keySet(), containsInAnyOrder(xml, war));
            assertThat("action.app.paths[xml].state", app.getPaths().get(xml), is(PathsApp.State.ADDED));
            assertThat("action.app.paths[war].state", app.getPaths().get(war), is(PathsApp.State.ADDED));
            assertThat("action.app.mainPath", app.getMainPath(), is(xml));
        };

        deploymentScanner.pathsChanged(changeSet);

        // Change/Touch war
        changeSet = new HashMap<>();
        changeSet.put(war, Scanner.Notification.CHANGED);

        deploymentScanner.assertActionList = (actions) ->
        {
            assertThat("actions.size", actions.size(), is(1));
            Iterator<DeployAction> iterator = actions.iterator();
            DeployAction action;

            action = iterator.next();
            assertThat("action.name", action.name(), is("bar"));
            assertThat("action.type", action.type(), is(DeployAction.Type.REDEPLOY));
            PathsApp app = deploymentScanner.findApp(action.name());
            assertThat("action.app.state", app.getState(), is(PathsApp.State.CHANGED));
            assertThat("action.app.paths", app.getPaths().keySet(), containsInAnyOrder(xml, war));
            assertThat("action.app.paths[xml].state", app.getPaths().get(xml), is(PathsApp.State.UNCHANGED));
            assertThat("action.app.paths[war].state", app.getPaths().get(war), is(PathsApp.State.CHANGED));
            assertThat("action.app.mainPath", app.getMainPath(), is(xml));
        };

        deploymentScanner.pathsChanged(changeSet);
    }

    @Test
    public void testActionListXmlAndWarWithXmlRemoved() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);
        Path war = dir.resolve("bar.war");
        Files.writeString(war, "WAR for bar", UTF_8);

        AssertActionListDeploymentScanner deploymentScanner = new AssertActionListDeploymentScanner();
        deploymentScanner.addMonitoredDirectory(dir);

        // Initial deployment
        Map<Path, Scanner.Notification> changeSet = new HashMap<>();
        changeSet.put(xml, Scanner.Notification.ADDED);
        changeSet.put(war, Scanner.Notification.ADDED);

        deploymentScanner.assertActionList = (actions) ->
        {
            assertThat("actions.size", actions.size(), is(1));
            Iterator<DeployAction> iterator = actions.iterator();
            DeployAction action;

            action = iterator.next();
            assertThat("action.name", action.name(), is("bar"));
            assertThat("action.type", action.type(), is(DeployAction.Type.DEPLOY));
            PathsApp app = deploymentScanner.findApp(action.name());
            assertThat("action.app.state", app.getState(), is(PathsApp.State.ADDED));
            assertThat("action.app.paths", app.getPaths().keySet(), containsInAnyOrder(xml, war));
            assertThat("action.app.paths[xml].state", app.getPaths().get(xml), is(PathsApp.State.ADDED));
            assertThat("action.app.paths[war].state", app.getPaths().get(war), is(PathsApp.State.ADDED));
            assertThat("action.app.mainPath", app.getMainPath(), is(xml));
        };

        deploymentScanner.pathsChanged(changeSet);

        // Change/Touch war and xml
        changeSet = new HashMap<>();
        changeSet.put(war, Scanner.Notification.CHANGED);
        changeSet.put(xml, Scanner.Notification.CHANGED);

        deploymentScanner.assertActionList = (actions) ->
        {
            assertThat("actions.size", actions.size(), is(1));
            Iterator<DeployAction> iterator = actions.iterator();
            DeployAction action;

            action = iterator.next();
            assertThat("action.name", action.name(), is("bar"));
            assertThat("action.type", action.type(), is(DeployAction.Type.REDEPLOY));
            PathsApp app = deploymentScanner.findApp(action.name());
            assertThat("action.app.state", app.getState(), is(PathsApp.State.CHANGED));
            assertThat("action.app.paths", app.getPaths().keySet(), containsInAnyOrder(xml, war));
            assertThat("action.app.paths[xml].state", app.getPaths().get(xml), is(PathsApp.State.CHANGED));
            assertThat("action.app.paths[war].state", app.getPaths().get(war), is(PathsApp.State.CHANGED));
            assertThat("action.app.mainPath", app.getMainPath(), is(xml));
        };

        deploymentScanner.pathsChanged(changeSet);

        // Delete XML (now only war exists)
        Files.deleteIfExists(xml);
        changeSet = new HashMap<>();
        changeSet.put(xml, Scanner.Notification.REMOVED);

        deploymentScanner.assertActionList = (actions) ->
        {
            assertThat("actions.size", actions.size(), is(1));
            Iterator<DeployAction> iterator = actions.iterator();
            DeployAction action;

            action = iterator.next();
            assertThat("action.name", action.name(), is("bar"));
            assertThat("action.type", action.type(), is(DeployAction.Type.REDEPLOY));
            PathsApp app = deploymentScanner.findApp(action.name());
            assertThat("action.app.state", app.getState(), is(PathsApp.State.CHANGED));
            assertThat("action.app.paths", app.getPaths().keySet(), containsInAnyOrder(xml, war));
            assertThat("action.app.paths[xml].state", app.getPaths().get(xml), is(PathsApp.State.REMOVED));
            assertThat("action.app.paths[war].state", app.getPaths().get(war), is(PathsApp.State.UNCHANGED));
            assertThat("action.app.mainPath", app.getMainPath(), is(war));
        };

        deploymentScanner.pathsChanged(changeSet);

        // Delete WAR
        Files.deleteIfExists(war);
        changeSet = new HashMap<>();
        changeSet.put(war, Scanner.Notification.REMOVED);

        deploymentScanner.assertActionList = (actions) ->
        {
            assertThat("actions.size", actions.size(), is(1));
            Iterator<DeployAction> iterator = actions.iterator();
            DeployAction action;

            action = iterator.next();
            assertThat("action.name", action.name(), is("bar"));
            assertThat("action.type", action.type(), is(DeployAction.Type.UNDEPLOY));
            PathsApp app = deploymentScanner.findApp(action.name());
            assertThat("action.app.state", app.getState(), is(PathsApp.State.REMOVED));
            assertThat("action.app.paths", app.getPaths().keySet(), containsInAnyOrder(war));
            assertThat("action.app.paths[war].state", app.getPaths().get(war), is(PathsApp.State.REMOVED));
            assertThat("action.app.mainPath", app.getMainPath(), is(nullValue()));
        };

        deploymentScanner.pathsChanged(changeSet);
    }

    @Test
    public void testDefaultWeights()
    {
        DeploymentScanner deploymentScanner = new DeploymentScanner(new Server());
        assertThat(deploymentScanner.getDefaultWeight("core"), is(200));
        assertThat(deploymentScanner.getDefaultWeight("ee9"), is(900));
        assertThat(deploymentScanner.getDefaultWeight("ee11"), is(1100));

        assertThrows(IllegalStateException.class, () -> deploymentScanner.getDefaultWeight("corp"));
    }

    public static Stream<Arguments> envNameSorting()
    {
        return Stream.of(
            Arguments.of(Map.of("static", 100, "core", 200), List.of("core", "static")),
            Arguments.of(Map.of("core", 200, "static", 100), List.of("core", "static")),
            Arguments.of(Map.of("core", 200, "ee11", 1011), List.of("ee11", "core")),
            Arguments.of(Map.of("core", 200, "ee11", 1011, "ee9", 1009, "ee10", 1010), List.of("ee11", "ee10", "ee9", "core"))
        );
    }

    @ParameterizedTest
    @MethodSource("envNameSorting")
    public void testEnvironmentNameSorting(Map<String, Integer> input, List<String> expected)
    {
        DeploymentScanner deploymentScanner = new DeploymentScanner(new Server());
        for (Map.Entry<String, Integer> entry : input.entrySet())
        {
            deploymentScanner.addTrackedEnvironment(entry.getKey(), entry.getValue());
        }
        List<String> sorted = deploymentScanner.getTrackedEnvironmentsByWeight();
        assertThat(sorted, ExtraMatchers.ordered(expected));
    }
}
