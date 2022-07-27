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

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class ResourceCollectionTest
{
    public WorkDir workDir;

    @Test
    public void testList() throws Exception
    {
        Path one = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/one");
        Path two = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/two");
        Path three = MavenTestingUtils.getTestResourcePathDir("org/eclipse/jetty/util/resource/three");

        ResourceCollection rc = Resource.of(
            Resource.newResource(one),
            Resource.newResource(two),
            Resource.newResource(three)
        );
        assertThat(rc.list(), contains("1.txt", "2.txt", "3.txt", "dir/"));
        assertThat(rc.resolve("dir").list(), contains("1.txt", "2.txt", "3.txt"));
        assertThat(rc.resolve("unknown").list(), nullValue());

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

        ResourceCollection rc = Resource.of(
            Resource.newResource(one),
            Resource.newResource(two),
            Resource.newResource(three)
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

        ResourceCollection rc = Resource.of(
                Resource.newResource(one),
                Resource.newResource(two),
                Resource.newResource(three)
        );
        Path destDir = workDir.getEmptyPathDir();
        rc.copyTo(destDir);

        Resource r = Resource.newResource(destDir);
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

        ResourceCollection rc1 = new ResourceCollection(
            List.of(
                Resource.newResource(one),
                Resource.newResource(two),
                Resource.newResource(three)
            )
        );

        ResourceCollection rc2 = new ResourceCollection(
            List.of(
                // the original ResourceCollection
                rc1,
                // a duplicate entry
                Resource.newResource(two),
                // a new entry
                Resource.newResource(twoDir)
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
        List<URI> uris = Resource.split(config);
        // Now let's create a ResourceCollection from this list of URIs
        // Since this is user space, we cannot know ahead of time what
        // this list contains, so we mount because we assume there
        // will be necessary things to mount
        try (Resource.Mount mount = Resource.mountCollection(uris))
        {
            ResourceCollection rc = (ResourceCollection)mount.root();
            assertThat(getContent(rc, "test.txt"), is("Test"));
        }
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
        List<URI> uris = Resource.split(config);
        // Now let's create a ResourceCollection from this list of URIs
        // Since this is user space, we cannot know ahead of time what
        // this list contains, so we mount because we assume there
        // will be necessary things to mount
        try (Resource.Mount mount = Resource.mountCollection(uris))
        {
            ResourceCollection rc = (ResourceCollection)mount.root();
            assertThat(getContent(rc, "test.txt"), is("Test inside lib-foo.jar"));
            assertThat(getContent(rc, "testZed.txt"), is("TestZed inside lib-zed.jar"));
        }
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
