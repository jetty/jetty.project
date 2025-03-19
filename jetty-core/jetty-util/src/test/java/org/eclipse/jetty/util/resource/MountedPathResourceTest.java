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

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.zip.ZipFile;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.URIUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

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
public class MountedPathResourceTest
{

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
    public void testClassLoaderResourceIsNotAnAlias() throws Exception
    {
        Path testZip = MavenPaths.findTestResourceFile("jar-file-resource.jar");
        ClassLoader loader = new URLClassLoader(new URL[] {testZip.toUri().toURL()});
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource r = resourceFactory.newClassLoaderResource("rez/deep/zzz", false);
            assertThat("file inside a JAR should NOT be an alias", r.isAlias(), is(false));
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(oldLoader);
        }
    }

    @Test
    public void testNewResourceByUrlHasCorrectUri() throws Exception
    {
        Path testZip = MavenPaths.findTestResourceFile("jar-file-resource.jar");
        URL url = testZip.toUri().toURL();
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource r = resourceFactory.newResource(url);
            URI uri = r.getURI();
            assertThat(uri.toASCIIString(), is(URIUtil.correctURI(uri).toASCIIString()));
        }
    }

    @Test
    public void testJarFile(WorkDir workDir)
        throws Exception
    {
        Path testZip = MavenTestingUtils.getTestResourcePathFile("TestData/test.zip");
        String s = "jar:" + testZip.toUri().toASCIIString() + "!/subdir/";
        URI uri = URI.create(s);
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource r = resourceFactory.newResource(uri);

            List<String> entries = r.list().stream().map(Resource::getFileName).toList();
            assertThat(entries, containsInAnyOrder("alphabet", "numbers", "subsubdir"));

            Resource file = r.resolve("subsubdir/numbers");
            assertTrue(Resources.isReadableFile(file));
            assertThat(file.getFileName(), is("numbers"));

            Path extract = workDir.getEmptyPathDir().resolve("extract");
            FS.ensureEmpty(extract);

            r.copyTo(extract);

            Resource e = resourceFactory.newResource(extract.toString());

            entries = r.list().stream().map(Resource::getFileName).toList();
            assertThat(entries, containsInAnyOrder("alphabet", "numbers", "subsubdir"));

            s = "jar:" + testZip.toUri().toASCIIString() + "!/subdir/subsubdir/";
            r = resourceFactory.newResource(s);

            entries = r.list().stream().map(Resource::getFileName).toList();
            assertThat(entries, containsInAnyOrder("alphabet", "numbers"));

            Path extract2 = workDir.getEmptyPathDir().resolve("extract2");
            FS.ensureEmpty(extract2);

            r.copyTo(extract2);

            e = resourceFactory.newResource(extract2.toString());

            entries = r.list().stream().map(Resource::getFileName).toList();
            assertThat(entries, containsInAnyOrder("alphabet", "numbers"));
        }
    }

    @Test
    public void testZipFileName()
    {
        Path testZip = MavenTestingUtils.getTestResourcePathFile("TestData/test.zip");
        URI uri = testZip.toUri();
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource r = resourceFactory.newJarFileResource(uri);
            assertThat(r.getFileName(), is(""));

            r = r.resolve("subdir/numbers");
            assertTrue(Resources.isReadableFile(r));
            assertThat(r.getFileName(), is("numbers"));
        }
    }

    @Test
    public void testJarFileName()
    {
        Path testZip = MavenPaths.findTestResourceFile("jar-file-resource.jar");
        URI uri = testZip.toUri();
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource r = resourceFactory.newJarFileResource(uri);
            assertThat(r.getFileName(), is(""));

            r = r.resolve("rez/deep/zzz");
            assertTrue(Resources.isReadableFile(r));
            assertThat(r.getFileName(), is("zzz"));
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
    public void testJarFileDeleted(WorkDir workDir) throws Exception
    {
        Path originalTestZip = MavenTestingUtils.getTestResourcePathFile("TestData/test.zip");
        Path testZip = Files.copy(originalTestZip, workDir.getEmptyPathDir().resolve("test.zip"));
        String s = "jar:" + testZip.toUri().toASCIIString() + "!/subdir/";
        URI uri = URI.create(s);
        Resource resource;
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            resource = resourceFactory.newResource(uri);
            assertTrue(resource.exists());
            Files.delete(testZip);
            assertThrows(FileSystemNotFoundException.class, () -> resource.resolve("alphabet"));
        }
        assertThrows(ClosedFileSystemException.class, resource::exists);
    }

    @Test
    public void testDumpAndSweep(WorkDir workDir) throws Exception
    {
        Path originalTestZip = MavenTestingUtils.getTestResourcePathFile("TestData/test.zip");
        Path testZip = Files.copy(originalTestZip, workDir.getEmptyPathDir().resolve("test.zip"));
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
            assertThat(dump, containsString(testZip + "#1"));

            Files.delete(testZip);
            FileSystemPool.INSTANCE.sweep();

            dump = FileSystemPool.INSTANCE.dump();
            assertThat(dump, containsString("FileSystemPool"));
            assertThat(dump, containsString("buckets size=0"));

            assertThrows(ClosedFileSystemException.class, resource::exists);
        }
    }

    @Test
    public void testJarFileGetAllResources()
        throws Exception
    {
        Path testZip = MavenTestingUtils.getTestResourcePathFile("TestData/test.zip");
        String s = "jar:" + testZip.toUri().toASCIIString() + "!/subdir/";
        URI uri = URI.create(s);
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource r = resourceFactory.newResource(uri);
            Collection<Resource> deep = r.getAllResources();

            assertThat(deep.stream().map(r::getPathTo).map(Path::toString).toList(),
                containsInAnyOrder(
                    "numbers",
                    "subsubdir",
                    "subsubdir/numbers",
                    "subsubdir/alphabet",
                    "alphabet"
                ));
        }
    }

    @Test
    public void testJarFileIsContainedIn()
        throws Exception
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
            assertEquals(last, r.lastModified().toEpochMilli());
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
        URI uri = URI.create("jar:" + testJar.toUri().toASCIIString() + "!/");
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newResource(uri);
            Resource rez = resource.resolve("rez/");

            assertThat("path /rez/ is a dir", rez.isDirectory(), is(true));

            List<String> actual = rez.list().stream().map(Resource::getFileName).toList();
            String[] expected = new String[]{
                "one",
                "aaa",
                "bbb",
                "oddities",
                "another dir",
                "ccc",
                "deep",
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

            List<String> actual = rez.list().stream().map(Resource::getFileName).toList();
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

    /**
     * Test resolving a resource that has a backslash
     */
    @Test
    @Disabled("Test disabled due to JDK bug JDK-8311079")
    public void testJarFileResourceAccessBackSlash() throws Exception
    {
        Path testJar = MavenTestingUtils.getTestResourcePathFile("jar-file-resource.jar");
        URI uri = URI.create("jar:" + testJar.toUri().toASCIIString() + "!/");
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newResource(uri);
            Resource oddities = resource.resolve("rez/oddities/");

            assertThat("path /rez/oddities/ is a dir", oddities.isDirectory(), is(true));

            // attempt to access a resource with a backslash
            Resource someSlash = oddities.resolve("some\\slash\\you\\got\\there");
            assertTrue(Resources.isReadableFile(someSlash), "someSlash is accessible as a file");
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

            List<String> actual = anotherDir.list().stream().map(Resource::getFileName).toList();
            String[] expected = new String[]{
                "a file.txt",
                "another file.txt",
                "..\\a different file.txt",
                };
            assertThat("Dir contents", actual, containsInAnyOrder(expected));
        }
    }

    /**
     * When mounting multiple points within the same JAR, only
     * 1 mount should be created, but have reference counts
     * tracked separately.  Done via {@link ResourceFactory.Closeable}
     * essentially a duplicate of {@link #testMountByJarNameLifeCycle()}
     * to ensure parity in the two implementations.
     */
    @Test
    public void testMountByJarNameClosable()
    {
        Path jarPath = MavenPaths.findTestResourceFile("jar-file-resource.jar");
        URI uriRoot = URI.create("jar:" + jarPath.toUri().toASCIIString() + "!/"); // root
        URI uriRez = URI.create("jar:" + jarPath.toUri().toASCIIString() + "!/rez/"); // dir
        URI uriDeep = URI.create("jar:" + jarPath.toUri().toASCIIString() + "!/rez/deep/"); // dir
        URI uriZzz = URI.create("jar:" + jarPath.toUri().toASCIIString() + "!/rez/deep/zzz"); // file

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resRoot = resourceFactory.newResource(uriRoot);
            Resource resRez = resourceFactory.newResource(uriRez);
            Resource resDeep = resourceFactory.newResource(uriDeep);
            Resource resZzz = resourceFactory.newResource(uriZzz);

            assertThat(FileSystemPool.INSTANCE.mounts().size(), is(1));
            int mountCount = FileSystemPool.INSTANCE.getReferenceCount(uriRoot);
            assertThat(mountCount, is(1));
        }

        assertThat(FileSystemPool.INSTANCE.mounts().size(), is(0));
        int mountCount = FileSystemPool.INSTANCE.getReferenceCount(uriRoot);
        assertThat(mountCount, is(0));
    }

    /**
     * When mounting multiple points within the same JAR, only
     * 1 mount should be created, but have reference counts
     * tracked separately.  Done via {@link ResourceFactory.LifeCycle}
     * essentially a duplicate of {@link #testMountByJarNameClosable()}
     * to ensure parity in the two implementations.
     */
    @Test
    public void testMountByJarNameLifeCycle() throws Exception
    {
        Path jarPath = MavenPaths.findTestResourceFile("jar-file-resource.jar");
        URI uriRoot = URI.create("jar:" + jarPath.toUri().toASCIIString() + "!/"); // root
        URI uriRez = URI.create("jar:" + jarPath.toUri().toASCIIString() + "!/rez/"); // dir
        URI uriDeep = URI.create("jar:" + jarPath.toUri().toASCIIString() + "!/rez/deep/"); // dir
        URI uriZzz = URI.create("jar:" + jarPath.toUri().toASCIIString() + "!/rez/deep/zzz"); // file

        ResourceFactory.LifeCycle resourceFactory = ResourceFactory.lifecycle();

        try
        {
            resourceFactory.start();
            Resource resRoot = resourceFactory.newResource(uriRoot);
            Resource resRez = resourceFactory.newResource(uriRez);
            Resource resDeep = resourceFactory.newResource(uriDeep);
            Resource resZzz = resourceFactory.newResource(uriZzz);

            assertThat(FileSystemPool.INSTANCE.mounts().size(), is(1));
            int mountCount = FileSystemPool.INSTANCE.getReferenceCount(uriRoot);
            assertThat(mountCount, is(1));
            String dump = resourceFactory.dump();
            assertThat(dump, containsString("newResourceReferences size=1"));
            assertThat(dump, containsString(uriRoot.toASCIIString()));
        }
        finally
        {
            resourceFactory.stop();

            assertThat(FileSystemPool.INSTANCE.mounts().size(), is(0));
            int mountCount = FileSystemPool.INSTANCE.getReferenceCount(uriRoot);
            assertThat(mountCount, is(0));
        }
    }
}
