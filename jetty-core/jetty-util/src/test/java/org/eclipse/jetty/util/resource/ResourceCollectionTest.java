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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
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
    public void testNullCsvThrowsIAE()
    {
        assertThrows(IllegalArgumentException.class, () ->
        {
            String csv = null;
            Resource.mountCollection(csv); // throws IAE
        });
    }

    @SuppressWarnings("resource")
    @Test
    public void testEmptyCsvThrowsIAE()
    {
        assertThrows(IllegalArgumentException.class, () ->
        {
            String csv = "";
            Resource.mountCollection(csv); // throws IAE
        });
    }

    @SuppressWarnings("resource")
    @Test
    public void testBlankCsvThrowsIAE()
    {
        assertThrows(IllegalArgumentException.class, () ->
        {
            String csv = ",,,,";
            Resource.mountCollection(csv); // throws IAE
        });
    }

    @Test
    public void testList() throws Exception
    {
        try (Resource.Mount rcMount = Resource.mountCollection(List.of(
            Resource.newResource("src/test/resources/org/eclipse/jetty/util/resource/one/"),
            Resource.newResource("src/test/resources/org/eclipse/jetty/util/resource/two/"),
            Resource.newResource("src/test/resources/org/eclipse/jetty/util/resource/three/"))))
        {
            ResourceCollection rc1 = (ResourceCollection)rcMount.root();
            assertThat(rc1.list(), contains("1.txt", "2.txt", "3.txt", "dir/"));
            assertThat(rc1.resolve("dir").list(), contains("1.txt", "2.txt", "3.txt"));
            assertThat(rc1.resolve("unknown").list(), nullValue());
        }
    }

    @Test
    public void testMultipleSources1() throws Exception
    {
        try (Resource.Mount rcMount = Resource.mountCollection(Resource.fromList(List.of(
                "src/test/resources/org/eclipse/jetty/util/resource/one/",
                "src/test/resources/org/eclipse/jetty/util/resource/two/",
                "src/test/resources/org/eclipse/jetty/util/resource/three/"),
            false)))
        {
            ResourceCollection rc1 = (ResourceCollection)rcMount.root();
            assertEquals(getContent(rc1, "1.txt"), "1 - one");
            assertEquals(getContent(rc1, "2.txt"), "2 - two");
            assertEquals(getContent(rc1, "3.txt"), "3 - three");
        }

        try (Resource.Mount rcMount = Resource.mountCollection(
            "src/test/resources/org/eclipse/jetty/util/resource/one/," +
                "src/test/resources/org/eclipse/jetty/util/resource/two/," +
                "src/test/resources/org/eclipse/jetty/util/resource/three/"))
        {
            ResourceCollection rc2 = (ResourceCollection)rcMount.root();
            assertEquals(getContent(rc2, "1.txt"), "1 - one");
            assertEquals(getContent(rc2, "2.txt"), "2 - two");
            assertEquals(getContent(rc2, "3.txt"), "3 - three");
        }
    }

    @Test
    public void testMergedDir() throws Exception
    {
        try (Resource.Mount rcMount = Resource.mountCollection(Resource.fromList(List.of(
                "src/test/resources/org/eclipse/jetty/util/resource/one/",
                "src/test/resources/org/eclipse/jetty/util/resource/two/",
                "src/test/resources/org/eclipse/jetty/util/resource/three/"),
            false)))
        {
            ResourceCollection rc = (ResourceCollection)rcMount.root();
            Resource r = rc.resolve("dir");
            assertTrue(r instanceof ResourceCollection);
            rc = (ResourceCollection)r;
            assertEquals(getContent(rc, "1.txt"), "1 - one");
            assertEquals(getContent(rc, "2.txt"), "2 - two");
            assertEquals(getContent(rc, "3.txt"), "3 - three");
        }
    }

    @Test
    public void testCopyTo() throws Exception
    {
        try (Resource.Mount rcMount = Resource.mountCollection(Resource.fromList(List.of(
                "src/test/resources/org/eclipse/jetty/util/resource/one/",
                "src/test/resources/org/eclipse/jetty/util/resource/two/",
                "src/test/resources/org/eclipse/jetty/util/resource/three/"),
            false)))
        {
            ResourceCollection rc = (ResourceCollection)rcMount.root();
            Path destDir = workdir.getEmptyPathDir();
            rc.copyTo(destDir);

            Resource r = Resource.newResource(destDir);
            assertEquals(getContent(r, "1.txt"), "1 - one");
            assertEquals(getContent(r, "2.txt"), "2 - two");
            assertEquals(getContent(r, "3.txt"), "3 - three");
            r = r.resolve("dir");
            assertEquals(getContent(r, "1.txt"), "1 - one");
            assertEquals(getContent(r, "2.txt"), "2 - two");
            assertEquals(getContent(r, "3.txt"), "3 - three");
        }
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
