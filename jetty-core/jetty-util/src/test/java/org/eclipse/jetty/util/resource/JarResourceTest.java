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

import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class JarResourceTest
{
    public WorkDir workDir;

    @Test
    public void testJarFile()
        throws Exception
    {
        Path testZip = MavenTestingUtils.getTestResourcePathFile("TestData/test.zip");
        String s = "jar:" + testZip.toUri().toASCIIString() + "!/subdir/";
        Resource r = Resource.newResource(s);

        Set<String> entries = new HashSet<>(Arrays.asList(r.list()));
        assertThat(entries, containsInAnyOrder("alphabet", "numbers", "subsubdir/"));

        Path extract = workDir.getPathFile("extract");
        FS.ensureEmpty(extract);

        r.copyTo(extract.toFile());

        Resource e = Resource.newResource(extract.toString());

        entries = new HashSet<>(Arrays.asList(e.list()));
        assertThat(entries, containsInAnyOrder("alphabet", "numbers", "subsubdir/"));

        s = "jar:" + testZip.toUri().toASCIIString() + "!/subdir/subsubdir/";
        r = Resource.newResource(s);

        entries = new HashSet<>(Arrays.asList(r.list()));
        assertThat(entries, containsInAnyOrder("alphabet", "numbers"));

        Path extract2 = workDir.getPathFile("extract2");
        FS.ensureEmpty(extract2);

        r.copyTo(extract2.toFile());

        e = Resource.newResource(extract2.toString());

        entries = new HashSet<>(Arrays.asList(e.list()));
        assertThat(entries, containsInAnyOrder("alphabet", "numbers"));
    }

    @Test
    public void testJarFileGetAllResoures()
        throws Exception
    {
        Path testZip = MavenTestingUtils.getTestResourcePathFile("TestData/test.zip");
        String s = "jar:" + testZip.toUri().toASCIIString() + "!/subdir/";
        Resource r = Resource.newResource(s);
        Collection<Resource> deep = r.getAllResources();

        assertEquals(4, deep.size());
    }

    @Test
    public void testJarFileLastModified()
        throws Exception
    {
        Path testZip = MavenTestingUtils.getTestResourcePathFile("TestData/test.zip");

        String s = "jar:" + testZip.toUri().toASCIIString() + "!/subdir/numbers";

        try (ZipFile zf = new ZipFile(testZip.toFile()))
        {
            long last = zf.getEntry("subdir/numbers").getTime();

            Resource r = Resource.newResource(s);
            assertEquals(last, r.lastModified());
        }
    }

    @Test
    public void testJarFileCopyToDirectoryTraversal() throws Exception
    {
        Path extractZip = MavenTestingUtils.getTestResourcePathFile("TestData/extract.zip");

        String s = "jar:" + extractZip.toUri().toASCIIString() + "!/";
        Resource r = Resource.newResource(s);

        assertThat(r, instanceOf(JarResource.class));
        JarResource jarResource = (JarResource)r;

        Path destParent = workDir.getPathFile("copyjar");
        FS.ensureEmpty(destParent);

        Path dest = destParent.toRealPath().resolve("extract");
        FS.ensureEmpty(dest);

        jarResource.copyTo(dest.toFile());

        // dest contains only the valid entry; dest.getParent() contains only the dest directory
        assertEquals(1, listFiles(dest).size());
        assertEquals(1, listFiles(dest.getParent()).size());

        DirectoryStream.Filter<? super Path> dotdotFilenameFilter = (path) ->
            path.getFileName().toString().equalsIgnoreCase("dotdot.dot");

        assertEquals(0, listFiles(dest, dotdotFilenameFilter).size());
        assertEquals(0, listFiles(dest.getParent(), dotdotFilenameFilter).size());

        DirectoryStream.Filter<? super Path> extractfileFilenameFilter = (path) ->
            path.getFileName().toString().equalsIgnoreCase("extract-filenotdir");

        assertEquals(0, listFiles(dest, extractfileFilenameFilter).size());
        assertEquals(0, listFiles(dest.getParent(), extractfileFilenameFilter).size());

        DirectoryStream.Filter<? super Path> currentDirectoryFilenameFilter = (path) ->
            path.getFileName().toString().equalsIgnoreCase("current.txt");

        assertEquals(1, listFiles(dest, currentDirectoryFilenameFilter).size());
        assertEquals(0, listFiles(dest.getParent(), currentDirectoryFilenameFilter).size());
    }

    @Test
    public void testEncodedFileName()
        throws Exception
    {
        Path testZip = MavenTestingUtils.getTestResourcePathFile("TestData/test.zip");

        String s = "jar:" + testZip.toUri().toASCIIString() + "!/file%20name.txt";
        Resource r = Resource.newResource(s);
        assertTrue(r.exists());
    }

    @Test
    public void testJarFileResourceList() throws Exception
    {
        Path testJar = MavenTestingUtils.getTestResourcePathFile("jar-file-resource.jar");
        String uri = "jar:" + testJar.toUri().toASCIIString() + "!/";

        Resource resource = new JarFileResource(URI.create(uri).toURL(), false);
        Resource rez = resource.addPath("rez/");

        assertThat("path /rez/ is a dir", rez.isDirectory(), is(true));

        List<String> actual = Arrays.asList(rez.list());
        String[] expected = new String[]{
            "one",
            "aaa",
            "bbb",
            "oddities/",
            "another dir/",
            "ccc",
            "deep/",
            };
        assertThat("Dir contents", actual, containsInAnyOrder(expected));
    }

    /**
     * Test getting a file listing of a Directory in a JAR
     * Where the JAR entries contain names that are URI encoded / escaped
     */
    @Test
    public void testJarFileResourceListPreEncodedEntries() throws Exception
    {
        Path testJar = MavenTestingUtils.getTestResourcePathFile("jar-file-resource.jar");
        String uri = "jar:" + testJar.toUri().toASCIIString() + "!/";

        Resource resource = new JarFileResource(URI.create(uri).toURL(), false);
        Resource rez = resource.addPath("rez/oddities/");

        assertThat("path /rez/oddities/ is a dir", rez.isDirectory(), is(true));

        List<String> actual = Arrays.asList(rez.list());
        String[] expected = new String[]{
            ";",
            "#hashcode",
            "index.html#fragment",
            "other%2fkind%2Fof%2fslash", // pre-encoded / escaped
            "a file with a space",
            ";\" onmousedown=\"alert(document.location)\"",
            "some\\slash\\you\\got\\there" // not encoded, stored as backslash native
        };
        assertThat("Dir contents", actual, containsInAnyOrder(expected));
    }

    @Test
    public void testJarFileResourceListDirWithSpace() throws Exception
    {
        Path testJar = MavenTestingUtils.getTestResourcePathFile("jar-file-resource.jar");
        String uri = "jar:" + testJar.toUri().toASCIIString() + "!/";

        Resource resource = new JarFileResource(URI.create(uri).toURL(), false);
        Resource anotherDir = resource.addPath("rez/another dir/");

        assertThat("path /rez/another dir/ is a dir", anotherDir.isDirectory(), is(true));

        List<String> actual = Arrays.asList(anotherDir.list());
        String[] expected = new String[]{
            "a file.txt",
            "another file.txt",
            "..\\a different file.txt",
            };
        assertThat("Dir contents", actual, containsInAnyOrder(expected));
    }

    private List<Path> listFiles(Path dir) throws IOException
    {
        try (Stream<Path> s = Files.list(dir))
        {
            return s.collect(Collectors.toList());
        }
    }

    private List<Path> listFiles(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException
    {
        List<Path> results = new ArrayList<>();
        try (DirectoryStream<Path> filteredDirStream = Files.newDirectoryStream(dir, filter))
        {
            for (Path path : filteredDirStream)
            {
                results.add(path);
            }
            return results;
        }
    }
}
