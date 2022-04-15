//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.paths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.eclipse.jetty.toolchain.test.ExtraMatchers.ordered;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class PathCollectionTest
{
    public WorkDir workDir;

    @Test
    public void testEmptyPathCollection() throws Exception
    {
        PathCollection pathCollection = new PathCollection();
        assertThat("pathCollection.size", pathCollection.size(), is(0));
        assertTrue(pathCollection.isEmpty());
    }

    @Test
    public void testResolveExisting() throws IOException
    {
        Path testPath = workDir.getEmptyPathDir();

        FS.touch(testPath.resolve("hello.txt"));

        PathCollection pathCollection = new PathCollection();
        pathCollection.add(testPath);

        assertThat("pathCollection.size", pathCollection.size(), is(1));

        Path discovered = pathCollection.resolveFirstExisting("hello.txt");
        assertEquals(testPath.resolve("hello.txt"), discovered);

        Path missing = pathCollection.resolveFirstExisting("missing.txt");
        assertNull(missing);
    }

    // TODO: test remove (cleanup)
    // TODO: test replace (cleanup)

    @Test
    public void testResolveAll() throws IOException
    {
        Path testPath = workDir.getEmptyPathDir();

        Path foo = testPath.resolve("foo");
        Path bar = testPath.resolve("bar");

        Files.createDirectories(foo.resolve("META-INF/services"));
        Files.createDirectories(bar.resolve("META-INF/services"));

        FS.touch(foo.resolve("META-INF/services/org.eclipse.jetty.Zed"));
        FS.touch(bar.resolve("META-INF/services/org.eclipse.jetty.Zed"));
        FS.touch(bar.resolve("META-INF/services/org.cometd.Widget"));

        PathCollection pathCollection = new PathCollection();
        pathCollection.add(foo);
        pathCollection.add(bar);

        assertThat("pathCollection.size", pathCollection.size(), is(2));

        List<Path> expected = new ArrayList<>();
        expected.add(foo.resolve("META-INF/services/org.eclipse.jetty.Zed"));
        expected.add(bar.resolve("META-INF/services/org.eclipse.jetty.Zed"));

        List<Path> services = pathCollection.resolveAll("META-INF/services/org.eclipse.jetty.Zed", Files::exists);
        assertThat(services, ordered(expected));
    }

    @Test
    public void testFindClassFiles() throws IOException
    {
        Path testPath = workDir.getEmptyPathDir();

        Path foo = testPath.resolve("foo");
        Path bar = testPath.resolve("bar");

        Files.createDirectories(foo.resolve("META-INF/services"));
        Files.createDirectories(foo.resolve("org/eclipse/jetty/demo"));
        Files.createDirectories(bar.resolve("META-INF/services"));
        Files.createDirectories(bar.resolve("org/cometd"));

        FS.touch(foo.resolve("META-INF/services/org.eclipse.jetty.Zed"));
        FS.touch(bar.resolve("META-INF/services/org.eclipse.jetty.Zed"));

        FS.touch(foo.resolve("org/eclipse/jetty/Zed.class"));
        FS.touch(foo.resolve("org/eclipse/jetty/demo/Extra.class"));
        FS.touch(bar.resolve("org/cometd/Widget.class"));

        PathCollection pathCollection = new PathCollection();
        pathCollection.add(foo);
        pathCollection.add(bar);

        assertThat("pathCollection.size", pathCollection.size(), is(2));

        List<Path> expected = new ArrayList<>();
        expected.add(foo.resolve("org/eclipse/jetty/demo/Extra.class"));
        expected.add(foo.resolve("org/eclipse/jetty/Zed.class"));
        expected.add(bar.resolve("org/cometd/Widget.class"));
        Collections.sort(expected);

        Stream<Path> classes = pathCollection.find(new ClassFilePredicate());

        List<Path> actual = classes.sorted().collect(Collectors.toList());
        assertThat(actual, ordered(expected));
    }

    @Test
    public void testFindClassFilesWithinJars() throws Exception
    {
        Path testPath = workDir.getEmptyPathDir();

        Path foo = testPath.resolve("foo");
        Path bar = testPath.resolve("bar");

        Files.createDirectories(foo.resolve("META-INF/services"));
        Files.createDirectories(foo.resolve("org/eclipse/jetty/demo"));
        Files.createDirectories(bar.resolve("META-INF/services"));
        Files.createDirectories(bar.resolve("org/cometd"));

        FS.touch(foo.resolve("META-INF/services/org.eclipse.jetty.Zed"));
        FS.touch(bar.resolve("META-INF/services/org.eclipse.jetty.Zed"));

        FS.touch(foo.resolve("org/eclipse/jetty/Zed.class"));
        FS.touch(foo.resolve("org/eclipse/jetty/demo/Extra.class"));
        FS.touch(bar.resolve("org/cometd/Widget.class"));

        try (PathCollection pathCollection = new PathCollection())
        {
            pathCollection.add(foo); // a.jar!/
            pathCollection.add(bar); // a.jar!/META-INF/resources/

            pathCollection.add(getTestJar("example.jar"));
            pathCollection.add(getTestJar("jar-file-resource.jar"));
            pathCollection.add(getTestJar("test-base-resource.jar"));

            assertThat("pathCollection.size", pathCollection.size(), is(5));

            Stream<Path> classes = pathCollection.find(new ClassFilePredicate());
            // classes.sorted(new PathComparator()).map(Path::toUri).forEach(System.out::println);

            List<String> actual = classes.map(Path::toString).collect(Collectors.toList());

            assertThat(actual, hasItem(foo.resolve("org/eclipse/jetty/demo/Extra.class").toString()));
            assertThat(actual, hasItem("/org/example/In10Only.class"));
            assertThat(actual, hasItem("/org/example/OnlyIn9.class"));
            assertThat(actual, hasItem("/org/example/onlyIn9/OnlyIn9.class"));
            assertThat(actual, hasItem("/org/example/InBoth.class"));
            assertThat(actual, hasItem("/org/example/InBoth$InnerBase.class"));
            assertThat(actual, hasItem("/org/example/Nowhere$NoOuter.class"));

            // make sure forbidden entries don't exist
            assertThat(actual, not(hasItem(containsString("WEB-INF"))));
            assertThat(actual, not(hasItem(containsString("META-INF"))));
        }

        Map<Path, FileSystemPool.FileSystemRefCount> fsCache = FileSystemPool.getCache();
        assertThat("Cache is empty", fsCache.size(), is(0));
    }

    private static Path getTestJar(String filename)
    {
        return MavenTestingUtils.getProjectFilePath("src/test/jars/" + filename);
    }
}
