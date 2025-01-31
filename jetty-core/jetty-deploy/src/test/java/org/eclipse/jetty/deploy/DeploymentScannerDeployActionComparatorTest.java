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
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.deploy.DeploymentScanner.DeployAction;
import org.eclipse.jetty.deploy.internal.PathsApp;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class DeploymentScannerDeployActionComparatorTest
{
    @Test
    public void testAddOnly()
    {
        PathsApp appFoo = new PathsApp("foo");
        appFoo.putPath(Path.of("foo.xml"), PathsApp.State.ADDED);
        PathsApp appBar = new PathsApp("bar");
        appBar.putPath(Path.of("bar.xml"), PathsApp.State.ADDED);

        List<DeployAction> actions = new ArrayList<>();
        actions.add(new DeployAction(DeployAction.Type.ADD, "bar"));
        actions.add(new DeployAction(DeployAction.Type.ADD, "foo"));

        actions.sort(new DeploymentScanner.DeployActionComparator());

        // Verify order
        Iterator<DeployAction> iterator = actions.iterator();
        DeployAction action;

        // expected in ascending basename order
        action = iterator.next();
        assertThat(action.type(), is(DeployAction.Type.ADD));
        assertThat(action.name(), is("bar"));

        action = iterator.next();
        assertThat(action.type(), is(DeployAction.Type.ADD));
        assertThat(action.name(), is("foo"));
    }

    @Test
    public void testRemoveOnly()
    {
        PathsApp appFoo = new PathsApp("foo");
        appFoo.putPath(Path.of("foo.xml"), PathsApp.State.REMOVED);

        PathsApp appBar = new PathsApp("bar");
        appBar.putPath(Path.of("bar.xml"), PathsApp.State.REMOVED);

        List<DeployAction> actions = new ArrayList<>();
        actions.add(new DeployAction(DeployAction.Type.REMOVE, "foo"));
        actions.add(new DeployAction(DeployAction.Type.REMOVE, "bar"));

        actions.sort(new DeploymentScanner.DeployActionComparator());

        // Verify order
        Iterator<DeployAction> iterator = actions.iterator();
        DeployAction action;

        // expected in descending basename order
        action = iterator.next();
        assertThat(action.type(), is(DeployAction.Type.REMOVE));
        assertThat(action.name(), is("foo"));

        action = iterator.next();
        assertThat(action.type(), is(DeployAction.Type.REMOVE));
        assertThat(action.name(), is("bar"));
    }

    @Test
    public void testRemoveTwoAndAddTwo()
    {
        PathsApp appFoo = new PathsApp("foo");
        appFoo.putPath(Path.of("foo.xml"), PathsApp.State.REMOVED);

        PathsApp appBar = new PathsApp("bar");
        appBar.putPath(Path.of("bar.xml"), PathsApp.State.REMOVED);

        List<DeployAction> actions = new ArrayList<>();
        actions.add(new DeployAction(DeployAction.Type.REMOVE, "foo"));
        actions.add(new DeployAction(DeployAction.Type.ADD, "foo"));
        actions.add(new DeployAction(DeployAction.Type.ADD, "bar"));
        actions.add(new DeployAction(DeployAction.Type.REMOVE, "bar"));

        // Perform sort
        actions.sort(new DeploymentScanner.DeployActionComparator());

        // Verify order
        Iterator<DeployAction> iterator = actions.iterator();
        DeployAction action;

        // expecting REMOVE first

        // REMOVE is in descending basename order
        action = iterator.next();
        assertThat(action.type(), is(DeployAction.Type.REMOVE));
        assertThat(action.name(), is("foo"));

        action = iterator.next();
        assertThat(action.type(), is(DeployAction.Type.REMOVE));
        assertThat(action.name(), is("bar"));

        // expecting ADD next

        // ADD is in ascending basename order
        action = iterator.next();
        assertThat(action.type(), is(DeployAction.Type.ADD));
        assertThat(action.name(), is("bar"));

        action = iterator.next();
        assertThat(action.type(), is(DeployAction.Type.ADD));
        assertThat(action.name(), is("foo"));
    }

    @Test
    public void testRemoveFourAndAddTwo()
    {
        PathsApp appA = new PathsApp("app-a");
        appA.putPath(Path.of("app-a.xml"), PathsApp.State.REMOVED);

        PathsApp appB = new PathsApp("app-b");
        appB.putPath(Path.of("app-b.xml"), PathsApp.State.REMOVED);

        PathsApp appC = new PathsApp("app-c");
        appC.putPath(Path.of("app-c.xml"), PathsApp.State.REMOVED);

        PathsApp appD = new PathsApp("app-d");
        appD.putPath(Path.of("app-d.xml"), PathsApp.State.REMOVED);

        List<DeployAction> actions = new ArrayList<>();
        // app A is going through hot-reload
        actions.add(new DeployAction(DeployAction.Type.REMOVE, "app-a"));
        actions.add(new DeployAction(DeployAction.Type.ADD, "app-a"));
        // app B is being removed
        actions.add(new DeployAction(DeployAction.Type.REMOVE, "app-b"));
        // app C is being removed
        actions.add(new DeployAction(DeployAction.Type.REMOVE, "app-c"));
        // app D is going through hot-reload
        actions.add(new DeployAction(DeployAction.Type.ADD, "app-d"));
        actions.add(new DeployAction(DeployAction.Type.REMOVE, "app-d"));

        assertThat(actions.size(), is(6));

        // Perform sort
        actions.sort(new DeploymentScanner.DeployActionComparator());

        // Verify order
        Iterator<DeployAction> iterator = actions.iterator();
        DeployAction action;

        // expecting REMOVE first

        // REMOVE is in descending basename order
        action = iterator.next();
        assertThat(action.type(), is(DeployAction.Type.REMOVE));
        assertThat(action.name(), is("app-d"));

        action = iterator.next();
        assertThat(action.type(), is(DeployAction.Type.REMOVE));
        assertThat(action.name(), is("app-c"));

        action = iterator.next();
        assertThat(action.type(), is(DeployAction.Type.REMOVE));
        assertThat(action.name(), is("app-b"));

        action = iterator.next();
        assertThat(action.type(), is(DeployAction.Type.REMOVE));
        assertThat(action.name(), is("app-a"));

        // expecting ADD next

        // ADD is in ascending basename order
        action = iterator.next();
        assertThat(action.type(), is(DeployAction.Type.ADD));
        assertThat(action.name(), is("app-a"));

        action = iterator.next();
        assertThat(action.type(), is(DeployAction.Type.ADD));
        assertThat(action.name(), is("app-d"));
    }
}
