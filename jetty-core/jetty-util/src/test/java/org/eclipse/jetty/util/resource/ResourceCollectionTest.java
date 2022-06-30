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

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Arrays;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class ResourceCollectionTest
{
    public WorkDir workdir;

    @Test
    public void testUnsetCollectionThrowsISE()
    {
        ResourceCollection coll = new ResourceCollection();

        assertThrowIllegalStateException(coll);
    }

    @Test
    public void testEmptyResourceArrayThrowsISE()
    {
        ResourceCollection coll = new ResourceCollection(new Resource[0]);

        assertThrowIllegalStateException(coll);
    }

    @Test
    public void testResourceArrayWithNullThrowsISE()
    {
        ResourceCollection coll = new ResourceCollection(new Resource[]{null});

        assertThrowIllegalStateException(coll);
    }

    @Test
    public void testEmptyStringArrayThrowsISE()
    {
        ResourceCollection coll = new ResourceCollection(new String[0]);

        assertThrowIllegalStateException(coll);
    }

    @Test
    public void testStringArrayWithNullThrowsIAE()
    {
        assertThrows(IllegalArgumentException.class,
            () -> new ResourceCollection(new String[]{null}));
    }

    @Test
    public void testNullCsvThrowsIAE()
    {
        assertThrows(IllegalArgumentException.class, () ->
        {
            String csv = null;
            new ResourceCollection(csv); // throws IAE
        });
    }

    @Test
    public void testEmptyCsvThrowsIAE()
    {
        assertThrows(IllegalArgumentException.class, () ->
        {
            String csv = "";
            new ResourceCollection(csv); // throws IAE
        });
    }

    @Test
    public void testBlankCsvThrowsIAE()
    {
        assertThrows(IllegalArgumentException.class, () ->
        {
            String csv = ",,,,";
            new ResourceCollection(csv); // throws IAE
        });
    }

    @Test
    public void testSetResourceArrayNullThrowsISE()
    {
        // Create a ResourceCollection with one valid entry
        Path path = MavenTestingUtils.getTargetPath();
        Resource resource = Resource.newResource(path);
        ResourceCollection coll = new ResourceCollection(resource);

        // Reset collection to invalid state
        coll.setResources((Resource[])null);

        assertThrowIllegalStateException(coll);
    }

    @Test
    public void testSetResourceEmptyThrowsISE()
    {
        // Create a ResourceCollection with one valid entry
        Path path = MavenTestingUtils.getTargetPath();
        Resource resource = Resource.newResource(path);
        ResourceCollection coll = new ResourceCollection(resource);

        // Reset collection to invalid state
        coll.setResources(new Resource[0]);

        assertThrowIllegalStateException(coll);
    }

    @Test
    public void testSetResourceAllNullsThrowsISE()
    {
        // Create a ResourceCollection with one valid entry
        Path path = MavenTestingUtils.getTargetPath();
        Resource resource = Resource.newResource(path);
        ResourceCollection coll = new ResourceCollection(resource);

        // Reset collection to invalid state
        assertThrows(IllegalStateException.class, () -> coll.setResources(new Resource[]{null, null, null}));

        // Ensure not modified.
        assertThat(coll.getResources().size(), is(1));
    }

    private void assertThrowIllegalStateException(ResourceCollection coll)
    {
        assertThrows(IllegalStateException.class, () -> coll.resolve("foo"));
        assertThrows(IllegalStateException.class, coll::exists);
        assertThrows(IllegalStateException.class, coll::getPath);
        assertThrows(IllegalStateException.class, coll::getInputStream);
        assertThrows(IllegalStateException.class, coll::getReadableByteChannel);
        assertThrows(IllegalStateException.class, coll::getURI);
        assertThrows(IllegalStateException.class, coll::getName);
        assertThrows(IllegalStateException.class, coll::isDirectory);
        assertThrows(IllegalStateException.class, coll::lastModified);
        assertThrows(IllegalStateException.class, coll::list);
        assertThrows(IllegalStateException.class, () ->
        {
            Path destPath = workdir.getPathFile("bar");
            coll.copyTo(destPath);
        });
    }

    @Test
    public void testList() throws Exception
    {
        ResourceCollection rc1 = new ResourceCollection(
            Resource.newResource("src/test/resources/org/eclipse/jetty/util/resource/one/"),
            Resource.newResource("src/test/resources/org/eclipse/jetty/util/resource/two/"),
            Resource.newResource("src/test/resources/org/eclipse/jetty/util/resource/three/"));

        assertThat(rc1.list(), contains("1.txt", "2.txt", "3.txt", "dir/"));
        assertThat(rc1.resolve("dir").list(), contains("1.txt", "2.txt", "3.txt"));
        assertThat(rc1.resolve("unknown").list(), nullValue());
    }

    @Test
    public void testMultipleSources1() throws Exception
    {
        ResourceCollection rc1 = new ResourceCollection(new String[]{
            "src/test/resources/org/eclipse/jetty/util/resource/one/",
            "src/test/resources/org/eclipse/jetty/util/resource/two/",
            "src/test/resources/org/eclipse/jetty/util/resource/three/"
        });
        assertEquals(getContent(rc1, "1.txt"), "1 - one");
        assertEquals(getContent(rc1, "2.txt"), "2 - two");
        assertEquals(getContent(rc1, "3.txt"), "3 - three");

        ResourceCollection rc2 = new ResourceCollection(
            "src/test/resources/org/eclipse/jetty/util/resource/one/," +
                "src/test/resources/org/eclipse/jetty/util/resource/two/," +
                "src/test/resources/org/eclipse/jetty/util/resource/three/"
        );
        assertEquals(getContent(rc2, "1.txt"), "1 - one");
        assertEquals(getContent(rc2, "2.txt"), "2 - two");
        assertEquals(getContent(rc2, "3.txt"), "3 - three");
    }

    @Test
    public void testMergedDir() throws Exception
    {
        ResourceCollection rc = new ResourceCollection(new String[]{
            "src/test/resources/org/eclipse/jetty/util/resource/one/",
            "src/test/resources/org/eclipse/jetty/util/resource/two/",
            "src/test/resources/org/eclipse/jetty/util/resource/three/"
        });

        Resource r = rc.resolve("dir");
        assertTrue(r instanceof ResourceCollection);
        rc = (ResourceCollection)r;
        assertEquals(getContent(rc, "1.txt"), "1 - one");
        assertEquals(getContent(rc, "2.txt"), "2 - two");
        assertEquals(getContent(rc, "3.txt"), "3 - three");
    }

    @Test
    public void testCopyTo() throws Exception
    {
        ResourceCollection rc = new ResourceCollection(new String[]{
            "src/test/resources/org/eclipse/jetty/util/resource/one/",
            "src/test/resources/org/eclipse/jetty/util/resource/two/",
            "src/test/resources/org/eclipse/jetty/util/resource/three/"
        });

        File dest = MavenTestingUtils.getTargetTestingDir("copyto");
        FS.ensureDirExists(dest);
        rc.copyTo(dest.toPath());

        Resource r = Resource.newResource(dest.toURI());
        assertEquals(getContent(r, "1.txt"), "1 - one");
        assertEquals(getContent(r, "2.txt"), "2 - two");
        assertEquals(getContent(r, "3.txt"), "3 - three");
        r = r.resolve("dir");
        assertEquals(getContent(r, "1.txt"), "1 - one");
        assertEquals(getContent(r, "2.txt"), "2 - two");
        assertEquals(getContent(r, "3.txt"), "3 - three");

        IO.delete(dest);
    }

    static String getContent(Resource r, String path) throws Exception
    {
        Resource resource = r.resolve(path);
        StringBuilder buffer = new StringBuilder();
        try (InputStream in = resource.getInputStream();
             InputStreamReader reader = new InputStreamReader(in);
             BufferedReader br = new BufferedReader(reader))
        {
            String line;
            while ((line = br.readLine()) != null)
            {
                buffer.append(line);
            }
        }
        return buffer.toString();
    }
}
