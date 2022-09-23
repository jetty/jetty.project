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
import java.util.function.Function;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.URIUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class ResourceCollectionTest
{
    private final ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable();
    public WorkDir workDir;

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
        Path testBaseDir = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource");
        Path one = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/one");
        Path two = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/two");
        Path three = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/three");

        ResourceCollection rc = Resource.combine(
            resourceFactory.newResource(one),
            resourceFactory.newResource(two),
            resourceFactory.newResource(three)
        );

        Function<Resource, String> relativizeToTestResources = (r) -> testBaseDir.toUri().relativize(r.getURI()).toASCIIString();

        List<Resource> listing = rc.list();
        List<String> listingFilenames = listing.stream().map(relativizeToTestResources).toList();

        String[] expected = new String[] {
            "one/dir/",
            "one/1.txt",
            "two/2.txt",
            "two/dir/",
            "two/1.txt",
            "three/3.txt",
            "three/2.txt",
            "three/dir/"
        };

        assertThat(listingFilenames, containsInAnyOrder(expected));

        listingFilenames = rc.resolve("dir").list().stream().map(relativizeToTestResources).toList();

        expected = new String[] {
            "one/dir/1.txt",
            "two/dir/2.txt",
            "three/dir/3.txt"
        };

        assertThat(listingFilenames, containsInAnyOrder(expected));

        assertThat(rc.resolve("unknown").list(), is(empty()));

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

        ResourceCollection rc = Resource.combine(
            resourceFactory.newResource(one),
            resourceFactory.newResource(two),
            resourceFactory.newResource(three)
        );

        // This should return a ResourceCollection with 3 `/dir/` sub-directories.
        Resource r = rc.resolve("dir");
        assertTrue(r instanceof ResourceCollection);
        rc = (ResourceCollection)r;
        assertEquals(getContent(rc, "1.txt"), "1 - one (in dir)");
        assertEquals(getContent(rc, "2.txt"), "2 - two (in dir)");
        assertEquals(getContent(rc, "3.txt"), "3 - three (in dir)");
    }

    @Test
    public void testCopyTo() throws Exception
    {
        Path one = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/one");
        Path two = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/two");
        Path three = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/three");

        ResourceCollection rc = Resource.combine(
                resourceFactory.newResource(one),
                resourceFactory.newResource(two),
                resourceFactory.newResource(three)
        );
        Path destDir = workDir.getEmptyPathDir();
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

    @Test
    public void testResourceCollectionInResourceCollection()
    {
        Path one = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/one");
        Path two = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/two");
        Path three = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/three");
        Path twoDir = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/two/dir");

        ResourceCollection rc1 = Resource.combine(
            List.of(
                resourceFactory.newResource(one),
                resourceFactory.newResource(two),
                resourceFactory.newResource(three)
            )
        );

        ResourceCollection rc2 = Resource.combine(
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
        for (Resource res: rc2.getResources())
        {
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

        Resource compositeA = Resource.combine(
            List.of(
                resourceFactory.newResource(one),
                resourceFactory.newResource(two),
                resourceFactory.newResource(three)
            )
        );

        Resource compositeB = Resource.combine(
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

        Resource rc = Resource.combine(
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
        List<URI> uris = URIUtil.split(config);
        // Now let's create a ResourceCollection from this list of URIs
        // Since this is user space, we cannot know ahead of time what
        // this list contains, so we mount because we assume there
        // will be necessary things to mount
        ResourceCollection rc = resourceFactory.newResource(uris);
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
        List<URI> uris = URIUtil.split(config);
        // Now let's create a ResourceCollection from this list of URIs
        // Since this is user space, we cannot know ahead of time what
        // this list contains, so we mount because we assume there
        // will be necessary things to mount
        ResourceCollection rc = resourceFactory.newResource(uris);
        assertThat(getContent(rc, "test.txt"), is("Test inside lib-foo.jar"));
        assertThat(getContent(rc, "testZed.txt"), is("TestZed inside lib-zed.jar"));
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
