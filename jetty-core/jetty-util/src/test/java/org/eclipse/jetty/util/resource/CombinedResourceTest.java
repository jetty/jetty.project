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

package org.eclipse.jetty.util.resource;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.URIUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@ExtendWith(WorkDirExtension.class)
@Isolated
public class CombinedResourceTest
{
    public WorkDir workDir;

    private final ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable();

    @BeforeEach
    public void beforeEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @AfterEach
    public void afterEach()
    {
        resourceFactory.close();
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @Test
    public void testList() throws Exception
    {
        Path one = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/one");
        Path two = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/two");
        Path three = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/three");

        Resource rc = ResourceFactory.combine(
            resourceFactory.newResource(one),
            resourceFactory.newResource(two),
            resourceFactory.newResource(three)
        );

        List<Resource> listing = rc.list();
        List<String> relative = listing.stream()
            .map(rc::getPathTo)
            .map(Path::toString)
            .toList();

        String[] expected = new String[] {
            "1.txt",
            "2.txt",
            "3.txt",
            "dir"
        };
        assertThat(relative, containsInAnyOrder(expected));

        for (Resource r : listing)
        {
            if ("dir".equals(r.getFileName()))
                assertThat(r, instanceOf(CombinedResource.class));
        }

        relative = rc.resolve("dir").list().stream()
            .map(rc::getPathTo)
            .map(Path::toString)
            .toList();

        expected = new String[] {
            FS.separators("dir/1.txt"),
            FS.separators("dir/2.txt"),
            FS.separators("dir/3.txt")
        };

        assertThat(relative, containsInAnyOrder(expected));

        Resource unk = rc.resolve("unknown");
        assertFalse(unk.exists());

        assertEquals(getContent(rc, "1.txt"), "1 - one");
        assertEquals(getContent(rc, "2.txt"), "2 - two");
        assertEquals(getContent(rc, "3.txt"), "3 - three");
    }

    @Test
    public void testMergedDir() throws Exception
    {
        Path one = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/one");
        Path two = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/two");
        Path three = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/three");

        Resource rc = ResourceFactory.combine(
            resourceFactory.newResource(one),
            resourceFactory.newResource(two),
            resourceFactory.newResource(three)
        );

        // This should return a ResourceCollection with 3 `/dir/` sub-directories.
        Resource r = rc.resolve("dir");
        assertThat(r, instanceOf(CombinedResource.class));
        assertEquals(getContent(r, "1.txt"), "1 - one (in dir)");
        assertEquals(getContent(r, "2.txt"), "2 - two (in dir)");
        assertEquals(getContent(r, "3.txt"), "3 - three (in dir)");
    }

    @Test
    public void testCopyTo() throws Exception
    {
        Path destDir = workDir.getEmptyPathDir();
        Path one = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/one");
        Path two = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/two");
        Path three = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/three");

        Resource rc = ResourceFactory.combine(
                resourceFactory.newResource(one),
                resourceFactory.newResource(two),
                resourceFactory.newResource(three)
        );
        rc.copyTo(destDir);

        Resource r = resourceFactory.newResource(destDir);
        assertEquals(getContent(r, "1.txt"), "1 - one");
        assertEquals(getContent(r, "2.txt"), "2 - two");
        assertEquals(getContent(r, "3.txt"), "3 - three");
        r = r.resolve("dir");
        assertEquals(getContent(r, "1.txt"), "1 - one (in dir)");
        assertEquals(getContent(r, "2.txt"), "2 - two (in dir)");
        assertEquals(getContent(r, "3.txt"), "3 - three (in dir)");
    }

    /**
     * Test of CombinedResource.copyTo(Resource) where the CombinedResource is a mix
     * of FileSystem types.
     */
    @Test
    public void testCopyToDifferentFileSystem() throws Exception
    {
        Path testDir = workDir.getEmptyPathDir();

        // Create a JAR file with contents
        Path testJar = testDir.resolve("test.jar");

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI jarUri = URIUtil.uriJarPrefix(testJar.toUri(), "!/");
        try (FileSystem zipfs = FileSystems.newFileSystem(jarUri, env))
        {
            Path root = zipfs.getPath("/");
            Files.writeString(root.resolve("test.txt"), "Contents of test.txt", StandardCharsets.UTF_8);
            Path deepDir = root.resolve("deep/dir/foo");
            Files.createDirectories(deepDir);
            Files.writeString(deepDir.resolve("foo.txt"), "Contents of foo.txt", StandardCharsets.UTF_8);
        }

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource archiveResource = resourceFactory.newResource(jarUri);
            Path one = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/one");
            Path two = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/two");
            Path three = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/three");

            // A CombinedResource that has a mix of FileSystem types
            Resource rc = ResourceFactory.combine(
                archiveResource,
                resourceFactory.newResource(one),
                resourceFactory.newResource(two),
                resourceFactory.newResource(three)
            );

            Path destDir = testDir.resolve("dest");
            Files.createDirectory(destDir);
            rc.copyTo(destDir);

            Resource r = resourceFactory.newResource(destDir);
            assertEquals(getContent(r, "1.txt"), "1 - one");
            assertEquals(getContent(r, "2.txt"), "2 - two");
            assertEquals(getContent(r, "3.txt"), "3 - three");
            r = r.resolve("dir");
            assertEquals(getContent(r, "1.txt"), "1 - one (in dir)");
            assertEquals(getContent(r, "2.txt"), "2 - two (in dir)");
            assertEquals(getContent(r, "3.txt"), "3 - three (in dir)");
        }
    }

    @Test
    public void testMixedResourceCollectionGetPathTo() throws IOException
    {
        Path testDir = workDir.getEmptyPathDir();

        // Create a JAR file with contents
        Path testJar = testDir.resolve("test.jar");

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI jarUri = URIUtil.uriJarPrefix(testJar.toUri(), "!/");
        try (FileSystem zipfs = FileSystems.newFileSystem(jarUri, env))
        {
            Path root = zipfs.getPath("/");
            Files.writeString(root.resolve("3.txt"), "Contents of 3.txt from JAR", StandardCharsets.UTF_8);
            Files.writeString(root.resolve("4.txt"), "Contents of 4.txt from JAR", StandardCharsets.UTF_8);
            Path dir = root.resolve("dir");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("2.txt"), "Contents of dir/2.txt from JAR", StandardCharsets.UTF_8);
        }

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource archiveResource = resourceFactory.newResource(jarUri);
            Path one = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/one");
            Path two = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/two");
            Path three = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/three");

            // A CombinedResource that has a mix of FileSystem types
            Resource rc = ResourceFactory.combine(
                resourceFactory.newResource(one),
                resourceFactory.newResource(two),
                resourceFactory.newResource(three),
                archiveResource
            );

            List<String> actual = new ArrayList<>();

            for (Resource candidate : rc.getAllResources())
            {
                // Skip directories
                if (candidate.isDirectory())
                    continue;

                // Get the path relative to the base resource
                Path relative = rc.getPathTo(candidate); // should not throw an exception
                actual.add(relative.toString());
            }

            String[] expected = {
                "1.txt",
                "2.txt",
                "3.txt",
                "4.txt",
                FS.separators("dir/1.txt"),
                FS.separators("dir/2.txt"),
                FS.separators("dir/3.txt")
            };

            assertThat(actual, contains(expected));
        }
    }

    @Test
    public void testResourceCollectionInResourceCollection()
    {
        Path one = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/one");
        Path two = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/two");
        Path three = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/three");
        Path twoDir = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/two/dir");

        Resource rc1 = ResourceFactory.combine(
            List.of(
                resourceFactory.newResource(one),
                resourceFactory.newResource(two),
                resourceFactory.newResource(three)
            )
        );

        Resource rc2 = ResourceFactory.combine(
            List.of(
                // the original ResourceCollection
                rc1,
                // a duplicate entry
                resourceFactory.newResource(two),
                // a new entry
                resourceFactory.newResource(twoDir)
            )
        );

        URI[] expected = new URI[] {
            one.toUri(),
            two.toUri(),
            three.toUri(),
            twoDir.toUri()
        };

        List<URI> actual = new ArrayList<>();
        assertThat(rc2, instanceOf(CombinedResource.class));
        if (rc2 instanceof CombinedResource combinedResource)
        {
            for (Resource res : combinedResource.getResources())
                actual.add(res.getURI());
        }
        assertThat(actual, contains(expected));
    }

    @Test
    public void testIterable()
    {
        Path one = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/one");
        Path two = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/two");
        Path three = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/three");
        Path dirFoo = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/two/dir");

        Resource compositeA = ResourceFactory.combine(
            List.of(
                resourceFactory.newResource(one),
                resourceFactory.newResource(two),
                resourceFactory.newResource(three)
            )
        );

        Resource compositeB = ResourceFactory.combine(
            List.of(
                // the original composite Resource
                compositeA,
                // a duplicate entry
                resourceFactory.newResource(two),
                // a new entry
                resourceFactory.newResource(dirFoo)
            )
        );

        List<URI> actual = new ArrayList<>();
        for (Resource resource: compositeB)
        {
            actual.add(resource.getURI());
        }

        URI[] expected = new URI[] {
            one.toUri(),
            two.toUri(),
            three.toUri(),
            dirFoo.toUri()
        };

        assertThat(actual, contains(expected));
    }

    /**
     * Demonstrate behavior of ResourceCollection.resolve() when dealing with
     * conflicting names between Directories and Files.
     */
    @ParameterizedTest
    @ValueSource(strings = {"foo", "/foo", "foo/", "/foo/"})
    public void testResolveConflictDirAndFile(String input) throws IOException
    {
        Path base = workDir.getEmptyPathDir();
        Path dirA = base.resolve("dirA");
        FS.ensureDirExists(dirA);
        Files.createDirectory(dirA.resolve("foo"));

        Path dirB = base.resolve("dirB");
        FS.ensureDirExists(dirB);
        Files.createDirectory(dirB.resolve("foo"));

        Path dirC = base.resolve("dirC");
        FS.ensureDirExists(dirC);
        Files.createFile(dirC.resolve("foo"));

        Resource rc = ResourceFactory.combine(
            ResourceFactory.root().newResource(dirA),
            ResourceFactory.root().newResource(dirB),
            ResourceFactory.root().newResource(dirC)
        );

        Resource result = rc.resolve(input);
        assertThat(result, instanceOf(PathResource.class));
        assertThat(result.getPath().toUri().toASCIIString(), endsWith("dirC/foo"));
        assertFalse(result.isDirectory());
    }

    @Test
    public void testUserSpaceConfigurationNoGlob() throws Exception
    {
        Path base = workDir.getEmptyPathDir();
        Path dir = base.resolve("dir");
        FS.ensureDirExists(dir);
        Path foo = dir.resolve("foo");
        FS.ensureDirExists(foo);
        Path bar = dir.resolve("bar");
        FS.ensureDirExists(bar);
        Path content = foo.resolve("test.txt");
        Files.writeString(content, "Test");

        // This represents the user-space raw configuration
        String config = String.format("%s,%s,%s", dir, foo, bar);

        // To use this, we need to split it (and optionally honor globs)
        List<Resource> resources = resourceFactory.split(config);
        // Now let's create a ResourceCollection from this list of URIs
        // Since this is user space, we cannot know ahead of time what
        // this list contains, so we mount because we assume there
        // will be necessary things to mount
        Resource rc = ResourceFactory.combine(resources);
        assertThat(getContent(rc, "test.txt"), is("Test"));
    }

    @Test
    public void testUserSpaceConfigurationWithGlob() throws Exception
    {
        Path base = workDir.getEmptyPathDir();
        Path dir = base.resolve("dir");
        FS.ensureDirExists(dir);
        Path foo = dir.resolve("foo");
        FS.ensureDirExists(foo);
        Path bar = dir.resolve("bar");
        FS.ensureDirExists(bar);
        createJar(bar.resolve("lib-foo.jar"), "/test.txt", "Test inside lib-foo.jar");
        createJar(bar.resolve("lib-zed.jar"), "/testZed.txt", "TestZed inside lib-zed.jar");

        // This represents the user-space raw configuration with a glob
        String config = String.format("%s;%s;%s%s*", dir, foo, bar, File.separator);

        // To use this, we need to split it (and optionally honor globs)
        List<Resource> resources = resourceFactory.split(config);
        // Now let's create a ResourceCollection from this list of URIs
        // Since this is user space, we cannot know ahead of time what
        // this list contains, so we mount because we assume there
        // will be necessary things to mount
        Resource rc = ResourceFactory.combine(resources);
        assertThat(getContent(rc, "test.txt"), is("Test inside lib-foo.jar"));
        assertThat(getContent(rc, "testZed.txt"), is("TestZed inside lib-zed.jar"));
    }

    /**
     * Tests of {@link CombinedResource#contains(Resource)} consisting of only simple PathResources,
     * where the "other" Resource is a simple Resource (like a PathResource)
     */
    @Test
    public void testContainsSimple()
    {
        Path one = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/one");
        Path two = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/two");
        Path three = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/three");
        Path four = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/four");

        Resource composite = ResourceFactory.combine(
            List.of(
                resourceFactory.newResource(one),
                resourceFactory.newResource(two),
                resourceFactory.newResource(three)
            )
        );

        Resource oneTxt = composite.resolve("1.txt");
        assertThat(oneTxt, notNullValue());
        assertThat(composite.contains(oneTxt), is(true));

        Resource dir = composite.resolve("dir");
        assertThat(dir, notNullValue());
        assertThat(composite.contains(dir), is(true));

        Resource threeTxt = composite.resolve("dir/3.txt");
        assertThat(oneTxt, notNullValue());
        assertThat(composite.contains(threeTxt), is(true));
        assertThat(dir.contains(threeTxt), is(true));

        Resource fourth = resourceFactory.newResource(four);

        // some negations
        assertThat(oneTxt.contains(composite), is(false));
        assertThat(threeTxt.contains(composite), is(false));
        assertThat(oneTxt.contains(dir), is(false));
        assertThat(threeTxt.contains(dir), is(false));
        assertThat(dir.contains(composite), is(false));

        assertThat(composite.contains(fourth), is(false));
        assertThat(dir.contains(fourth), is(false));
    }

    /**
     * Tests of {@link CombinedResource#contains(Resource)} consisting of mixed PathResources types (file system and jars),
     * testing against "other" single Resource (not a CombinedResource)
     */
    @Test
    public void testMixedContainsSimple() throws IOException
    {
        Path testDir = workDir.getEmptyPathDir();

        // Create a JAR file with contents
        Path testJar = testDir.resolve("test.jar");

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI jarUri = URIUtil.uriJarPrefix(testJar.toUri(), "!/");
        try (FileSystem zipfs = FileSystems.newFileSystem(jarUri, env))
        {
            Path root = zipfs.getPath("/");
            Files.writeString(root.resolve("1.txt"), "Contents of 1.txt from TEST JAR", StandardCharsets.UTF_8);
            Path dir = root.resolve("dir");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("2.txt"), "Contents of 2.txt from TEST JAR", StandardCharsets.UTF_8);
        }

        // Create a JAR that is never part of the CombinedResource.
        Path unusedJar = testDir.resolve("unused.jar");
        URI unusedJarURI = URIUtil.uriJarPrefix(unusedJar.toUri(), "!/");
        try (FileSystem zipfs = FileSystems.newFileSystem(unusedJarURI, env))
        {
            Path root = zipfs.getPath("/");
            Files.writeString(root.resolve("unused.txt"), "Contents of unused.txt from UNUSED JAR", StandardCharsets.UTF_8);
        }

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource archiveResource = resourceFactory.newResource(jarUri);
            Path one = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/one");
            Path two = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/two");
            Path three = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/three");
            Path four = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/four");

            Resource composite = ResourceFactory.combine(
                List.of(
                    resourceFactory.newResource(one),
                    resourceFactory.newResource(two),
                    resourceFactory.newResource(three),
                    archiveResource
                )
            );

            Resource oneTxt = composite.resolve("1.txt");
            assertThat(oneTxt, notNullValue());
            assertThat(composite.contains(oneTxt), is(true));

            Resource dir = composite.resolve("dir");
            assertThat(dir, notNullValue());
            assertThat(composite.contains(dir), is(true));

            Resource threeTxt = composite.resolve("dir/3.txt");
            assertThat(oneTxt, notNullValue());
            assertThat(composite.contains(threeTxt), is(true));
            assertThat(dir.contains(threeTxt), is(true));

            // some negations
            Resource fourth = resourceFactory.newResource(four);
            Resource fourText = fourth.resolve("four.txt");


            assertThat(oneTxt.contains(composite), is(false));
            assertThat(threeTxt.contains(composite), is(false));
            assertThat(oneTxt.contains(dir), is(false));
            assertThat(threeTxt.contains(dir), is(false));
            assertThat(dir.contains(composite), is(false));

            assertThat(composite.contains(fourth), is(false));
            assertThat(composite.contains(fourText), is(false));
            assertThat(dir.contains(fourth), is(false));

            Resource unused = resourceFactory.newResource(unusedJarURI);
            assertThat(composite.contains(unused), is(false));
            Resource unusedText = unused.resolve("unused.txt");
            assertThat(composite.contains(unusedText), is(false));
        }
    }

    /**
     * Tests of {@link CombinedResource#contains(Resource)} consisting of mixed PathResources types (file system and jars),
     * testing against "other" which are CombinedResource instances of their own.
     */
    @Test
    public void testMixedContainsOtherCombinedResource() throws IOException
    {
        Path testDir = workDir.getEmptyPathDir();

        // Create a JAR file with contents
        Path testJar = testDir.resolve("test.jar");

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI jarUri = URIUtil.uriJarPrefix(testJar.toUri(), "!/");
        try (FileSystem zipfs = FileSystems.newFileSystem(jarUri, env))
        {
            Path root = zipfs.getPath("/");
            Files.writeString(root.resolve("1.txt"), "Contents of 1.txt from TEST JAR", StandardCharsets.UTF_8);
            Path dir = root.resolve("dir");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("2.txt"), "Contents of dir/2.txt from TEST JAR", StandardCharsets.UTF_8);
        }

        // Create a JAR that is never part of the CombinedResource.
        Path unusedJar = testDir.resolve("unused.jar");
        URI unusedJarURI = URIUtil.uriJarPrefix(unusedJar.toUri(), "!/");
        try (FileSystem zipfs = FileSystems.newFileSystem(unusedJarURI, env))
        {
            Path root = zipfs.getPath("/");
            Files.writeString(root.resolve("unused.txt"), "Contents of unused.txt from UNUSED JAR", StandardCharsets.UTF_8);
            Path dir = root.resolve("dir");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("un.txt"), "Contents of dir/un.txt from TEST JAR", StandardCharsets.UTF_8);
        }

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource archiveResource = resourceFactory.newResource(jarUri);
            Resource unused = resourceFactory.newResource(unusedJarURI);
            Resource one = resourceFactory.newResource(MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/one"));
            Resource two = resourceFactory.newResource(MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/two"));
            Resource three = resourceFactory.newResource(MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/three"));
            Resource four = resourceFactory.newResource(MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/four"));

            Resource composite = ResourceFactory.combine(
                List.of(
                    one,
                    two,
                    three,
                    archiveResource
                )
            );

            Resource other = ResourceFactory.combine(
                List.of(
                    one.resolve("dir"),
                    two.resolve("dir"),
                    three.resolve("dir")
                )
            );

            assertThat(composite.contains(other), is(true));

            other = ResourceFactory.combine(
                List.of(
                    one.resolve("dir"),
                    two.resolve("dir"),
                    three.resolve("dir"),
                    archiveResource.resolve("dir")
                )
            );

            assertThat(composite.contains(other), is(true));

            // some negations

            other = ResourceFactory.combine(
                List.of(
                    one.resolve("dir"),
                    four.resolve("dir") // not in composite
                )
            );

            assertThat(composite.contains(other), is(false));


            other = ResourceFactory.combine(
                List.of(
                    archiveResource.resolve("dir"),
                    unused.resolve("dir")
                )
            );

            assertThat(composite.contains(other), is(false));
        }
    }

    @Test
    public void testContainsAndPathTo()
    {
        Path one = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/one");
        Path two = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/two");
        Path three = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/three");
        Path four = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/four");

        Resource composite = ResourceFactory.combine(
            List.of(
                resourceFactory.newResource(one),
                resourceFactory.newResource(two),
                resourceFactory.newResource(three)
            )
        );

        Resource compositeAlt = ResourceFactory.combine(
            List.of(
                resourceFactory.newResource(one),
                resourceFactory.newResource(four)
            )
        );

        Resource fourth = resourceFactory.newResource(four);

        Resource oneTxt = composite.resolve("1.txt");
        assertThat(oneTxt, notNullValue());
        assertThat(composite.contains(oneTxt), is(true));

        Path rel = composite.getPathTo(oneTxt);
        assertThat(rel, notNullValue());
        assertThat(rel.getNameCount(), is(1));
        assertThat(rel.getName(0).toString(), is("1.txt"));

        Resource dir = composite.resolve("dir");
        assertThat(dir, notNullValue());
        assertThat(composite.contains(dir), is(true));

        rel = composite.getPathTo(dir);
        assertThat(rel, notNullValue());
        assertThat(rel.getNameCount(), is(1));
        assertThat(rel.getName(0).toString(), is("dir"));

        Resource threeTxt = composite.resolve("dir/3.txt");
        assertThat(oneTxt, notNullValue());
        assertThat(composite.contains(threeTxt), is(true));
        assertThat(dir.contains(threeTxt), is(true));

        rel = composite.getPathTo(threeTxt);
        assertThat(rel, notNullValue());
        assertThat(rel.getNameCount(), is(2));
        assertThat(rel.getName(0).toString(), is("dir"));
        assertThat(rel.getName(1).toString(), is("3.txt"));

        // some negations
        assertThat(oneTxt.contains(composite), is(false));
        assertThat(threeTxt.contains(composite), is(false));
        assertThat(oneTxt.contains(dir), is(false));
        assertThat(threeTxt.contains(dir), is(false));
        assertThat(dir.contains(composite), is(false));

        assertThat(composite.contains(fourth), is(false));
        assertThat(dir.contains(fourth), is(false));

        Resource dirAlt = compositeAlt.resolve("dir");
        assertThat(compositeAlt.contains(dirAlt), is(true));
        assertThat(composite.contains(dirAlt), is(false));
        assertThat(composite.getPathTo(dirAlt), nullValue());
    }

    @Test
    public void testGetFileName()
    {
        Path one = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/one");
        Path two = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/two");
        Path three = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/three");

        Resource composite = ResourceFactory.combine(
            List.of(
                resourceFactory.newResource(one),
                resourceFactory.newResource(two),
                resourceFactory.newResource(three)
            )
        );

        assertThat(composite.getFileName(), nullValue());
        Resource dir = composite.resolve("dir");
        assertThat(dir.getFileName(), is("dir"));
    }

    @Test
    public void testGetAllResources()
    {
        Path one = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/one");
        Path two = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/two");
        Path three = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/three");

        Resource composite = ResourceFactory.combine(
            List.of(
                resourceFactory.newResource(one),
                resourceFactory.newResource(two),
                resourceFactory.newResource(three)
            )
        );

        assertThat(composite.getAllResources().stream().map(composite::getPathTo).map(Path::toString).toList(), containsInAnyOrder(
            "1.txt",
            "2.txt",
            "3.txt",
            "dir",
            FS.separators("dir/1.txt"),
            FS.separators("dir/2.txt"),
            FS.separators("dir/3.txt")
        ));
    }

    private void createJar(Path outputJar, String entryName, String entryContents) throws IOException
    {
        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI uri = URI.create("jar:" + outputJar.toUri().toASCIIString());
        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            Files.writeString(zipfs.getPath(entryName), entryContents, StandardCharsets.UTF_8);
        }
    }

    static String getContent(Resource r, String path) throws Exception
    {
        return Files.readString(r.resolve(path).getPath(), StandardCharsets.UTF_8);
    }
}
