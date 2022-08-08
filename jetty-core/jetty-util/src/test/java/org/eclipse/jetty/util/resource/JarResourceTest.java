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

import java.net.URI;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.URIUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class JarResourceTest
{
    public WorkDir workDir;

    @BeforeEach
    public void beforeEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @AfterEach
    public void afterEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @Test
    public void testJarFile()
        throws Exception
    {
        Path testZip = MavenTestingUtils.getTestResourcePathFile("TestData/test.zip");
        String s = "jar:" + testZip.toUri().toASCIIString() + "!/subdir/";
        URI uri = URI.create(s);
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource r = resourceFactory.newResource(uri);

            List<String> entries = Files.list(r.getPath()).map(FileID::getClassifiedFileName).toList();
            assertThat(entries, containsInAnyOrder("alphabet", "numbers", "subsubdir/"));

            Path extract = workDir.getPathFile("extract");
            FS.ensureEmpty(extract);

            r.copyTo(extract);

            Resource e = resourceFactory.newResource(extract.toString());

            entries = Files.list(e.getPath()).map(FileID::getClassifiedFileName).toList();
            assertThat(entries, containsInAnyOrder("alphabet", "numbers", "subsubdir/"));

            s = "jar:" + testZip.toUri().toASCIIString() + "!/subdir/subsubdir/";
            r = resourceFactory.newResource(s);

            entries = Files.list(r.getPath()).map(FileID::getClassifiedFileName).toList();
            assertThat(entries, containsInAnyOrder("alphabet", "numbers"));

            Path extract2 = workDir.getPathFile("extract2");
            FS.ensureEmpty(extract2);

            r.copyTo(extract2);

            e = resourceFactory.newResource(extract2.toString());
            entries = Files.list(r.getPath()).map(FileID::getClassifiedFileName).toList();
            assertThat(entries, containsInAnyOrder("alphabet", "numbers"));
        }
    }

    @Test
    public void testJarFileUnMounted() throws Exception
    {
        Path testZip = MavenTestingUtils.getTestResourcePathFile("TestData/test.zip");
        String s = "jar:" + testZip.toUri().toASCIIString() + "!/subdir/";
        URI uri = URI.create(s);
        Resource resource;
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            resource = resourceFactory.newResource(uri);
            assertTrue(resource.exists());
        }
        assertThrows(ClosedFileSystemException.class, resource::exists);
    }

    @Test
    public void testJarFileDeleted(@TempDir Path tempDir) throws Exception
    {
        Path originalTestZip = MavenTestingUtils.getTestResourcePathFile("TestData/test.zip");
        Path testZip = Files.copy(originalTestZip, tempDir.resolve("test.zip"));
        String s = "jar:" + testZip.toUri().toASCIIString() + "!/subdir/";
        URI uri = URI.create(s);
        Resource resource;
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            resource = resourceFactory.newResource(uri);
            assertTrue(resource.exists());
            Files.delete(testZip);
            assertThrows(IllegalStateException.class, () -> resource.resolve("alphabet"));
        }
        assertThrows(ClosedFileSystemException.class, resource::exists);
    }

    @Test
    public void testDumpAndSweep(@TempDir Path tempDir) throws Exception
    {
        Path originalTestZip = MavenTestingUtils.getTestResourcePathFile("TestData/test.zip");
        Path testZip = Files.copy(originalTestZip, tempDir.resolve("test.zip"));
        String s = "jar:" + testZip.toUri().toASCIIString() + "!/subdir/";
        URI uri = URI.create(s);
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newResource(uri);
            assertTrue(resource.exists());

            String dump = FileSystemPool.INSTANCE.dump();
            System.out.println(dump);
            assertThat(dump, containsString("FileSystemPool"));
            assertThat(dump, containsString("buckets size=1"));
            assertThat(dump, containsString("/test.zip#1"));

            Files.delete(testZip);
            FileSystemPool.INSTANCE.sweep();

            dump = FileSystemPool.INSTANCE.dump();
            assertThat(dump, containsString("FileSystemPool"));
            assertThat(dump, containsString("buckets size=0"));

            assertThrows(ClosedFileSystemException.class, resource::exists);
        }
    }

    @Test
    public void testJarFileIsContainedIn()
    {
        Path testZip = MavenTestingUtils.getTestResourcePathFile("TestData/test.zip");
        URI uri = URI.create("jar:" + testZip.toUri().toASCIIString() + "!/subdir/");
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource r = resourceFactory.newResource(uri);
            Resource container = resourceFactory.newResource(testZip);

            assertThat(r, instanceOf(MountedPathResource.class));

            assertTrue(r.isContainedIn(container));

            container = resourceFactory.newResource(testZip.getParent());
            assertFalse(r.isContainedIn(container));
        }
    }

    @Test
    public void testJarFileLastModified()
        throws Exception
    {
        Path testZip = MavenTestingUtils.getTestResourcePathFile("TestData/test.zip");
        URI uri = URI.create("jar:" + testZip.toUri().toASCIIString() + "!/subdir/numbers");
        try (ZipFile zf = new ZipFile(testZip.toFile());
             ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            long last = zf.getEntry("subdir/numbers").getTime();

            Resource r = resourceFactory.newResource(uri);
            assertEquals(last, r.lastModified());
        }
    }

    @Test
    public void testEncodedFileName()
        throws Exception
    {
        Path testZip = MavenTestingUtils.getTestResourcePathFile("TestData/test.zip");
        URI uri = URI.create("jar:" + testZip.toUri().toASCIIString() + "!/file%20name.txt");
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource r = resourceFactory.newResource(uri);
            assertTrue(r.exists());
        }
    }

    @Test
    public void testJarFileResourceList() throws Exception
    {
        Path testJar = MavenTestingUtils.getTestResourcePathFile("jar-file-resource.jar");
        URI uri = URIUtil.toJarFileUri(testJar.toUri());
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newResource(uri);
            Resource rez = resource.resolve("rez/");

            assertThat("path /rez/ is a dir", rez.isDirectory(), is(true));

            List<String> actual;
            try (Stream<Path> listStream = Files.list(rez.getPath()))
            {
                actual = listStream
                    .map(FileID::getClassifiedFileName)
                    .sorted()
                    .toList();
            }

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
    }

    /**
     * Test getting a file listing of a Directory in a JAR
     * Where the JAR entries contain names that are URI encoded / escaped
     */
    @Test
    public void testJarFileResourceListPreEncodedEntries() throws Exception
    {
        Path testJar = MavenTestingUtils.getTestResourcePathFile("jar-file-resource.jar");
        URI uri = URI.create("jar:" + testJar.toUri().toASCIIString() + "!/");
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newResource(uri);
            Resource rez = resource.resolve("rez/oddities/");

            assertThat("path /rez/oddities/ is a dir", rez.isDirectory(), is(true));

            List<String> actual;
            try (Stream<Path> listStream = Files.list(rez.getPath()))
            {
                actual = listStream
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .sorted()
                    .toList();
            }
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
    }

    @Test
    public void testJarFileResourceListDirWithSpace() throws Exception
    {
        Path testJar = MavenTestingUtils.getTestResourcePathFile("jar-file-resource.jar");
        URI uri = URI.create("jar:" + testJar.toUri().toASCIIString() + "!/");
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newResource(uri);
            Resource anotherDir = resource.resolve("rez/another%20dir/");

            assertThat("path /rez/another dir/ is a dir", anotherDir.isDirectory(), is(true));

            List<String> actual;
            // Show list as raw filenames
            try (Stream<Path> listStream = Files.list(anotherDir.getPath()))
            {
                actual = listStream
                    .map(Path::getFileName)
                    .map(FileID::getClassifiedFileName)
                    .sorted()
                    .toList();
            }

            String[] expected = new String[]{
                "a file.txt",
                "another file.txt",
                "..\\a different file.txt",
                };
            assertThat("Dir contents", actual, containsInAnyOrder(expected));

            // Show list as URI encoded filenames
            try (Stream<Path> listStream = Files.list(anotherDir.getPath()))
            {
                actual = listStream
                    .map(Path::getFileName)
                    .map(FileID::getClassifiedFileName)
                    .map(URIUtil::encodePath)
                    .sorted()
                    .toList();
            }

            expected = new String[]{
                "a%20file.txt",
                "another%20file.txt",
                "..%5Ca%20different%20file.txt",
                };
            assertThat("Dir contents", actual, containsInAnyOrder(expected));
        }
    }
}
