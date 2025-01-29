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

import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

@ExtendWith(WorkDirExtension.class)
public class DefaultAppTest
{
    public WorkDir workDir;

    /**
     * Ensuring main path heuristics is followed.
     * <p>
     * Test with only a single XML.
     */
    @Test
    public void testMainPathOnlyXml() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();

        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);

        ScanTrackedApp app = new ScanTrackedApp("bar");
        app.putPath(xml, ScanTrackedApp.State.UNCHANGED);

        Path main = app.getMainPath();

        assertThat("main path", main, equalTo(xml));
    }

    /**
     * Ensuring main path heuristics is followed.
     * <p>
     * Test with an XML and WAR.
     */
    @Test
    public void testMainPathXmlAndWar() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);
        Path war = dir.resolve("bar.war");
        Files.writeString(war, "WAR for bar", UTF_8);

        ScanTrackedApp app = new ScanTrackedApp("bar");
        app.putPath(xml, ScanTrackedApp.State.UNCHANGED);
        app.putPath(war, ScanTrackedApp.State.UNCHANGED);

        Path main = app.getMainPath();

        assertThat("main path", main, equalTo(xml));
    }

    /**
     * Ensuring main path heuristics is followed.
     * <p>
     * Test with a Directory and WAR.
     */
    @Test
    public void testMainPathDirAndWar() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path appDir = dir.resolve("bar");
        Files.createDirectory(appDir);
        Path war = dir.resolve("bar.war");
        Files.writeString(war, "WAR for bar", UTF_8);

        ScanTrackedApp app = new ScanTrackedApp("bar");
        app.putPath(appDir, ScanTrackedApp.State.UNCHANGED);
        app.putPath(war, ScanTrackedApp.State.UNCHANGED);

        Path main = app.getMainPath();

        assertThat("main path", main, equalTo(war));
    }

    /**
     * Ensuring main path heuristics is followed.
     * <p>
     * Test with a Directory and XML.
     */
    @Test
    public void testMainPathDirAndXml() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path appDir = dir.resolve("bar");
        Files.createDirectory(appDir);
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);

        ScanTrackedApp app = new ScanTrackedApp("bar");
        app.putPath(appDir, ScanTrackedApp.State.UNCHANGED);
        app.putPath(xml, ScanTrackedApp.State.UNCHANGED);

        Path main = app.getMainPath();

        assertThat("main path", main, equalTo(xml));
    }

    /**
     * Ensuring main path heuristics is followed.
     * <p>
     * Test with a Directory and XML and WAR
     */
    @Test
    public void testMainPathDirAndXmlAndWar() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path appDir = dir.resolve("bar");
        Files.createDirectory(appDir);
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);
        Path war = dir.resolve("bar.war");
        Files.writeString(war, "WAR for bar", UTF_8);

        ScanTrackedApp app = new ScanTrackedApp("bar");
        app.putPath(appDir, ScanTrackedApp.State.UNCHANGED);
        app.putPath(xml, ScanTrackedApp.State.UNCHANGED);
        app.putPath(war, ScanTrackedApp.State.UNCHANGED);

        Path main = app.getMainPath();

        assertThat("main path", main, equalTo(xml));
    }

    @Test
    public void testStateUnchanged()
    {
        ScanTrackedApp app = new ScanTrackedApp("test");
        app.putPath(Path.of("test-a"), ScanTrackedApp.State.UNCHANGED);
        app.putPath(Path.of("test-b"), ScanTrackedApp.State.UNCHANGED);

        assertThat(app.getState(), is(ScanTrackedApp.State.UNCHANGED));
    }

    @Test
    public void testStateInitialEmpty()
    {
        ScanTrackedApp app = new ScanTrackedApp("test");
        // intentionally empty of Paths

        assertThat(app.getState(), is(ScanTrackedApp.State.REMOVED));
    }

    @Test
    public void testStatePutThenRemoveAll()
    {
        ScanTrackedApp app = new ScanTrackedApp("test");
        app.putPath(Path.of("test-a"), ScanTrackedApp.State.ADDED);
        assertThat(app.getState(), is(ScanTrackedApp.State.ADDED));

        // Now it gets flagged as removed. (eg: by a Scanner change)
        app.putPath(Path.of("test-a"), ScanTrackedApp.State.REMOVED);
        // Then it gets processed, which results in a state reset.
        app.resetStates();

        // The resulting app should have no paths, and be flagged as removed.
        assertThat(app.getPaths().size(), is(0));
        assertThat(app.getState(), is(ScanTrackedApp.State.REMOVED));
    }

    @Test
    public void testStateAddedOnly()
    {
        ScanTrackedApp app = new ScanTrackedApp("test");
        app.putPath(Path.of("test-a"), ScanTrackedApp.State.ADDED);
        app.putPath(Path.of("test-b"), ScanTrackedApp.State.ADDED);

        assertThat(app.getState(), is(ScanTrackedApp.State.ADDED));
    }

    @Test
    public void testStateAddedRemoved()
    {
        ScanTrackedApp app = new ScanTrackedApp("test");
        app.putPath(Path.of("test-a"), ScanTrackedApp.State.REMOVED); // existing file removed in this scan
        app.putPath(Path.of("test-b"), ScanTrackedApp.State.ADDED); // new file introduced in this scan event

        assertThat(app.getState(), is(ScanTrackedApp.State.CHANGED));
    }

    @Test
    public void testStateAddedChanged()
    {
        ScanTrackedApp app = new ScanTrackedApp("test");
        app.putPath(Path.of("test-a"), ScanTrackedApp.State.CHANGED); // existing file changed in this scan
        app.putPath(Path.of("test-b"), ScanTrackedApp.State.ADDED); // new file introduced in this scan event

        assertThat(app.getState(), is(ScanTrackedApp.State.CHANGED));
    }

    @Test
    public void testStateUnchangedAdded()
    {
        ScanTrackedApp app = new ScanTrackedApp("test");
        app.putPath(Path.of("test-a"), ScanTrackedApp.State.UNCHANGED); // existed in previous scan
        app.putPath(Path.of("test-b"), ScanTrackedApp.State.ADDED); // new file introduced in this scan event

        assertThat(app.getState(), is(ScanTrackedApp.State.CHANGED));
    }

    @Test
    public void testStateUnchangedChanged()
    {
        ScanTrackedApp app = new ScanTrackedApp("test");
        app.putPath(Path.of("test-a"), ScanTrackedApp.State.UNCHANGED); // existed in previous scan
        app.putPath(Path.of("test-b"), ScanTrackedApp.State.CHANGED); // existing file changed in this scan event

        assertThat(app.getState(), is(ScanTrackedApp.State.CHANGED));
    }

    @Test
    public void testStateUnchangedRemoved()
    {
        ScanTrackedApp app = new ScanTrackedApp("test");
        app.putPath(Path.of("test-a"), ScanTrackedApp.State.UNCHANGED); // existed in previous scan
        app.putPath(Path.of("test-b"), ScanTrackedApp.State.REMOVED); // existing file removed in this scan event

        assertThat(app.getState(), is(ScanTrackedApp.State.CHANGED));
    }

    @Test
    public void testStateUnchangedRemovedAdded()
    {
        ScanTrackedApp app = new ScanTrackedApp("test");
        app.putPath(Path.of("test-a"), ScanTrackedApp.State.UNCHANGED); // existed in previous scan
        app.putPath(Path.of("test-b"), ScanTrackedApp.State.REMOVED); // existing file changed in this scan event
        app.putPath(Path.of("test-c"), ScanTrackedApp.State.ADDED); // new file introduced in this scan event

        assertThat(app.getState(), is(ScanTrackedApp.State.CHANGED));
    }
}
