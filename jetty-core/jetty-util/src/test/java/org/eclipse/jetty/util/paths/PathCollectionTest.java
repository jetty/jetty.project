//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.io.File;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    public void testEmptyPathCollection()
    {
        PathCollection pathCollection = PathCollection.from();
        assertThat("pathCollection.size", pathCollection.size(), is(0));
        assertTrue(pathCollection.isEmpty());
    }

    @Test
    public void testResolveExisting() throws IOException
    {
        Path testPath = workDir.getEmptyPathDir();

        FS.touch(testPath.resolve("hello.txt"));

        PathCollection pathCollection = PathCollection.from(testPath);

        assertThat("pathCollection.size", pathCollection.size(), is(1));

        Path discovered = pathCollection.resolveFirstExisting("hello.txt");
        assertEquals(testPath.resolve("hello.txt"), discovered);

        Path missing = pathCollection.resolveFirstExisting("missing.txt");
        assertNull(missing);
    }

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

        PathCollection pathCollection = PathCollection.from(foo, bar);

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

        PathCollection pathCollection = PathCollection.from(foo, bar);

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

        Path testA = getTestJar("sub/example.jar");
        Path testB = getTestJar("jar-file-resource.jar");
        Path testC = getTestJar("test-base-resource.jar");

        try (PathCollection pathCollection = PathCollection.from(foo, bar, testA, testB, testC))
        {
            assertThat("pathCollection.size", pathCollection.size(), is(5));

            Stream<Path> classes = pathCollection.find(new ClassFilePredicate());

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

        Map<Path, ZipFsPool.FileSystemRefCount> fsCache = ZipFsPool.getCache();
        assertThat("Cache is empty", fsCache.size(), is(0));
    }

    @Test
    public void testFromListEmpty() throws Exception
    {
        try (PathCollection pathCollection = PathCollection.fromList("", false))
        {
            assertThat(pathCollection.size(), is(0));
        }
    }

    @Test
    public void testFromListSimpleDir() throws Exception
    {
        String testDir = MavenTestingUtils.getTestResourcePathDir("TestData").toString();
        try (PathCollection pathCollection = PathCollection.fromList(testDir, false))
        {
            assertThat(pathCollection.size(), is(1));
            // See if we can get an expected file
            Path entry = pathCollection.resolveFirstExisting("alphabet.txt");
            assertTrue(Files.exists(entry), "The alphabet.txt should have been found");
        }
    }

    /**
     * Show a String that has 2 jars declared, becoming a PathCollection.
     */
    @ParameterizedTest
    @ValueSource(strings = {",", ";"})
    public void testFromListJarsDeclared(String delim) throws Exception
    {
        String jarA = getTestJar("sub/example.jar").toString();
        String jarB = getTestJar("jar-file-resource.jar").toString();
        String list = String.join(delim, jarA, jarB);

        try (PathCollection pathCollection = PathCollection.fromList(list, false))
        {
            assertThat(pathCollection.size(), is(2));
            // See if we can get an expected file from sub/example.jar
            Path entry = pathCollection.resolveFirstExisting("org/example/InBoth.class");
            assertTrue(Files.exists(entry), "The org/example/InBoth.class file should have been found");
            // See if we can get an expected file from jar-file-resource.jar
            entry = pathCollection.resolveFirstExisting("rez/deep/zzz");
            assertTrue(Files.exists(entry), "The rez/deep/zzz file should have been found");
        }
    }

    /**
     * Show a String that uses glob ref, becoming a PathCollection.
     */
    @Test
    public void testFromListJarsGlob() throws Exception
    {
        String list = MavenTestingUtils.getProjectDirPath("src/test/jars").toString() + File.separatorChar + '*';

        try (PathCollection pathCollection = PathCollection.fromList(list, false))
        {
            // should only see the jars in the root of src/test/jars
            // should not have the sub/*.jar added
            assertThat(pathCollection.size(), is(2));
            // See if we can get an expected file from test-base-resource.jar
            Path entry = pathCollection.resolveFirstExisting("another%20one/bites%20the%20dust");
            assertTrue(Files.exists(entry), "The another%20one/bites%20the%20dust file should have been found");
            // See if we can get an expected file from jar-file-resource.jar
            entry = pathCollection.resolveFirstExisting("rez/deep/zzz");
            assertTrue(Files.exists(entry), "The rez/deep/zzz file should have been found");
        }
    }

    private static Path getTestJar(String filename)
    {
        return MavenTestingUtils.getProjectFilePath("src/test/jars/" + filename);
    }
}
