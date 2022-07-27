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

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
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
        assertThrows(IllegalArgumentException.class, () -> Resource.of((Resource[])null));
    }

    @Test
    public void testEmptyResourceArrayThrowsISE()
    {
        assertThrows(IllegalArgumentException.class, () -> Resource.of(new Resource[0]));
    }

    @Test
    public void testResourceArrayWithNullThrowsISE()
    {
        assertThrows(IllegalArgumentException.class, () -> Resource.of(new Resource[]{null}));
    }

    @Test
    public void testEmptyStringArrayThrowsISE()
    {
        assertThrows(IllegalArgumentException.class, () -> Resource.of(new String[0]));
    }

    @Test
    public void testStringArrayWithNullThrowsIAE()
    {
        assertThrows(IllegalArgumentException.class, () -> Resource.of(new String[]{null}));
    }

    @Test
    public void testNullCsvThrowsIAE()
    {
        assertThrows(IllegalArgumentException.class, () ->
        {
            String csv = null;
            Resource.of(csv); // throws IAE
        });
    }

    @Test
    public void testEmptyCsvThrowsIAE()
    {
        assertThrows(IllegalArgumentException.class, () ->
        {
            String csv = "";
            Resource.of(csv); // throws IAE
        });
    }

    @Test
    public void testBlankCsvThrowsIAE()
    {
        assertThrows(IllegalArgumentException.class, () ->
        {
            String csv = ",,,,";
            Resource.of(csv); // throws IAE
        });
    }

    @Test
    public void testResourceCollectionIsImmutable()
    {
        // Create a ResourceCollection with one valid entry
        Path path = MavenTestingUtils.getTargetPath();
        Resource resource = Resource.newResource(path);
        ResourceCollection collection = Resource.of(resource);
        assertThrows(UnsupportedOperationException.class, () -> collection.getResources().clear());
    }

    @Test
    public void testList() throws Exception
    {
        Resource rc1 = Resource.of(
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
        Resource rc1 = Resource.of(
            "src/test/resources/org/eclipse/jetty/util/resource/one/",
            "src/test/resources/org/eclipse/jetty/util/resource/two/",
            "src/test/resources/org/eclipse/jetty/util/resource/three/"
        );
        assertEquals(getContent(rc1, "1.txt"), "1 - one");
        assertEquals(getContent(rc1, "2.txt"), "2 - two");
        assertEquals(getContent(rc1, "3.txt"), "3 - three");

        Resource rc2 = Resource.of(
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
        Resource rc = Resource.of(
            "src/test/resources/org/eclipse/jetty/util/resource/one/",
            "src/test/resources/org/eclipse/jetty/util/resource/two/",
            "src/test/resources/org/eclipse/jetty/util/resource/three/"
        );

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
        Resource rc = Resource.of("src/test/resources/org/eclipse/jetty/util/resource/one/",
            "src/test/resources/org/eclipse/jetty/util/resource/two/",
            "src/test/resources/org/eclipse/jetty/util/resource/three/");

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
