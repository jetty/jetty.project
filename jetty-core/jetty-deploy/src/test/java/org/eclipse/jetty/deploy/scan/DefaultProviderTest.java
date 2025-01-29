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

package org.eclipse.jetty.deploy.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.jetty.deploy.AbstractCleanEnvironmentTest;
import org.eclipse.jetty.deploy.ContextHandlerManagement;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Scanner;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@ExtendWith(WorkDirExtension.class)
public class DefaultProviderTest extends AbstractCleanEnvironmentTest
{
    public WorkDir workDir;

    public static class AssertActionListDefaultProvider extends DefaultProvider
    {
        Consumer<List<DeployAction>> assertActionList;

        public AssertActionListDefaultProvider()
        {
            super(new ContextHandlerManagement()
            {
                @Override
                public Server getServer()
                {
                    return null;
                }

                @Override
                public void addContextHandler(ContextHandler contextHandler, String goalName)
                {
                    // ignore
                }

                @Override
                public void requestContextHandlerGoal(ContextHandler contextHandler, String goalName)
                {
                    // ignore
                }

                @Override
                public void removeContextHandler(ContextHandler contextHandler, String goalName)
                {
                    // ignore
                }
            });
        }

        @Override
        protected void performActions(List<DeployAction> actions)
        {
            assertActionList.accept(actions);

            // Perform post performActions cleanup that normally happens
            for (DeployAction action : actions)
            {
                action.getApp().resetStates();
            }
        }
    }

    @Test
    public void testActionListNewXmlOnly() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);

        AssertActionListDefaultProvider defaultProvider = new AssertActionListDefaultProvider();
        defaultProvider.addMonitoredDirectory(dir);

        Map<Path, Scanner.Notification> changeSet = new HashMap<>();
        changeSet.put(xml, Scanner.Notification.ADDED);

        defaultProvider.assertActionList = (actions) ->
        {
            assertThat("actions.size", actions.size(), is(1));
            Iterator<DeployAction> iterator = actions.iterator();
            DeployAction action;

            action = iterator.next();
            assertThat("action.name", action.getName(), is("bar"));
            assertThat("action.type", action.getType(), is(DeployAction.Type.ADD));
            assertThat("action.app.state", action.getApp().getState(), is(ScanTrackedApp.State.ADDED));
            assertThat("action.app.paths", action.getApp().getPaths().keySet(), Matchers.contains(xml));
            assertThat("action.app.mainPath", action.getApp().getMainPath(), is(xml));
        };

        defaultProvider.pathsChanged(changeSet);
    }

    @Test
    public void testActionListXmlThenRemoved() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path xml = dir.resolve("foo.xml");
        Files.writeString(xml, "XML for foo", UTF_8);

        AssertActionListDefaultProvider defaultProvider = new AssertActionListDefaultProvider();
        defaultProvider.addMonitoredDirectory(dir);

        // Initial deployment.
        Map<Path, Scanner.Notification> changeSet = new HashMap<>();
        changeSet.put(xml, Scanner.Notification.ADDED);

        defaultProvider.assertActionList = (actions) ->
        {
            assertThat("actions.size", actions.size(), is(1));
            Iterator<DeployAction> iterator = actions.iterator();
            DeployAction action;

            action = iterator.next();
            assertThat("action.name", action.getName(), is("foo"));
            assertThat("action.type", action.getType(), is(DeployAction.Type.ADD));
            assertThat("action.app.state", action.getApp().getState(), is(ScanTrackedApp.State.ADDED));
            assertThat("action.app.paths", action.getApp().getPaths().keySet(), contains(xml));
            assertThat("action.app.paths[xml].state", action.getApp().getPaths().get(xml), is(ScanTrackedApp.State.ADDED));
            assertThat("action.app.mainPath", action.getApp().getMainPath(), is(xml));
        };

        defaultProvider.pathsChanged(changeSet);

        // Removed only deployment file.
        Files.deleteIfExists(xml);
        changeSet.clear();
        changeSet.put(xml, Scanner.Notification.REMOVED);

        defaultProvider.assertActionList = (actions) ->
        {
            assertThat("actions.size", actions.size(), is(1));
            Iterator<DeployAction> iterator = actions.iterator();
            DeployAction action;

            action = iterator.next();
            assertThat("action.name", action.getName(), is("foo"));
            assertThat("action.type", action.getType(), is(DeployAction.Type.REMOVE));
            assertThat("action.app.state", action.getApp().getState(), is(ScanTrackedApp.State.REMOVED));
            assertThat("action.app.paths", action.getApp().getPaths().keySet(), contains(xml));
            assertThat("action.app.paths[xml].state", action.getApp().getPaths().get(xml), is(ScanTrackedApp.State.REMOVED));
            assertThat("action.app.mainPath", action.getApp().getMainPath(), is(nullValue()));
        };

        defaultProvider.pathsChanged(changeSet);
    }

    @Test
    public void testActionListNewXmlAndWarOnly() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);
        Path war = dir.resolve("bar.war");
        Files.writeString(war, "WAR for bar", UTF_8);

        AssertActionListDefaultProvider defaultProvider = new AssertActionListDefaultProvider();
        defaultProvider.addMonitoredDirectory(dir);

        // Initial deployment
        Map<Path, Scanner.Notification> changeSet = new HashMap<>();
        changeSet.put(xml, Scanner.Notification.ADDED);
        changeSet.put(war, Scanner.Notification.ADDED);

        defaultProvider.assertActionList = (actions) ->
        {
            assertThat("actions.size", actions.size(), is(1));
            Iterator<DeployAction> iterator = actions.iterator();
            DeployAction action;

            action = iterator.next();

            assertThat("action.name", action.getName(), is("bar"));
            assertThat("action.type", action.getType(), is(DeployAction.Type.ADD));
            assertThat("action.app.state", action.getApp().getState(), is(ScanTrackedApp.State.ADDED));
            assertThat("action.app.paths", action.getApp().getPaths().keySet(), containsInAnyOrder(xml, war));
            assertThat("action.app.paths[xml].state", action.getApp().getPaths().get(xml), is(ScanTrackedApp.State.ADDED));
            assertThat("action.app.paths[war].state", action.getApp().getPaths().get(war), is(ScanTrackedApp.State.ADDED));
            assertThat("action.app.mainPath", action.getApp().getMainPath(), is(xml));
        };

        defaultProvider.pathsChanged(changeSet);
    }

    @Test
    public void testActionListXmlAndWarWithXmlUpdate() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);
        Path war = dir.resolve("bar.war");
        Files.writeString(war, "WAR for bar", UTF_8);

        AssertActionListDefaultProvider defaultProvider = new AssertActionListDefaultProvider();
        defaultProvider.addMonitoredDirectory(dir);

        // Initial deployment
        Map<Path, Scanner.Notification> changeSet = new HashMap<>();
        changeSet.put(xml, Scanner.Notification.ADDED);
        changeSet.put(war, Scanner.Notification.ADDED);

        defaultProvider.assertActionList = (actions) ->
        {
            assertThat("actions.size", actions.size(), is(1));
            Iterator<DeployAction> iterator = actions.iterator();
            DeployAction action;

            action = iterator.next();
            assertThat("action.name", action.getName(), is("bar"));
            assertThat("action.type", action.getType(), is(DeployAction.Type.ADD));
            assertThat("action.app.state", action.getApp().getState(), is(ScanTrackedApp.State.ADDED));
            assertThat("action.app.paths", action.getApp().getPaths().keySet(), containsInAnyOrder(xml, war));
            assertThat("action.app.paths[xml].state", action.getApp().getPaths().get(xml), is(ScanTrackedApp.State.ADDED));
            assertThat("action.app.paths[war].state", action.getApp().getPaths().get(war), is(ScanTrackedApp.State.ADDED));
            assertThat("action.app.mainPath", action.getApp().getMainPath(), is(xml));
        };

        defaultProvider.pathsChanged(changeSet);

        // Change/Touch war
        changeSet = new HashMap<>();
        changeSet.put(war, Scanner.Notification.CHANGED);

        defaultProvider.assertActionList = (actions) ->
        {
            assertThat("actions.size", actions.size(), is(2));
            Iterator<DeployAction> iterator = actions.iterator();
            DeployAction action;

            action = iterator.next();
            assertThat("action.name", action.getName(), is("bar"));
            assertThat("action.type", action.getType(), is(DeployAction.Type.REMOVE));
            assertThat("action.app.state", action.getApp().getState(), is(ScanTrackedApp.State.CHANGED));
            assertThat("action.app.paths", action.getApp().getPaths().keySet(), containsInAnyOrder(xml, war));
            assertThat("action.app.paths[xml].state", action.getApp().getPaths().get(xml), is(ScanTrackedApp.State.UNCHANGED));
            assertThat("action.app.paths[war].state", action.getApp().getPaths().get(war), is(ScanTrackedApp.State.CHANGED));
            assertThat("action.app.mainPath", action.getApp().getMainPath(), is(xml));

            action = iterator.next();
            assertThat("action.name", action.getName(), is("bar"));
            assertThat("action.type", action.getType(), is(DeployAction.Type.ADD));
            assertThat("action.app.state", action.getApp().getState(), is(ScanTrackedApp.State.CHANGED));
            assertThat("action.app.paths", action.getApp().getPaths().keySet(), containsInAnyOrder(xml, war));
            assertThat("action.app.paths[xml].state", action.getApp().getPaths().get(xml), is(ScanTrackedApp.State.UNCHANGED));
            assertThat("action.app.paths[war].state", action.getApp().getPaths().get(war), is(ScanTrackedApp.State.CHANGED));
            assertThat("action.app.mainPath", action.getApp().getMainPath(), is(xml));
        };

        defaultProvider.pathsChanged(changeSet);
    }

    @Test
    public void testActionListXmlAndWarWithXmlRemoved() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);
        Path war = dir.resolve("bar.war");
        Files.writeString(war, "WAR for bar", UTF_8);

        AssertActionListDefaultProvider defaultProvider = new AssertActionListDefaultProvider();
        defaultProvider.addMonitoredDirectory(dir);

        // Initial deployment
        Map<Path, Scanner.Notification> changeSet = new HashMap<>();
        changeSet.put(xml, Scanner.Notification.ADDED);
        changeSet.put(war, Scanner.Notification.ADDED);

        defaultProvider.assertActionList = (actions) ->
        {
            assertThat("actions.size", actions.size(), is(1));
            Iterator<DeployAction> iterator = actions.iterator();
            DeployAction action;

            action = iterator.next();
            assertThat("action.name", action.getName(), is("bar"));
            assertThat("action.type", action.getType(), is(DeployAction.Type.ADD));
            assertThat("action.app.state", action.getApp().getState(), is(ScanTrackedApp.State.ADDED));
            assertThat("action.app.paths", action.getApp().getPaths().keySet(), containsInAnyOrder(xml, war));
            assertThat("action.app.paths[xml].state", action.getApp().getPaths().get(xml), is(ScanTrackedApp.State.ADDED));
            assertThat("action.app.paths[war].state", action.getApp().getPaths().get(war), is(ScanTrackedApp.State.ADDED));
            assertThat("action.app.mainPath", action.getApp().getMainPath(), is(xml));
        };

        defaultProvider.pathsChanged(changeSet);

        // Change/Touch war and xml
        changeSet = new HashMap<>();
        changeSet.put(war, Scanner.Notification.CHANGED);
        changeSet.put(xml, Scanner.Notification.CHANGED);

        defaultProvider.assertActionList = (actions) ->
        {
            assertThat("actions.size", actions.size(), is(2));
            Iterator<DeployAction> iterator = actions.iterator();
            DeployAction action;

            action = iterator.next();
            assertThat("action.name", action.getName(), is("bar"));
            assertThat("action.type", action.getType(), is(DeployAction.Type.REMOVE));
            assertThat("action.app.state", action.getApp().getState(), is(ScanTrackedApp.State.CHANGED));
            assertThat("action.app.paths", action.getApp().getPaths().keySet(), containsInAnyOrder(xml, war));
            assertThat("action.app.paths[xml].state", action.getApp().getPaths().get(xml), is(ScanTrackedApp.State.CHANGED));
            assertThat("action.app.paths[war].state", action.getApp().getPaths().get(war), is(ScanTrackedApp.State.CHANGED));
            assertThat("action.app.mainPath", action.getApp().getMainPath(), is(xml));

            action = iterator.next();
            assertThat("action.name", action.getName(), is("bar"));
            assertThat("action.type", action.getType(), is(DeployAction.Type.ADD));
            assertThat("action.app.state", action.getApp().getState(), is(ScanTrackedApp.State.CHANGED));
            assertThat("action.app.paths", action.getApp().getPaths().keySet(), containsInAnyOrder(xml, war));
            assertThat("action.app.paths[xml].state", action.getApp().getPaths().get(xml), is(ScanTrackedApp.State.CHANGED));
            assertThat("action.app.paths[war].state", action.getApp().getPaths().get(war), is(ScanTrackedApp.State.CHANGED));
            assertThat("action.app.mainPath", action.getApp().getMainPath(), is(xml));
        };

        defaultProvider.pathsChanged(changeSet);

        // Delete XML (now only war exists)
        Files.deleteIfExists(xml);
        changeSet = new HashMap<>();
        changeSet.put(xml, Scanner.Notification.REMOVED);

        defaultProvider.assertActionList = (actions) ->
        {
            assertThat("actions.size", actions.size(), is(2));
            Iterator<DeployAction> iterator = actions.iterator();
            DeployAction action;

            action = iterator.next();
            assertThat("action.name", action.getName(), is("bar"));
            assertThat("action.type", action.getType(), is(DeployAction.Type.REMOVE));
            assertThat("action.app.state", action.getApp().getState(), is(ScanTrackedApp.State.CHANGED));
            assertThat("action.app.paths", action.getApp().getPaths().keySet(), containsInAnyOrder(xml, war));
            assertThat("action.app.paths[xml].state", action.getApp().getPaths().get(xml), is(ScanTrackedApp.State.REMOVED));
            assertThat("action.app.paths[war].state", action.getApp().getPaths().get(war), is(ScanTrackedApp.State.UNCHANGED));
            assertThat("action.app.mainPath", action.getApp().getMainPath(), is(war));

            action = iterator.next();
            assertThat("action.name", action.getName(), is("bar"));
            assertThat("action.type", action.getType(), is(DeployAction.Type.ADD));
            assertThat("action.app.state", action.getApp().getState(), is(ScanTrackedApp.State.CHANGED));
            assertThat("action.app.paths", action.getApp().getPaths().keySet(), containsInAnyOrder(xml, war));
            assertThat("action.app.paths[xml].state", action.getApp().getPaths().get(xml), is(ScanTrackedApp.State.REMOVED));
            assertThat("action.app.paths[war].state", action.getApp().getPaths().get(war), is(ScanTrackedApp.State.UNCHANGED));
            assertThat("action.app.mainPath", action.getApp().getMainPath(), is(war));
        };

        defaultProvider.pathsChanged(changeSet);

        // Delete WAR
        Files.deleteIfExists(war);
        changeSet = new HashMap<>();
        changeSet.put(war, Scanner.Notification.REMOVED);

        defaultProvider.assertActionList = (actions) ->
        {
            assertThat("actions.size", actions.size(), is(1));
            Iterator<DeployAction> iterator = actions.iterator();
            DeployAction action;

            action = iterator.next();
            assertThat("action.name", action.getName(), is("bar"));
            assertThat("action.type", action.getType(), is(DeployAction.Type.REMOVE));
            assertThat("action.app.state", action.getApp().getState(), is(ScanTrackedApp.State.REMOVED));
            assertThat("action.app.paths", action.getApp().getPaths().keySet(), containsInAnyOrder(war));
            assertThat("action.app.paths[war].state", action.getApp().getPaths().get(war), is(ScanTrackedApp.State.REMOVED));
            assertThat("action.app.mainPath", action.getApp().getMainPath(), is(nullValue()));
        };

        defaultProvider.pathsChanged(changeSet);
    }
}
