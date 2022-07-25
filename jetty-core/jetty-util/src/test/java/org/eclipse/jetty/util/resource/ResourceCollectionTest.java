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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

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

    @SuppressWarnings("resource")
    @Test
    public void testUndefinedResourceArrayThrowsIAE()
    {
        assertThrows(IllegalArgumentException.class, () ->
            new ResourceCollection());
    }

    @SuppressWarnings("resource")
    @Test
    public void testEmptyResourceArrayThrowsIAE()
    {
        assertThrows(IllegalArgumentException.class, () ->
        {
            Resource[] res = new Resource[0];
            new ResourceCollection(res);
        });
    }

    @SuppressWarnings("resource")
    @Test
    public void testResourceArrayWithNullThrowsNPE()
    {
        assertThrows(NullPointerException.class, () ->
        {
            Resource[] col = new Resource[]{null};
            new ResourceCollection(col); // throws NPE due to List.of() rules
        });
    }

    @SuppressWarnings("resource")
    @Test
    public void testNullCsvThrowsIAE()
    {
        assertThrows(IllegalArgumentException.class, () ->
        {
            String csv = null;
            new ResourceCollection(csv); // throws IAE
        });
    }

    @SuppressWarnings("resource")
    @Test
    public void testEmptyCsvThrowsIAE()
    {
        assertThrows(IllegalArgumentException.class, () ->
        {
            String csv = "";
            new ResourceCollection(csv); // throws IAE
        });
    }

    @SuppressWarnings("resource")
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
    public void testList() throws Exception
    {
        try (ResourceCollection rc1 = new ResourceCollection(
            Resource.newResource("src/test/resources/org/eclipse/jetty/util/resource/one/"),
            Resource.newResource("src/test/resources/org/eclipse/jetty/util/resource/two/"),
            Resource.newResource("src/test/resources/org/eclipse/jetty/util/resource/three/")))
        {
            assertThat(rc1.list(), contains("1.txt", "2.txt", "3.txt", "dir/"));
            assertThat(rc1.resolve("dir").list(), contains("1.txt", "2.txt", "3.txt"));
            assertThat(rc1.resolve("unknown").list(), nullValue());
        }
    }

    @Test
    public void testMultipleSources1() throws Exception
    {
        try (ResourceCollection rc1 = new ResourceCollection(Resource.fromList(List.of(
                "src/test/resources/org/eclipse/jetty/util/resource/one/",
                "src/test/resources/org/eclipse/jetty/util/resource/two/",
                "src/test/resources/org/eclipse/jetty/util/resource/three/"),
            false)))
        {
            assertEquals(getContent(rc1, "1.txt"), "1 - one");
            assertEquals(getContent(rc1, "2.txt"), "2 - two");
            assertEquals(getContent(rc1, "3.txt"), "3 - three");
        }

        try (ResourceCollection rc2 = new ResourceCollection(
            "src/test/resources/org/eclipse/jetty/util/resource/one/," +
                "src/test/resources/org/eclipse/jetty/util/resource/two/," +
                "src/test/resources/org/eclipse/jetty/util/resource/three/"))
        {
            assertEquals(getContent(rc2, "1.txt"), "1 - one");
            assertEquals(getContent(rc2, "2.txt"), "2 - two");
            assertEquals(getContent(rc2, "3.txt"), "3 - three");
        }
    }

    @Test
    public void testMergedDir() throws Exception
    {
        ResourceCollection rc = new ResourceCollection(Resource.fromList(List.of(
                "src/test/resources/org/eclipse/jetty/util/resource/one/",
                "src/test/resources/org/eclipse/jetty/util/resource/two/",
                "src/test/resources/org/eclipse/jetty/util/resource/three/"),
            false));

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
        ResourceCollection rc = new ResourceCollection(Resource.fromList(List.of(
                "src/test/resources/org/eclipse/jetty/util/resource/one/",
                "src/test/resources/org/eclipse/jetty/util/resource/two/",
                "src/test/resources/org/eclipse/jetty/util/resource/three/"),
            false));

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
        try (BufferedReader br = Files.newBufferedReader(resource.getPath(), StandardCharsets.UTF_8))
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
