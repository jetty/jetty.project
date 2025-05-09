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
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.StaticContextHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.component.LifeCycle;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

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
        void performActions(List<DeployAction> actions)
        {
            assertActionList.accept(actions);

            // Perform post performActions cleanup that normally happens
            for (DeployAction action : actions)
            {
                resetAppState(action.name());
            }
        }
    }

    @Test
    public void testActionListNewXmlOnly() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);

        AssertActionListDeploymentScanner deploymentScanner = new AssertActionListDeploymentScanner();
        deploymentScanner.addWebappsDirectory(dir);

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
        deploymentScanner.addWebappsDirectory(dir);

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
        deploymentScanner.addWebappsDirectory(dir);

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
        deploymentScanner.addWebappsDirectory(dir);

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
        deploymentScanner.addWebappsDirectory(dir);

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

    public static Stream<Arguments> environmentsOrder()
    {
        return Stream.of(
            // One environment only.
            Arguments.of(List.of("core"), List.of(), "core"),
            Arguments.of(List.of("core"), List.of("core"), "core"),
            Arguments.of(List.of("core"), List.of("abc", "core"), "core"),
            Arguments.of(List.of("core"), List.of("core", "abc"), "core"),
            // Many environments.
            Arguments.of(List.of("abc", "core"), List.of(), "abc"),
            Arguments.of(List.of("abc", "core"), List.of("abc"), "abc"),
            Arguments.of(List.of("abc", "core"), List.of("core"), "core"),
            Arguments.of(List.of("abc", "core"), List.of("core", "abc"), "core")
        );
    }

    @ParameterizedTest
    @MethodSource("environmentsOrder")
    public void testEnvironmentsOrder(List<String> environments, List<String> order, String expected)
    {
        Environment.ensure("core", Environment.class);
        Environment.ensure("abc", Environment.class);

        DeploymentScanner deploymentScanner = new DeploymentScanner(new Server());
        environments.forEach(deploymentScanner::configureEnvironment);
        deploymentScanner.setEnvironmentsOrder(order);

        assertThat(deploymentScanner.getDefaultEnvironmentName(), is(expected));
    }

    @Test
    public void testEnvironmentsWithExplicitWrongOrder()
    {
        Environment.ensure("one", Environment.class);
        Environment.ensure("two", Environment.class);

        DeploymentScanner deploymentScanner = new DeploymentScanner(new Server());
        deploymentScanner.configureEnvironment("one");
        deploymentScanner.configureEnvironment("two");
        deploymentScanner.setEnvironmentsOrder(List.of("other"));

        assertThat(deploymentScanner.getDefaultEnvironmentName(), nullValue());
    }

    @Test
    public void testDump() throws Exception
    {
        Path root = workDir.getEmptyPathDir();
        Path webapps = root.resolve("webapps");
        FS.ensureDirExists(webapps);
        Path environments = root.resolve("environments");
        FS.ensureDirExists(environments);

        Server server = new Server();
        try
        {
            ContextHandlerCollection contextHandlerCollection = new ContextHandlerCollection();
            server.setHandler(contextHandlerCollection);
            StandardDeployer deployer = new StandardDeployer(contextHandlerCollection);
            server.addBean(deployer);
            DeploymentScanner deploymentScanner = new DeploymentScanner(server, deployer);
            deploymentScanner.addWebappsDirectory(webapps);
            deploymentScanner.setEnvironmentsDirectory(environments);

            Environment.ensure("static", ContextHandler.class);
            DeploymentScanner.EnvironmentConfig coreConfig = deploymentScanner.configureEnvironment("static");
            coreConfig.setContextHandlerClass(StaticContextHandler.class.getName());

            server.addBean(deploymentScanner);
            server.start();

            String dump = server.dump();
            // System.out.println(dump);
            assertThat(dump, containsString("webappsDirs size=1"));
            assertThat(dump, containsString("+> " + webapps));
            assertThat(dump, containsString("environmentsDir: " + environments));
            assertThat(dump, containsString("scanInterval: 0"));
            assertThat(dump, containsString("enabledEnvironments size=1"));
            assertThat(dump, containsString(" +> static"));
            assertThat(dump, containsString("environmentAttributes size=1"));
            assertThat(dump, containsString("jetty.deploy.contextHandlerClass=org.eclipse.jetty.server.handler.StaticContextHandler"));
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }
}
