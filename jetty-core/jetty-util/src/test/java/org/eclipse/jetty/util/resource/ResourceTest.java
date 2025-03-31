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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ExtendWith(WorkDirExtension.class)
@Isolated
public class ResourceTest
{
    private static final boolean DIR = true;
    private static final boolean EXISTS = true;
    private static final ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable();

    public WorkDir workDir;

    @AfterAll
    public static void afterAll()
    {
        resourceFactory.close();
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    static class Scenario
    {
        private Supplier<Resource> resourceSupplier;
        String test;
        boolean exists;
        boolean dir;
        String content;

        Scenario(Scenario data, String path, boolean exists, boolean dir)
        {
            this.test = data.getResource() + "+" + path;
            this.resourceSupplier = () -> data.getResource().resolve(path);
            this.exists = exists;
            this.dir = dir;
        }

        Scenario(Scenario data, String path, boolean exists, boolean dir, String content)
        {
            this.test = data.getResource() + "+" + path;
            this.resourceSupplier = () -> data.getResource().resolve(path);
            this.exists = exists;
            this.dir = dir;
            this.content = content;
        }

        Scenario(URL url, boolean exists, boolean dir)
        {
            this.test = url.toString();
            this.exists = exists;
            this.dir = dir;
            this.resourceSupplier = () -> resourceFactory.newResource(url);
        }

        Scenario(String url, boolean exists, boolean dir)
        {
            this.test = url;
            this.exists = exists;
            this.dir = dir;
            this.resourceSupplier = () -> resourceFactory.newResource(url);
        }

        Scenario(URI uri, boolean exists, boolean dir)
        {
            this.test = uri.toASCIIString();
            this.exists = exists;
            this.dir = dir;
            this.resourceSupplier = () -> resourceFactory.newResource(uri);
        }

        Scenario(Path file, boolean exists, boolean dir)
        {
            this.test = file.toString();
            this.exists = exists;
            this.dir = dir;
            this.resourceSupplier = () -> resourceFactory.newResource(file);
        }

        public Resource getResource()
        {
            return resourceSupplier.get();
        }

        @Override
        public String toString()
        {
            return this.test;
        }
    }

    static class Scenarios extends ArrayList<Arguments>
    {
        final Path fileRef;
        final URI uriRef;
        final String relRef;

        final Scenario[] baseCases;

        public Scenarios(String ref)
        {
            // relative directory reference
            this.relRef = FS.separators(ref);
            // File object reference
            this.fileRef = MavenPaths.projectBase().resolve(relRef);
            // URI reference
            this.uriRef = fileRef.toUri();

            // create baseline cases
            baseCases = new Scenario[]{
                new Scenario(relRef, EXISTS, DIR),
                new Scenario(uriRef, EXISTS, DIR),
                new Scenario(fileRef, EXISTS, DIR)
            };

            // add all baseline cases
            for (Scenario bcase : baseCases)
            {
                addCase(bcase);
            }
        }

        public void addCase(Scenario ucase)
        {
            add(Arguments.of(ucase));
        }

        public void addAllSimpleCases(String subpath, boolean exists, boolean dir)
            throws Exception
        {
            addCase(new Scenario(FS.separators(relRef + subpath), exists, dir));
            addCase(new Scenario(uriRef.resolve(subpath).toURL(), exists, dir));
            addCase(new Scenario(fileRef.resolve(subpath), exists, dir));
        }

        public Scenario addAllAddPathCases(String subpath, boolean exists, boolean dir)
        {
            Scenario bdata = null;

            for (Scenario bcase : baseCases)
            {
                bdata = new Scenario(bcase, subpath, exists, dir);
                addCase(bdata);
            }

            return bdata;
        }
    }

    public static Stream<Arguments> scenarios(WorkDir workDir) throws Exception
    {
        Scenarios cases = new Scenarios("src/test/resources/");

        Path testDir = workDir.getEmptyPathDir();
        FS.ensureEmpty(testDir);
        Path tmpFile = testDir.resolve("test.tmp");
        FS.touch(tmpFile);

        cases.addCase(new Scenario(tmpFile.toString(), EXISTS, !DIR));

        // Some resource references.
        cases.addAllSimpleCases("resource.txt", EXISTS, !DIR);
        cases.addAllSimpleCases("NoName.txt", !EXISTS, !DIR);

        // Some addPath() forms
        cases.addAllAddPathCases("resource.txt", EXISTS, !DIR);
        cases.addAllAddPathCases("/resource.txt", EXISTS, !DIR);
        cases.addAllAddPathCases("//resource.txt", EXISTS, !DIR);
        cases.addAllAddPathCases("NoName.txt", !EXISTS, !DIR);
        cases.addAllAddPathCases("/NoName.txt", !EXISTS, !DIR);
        cases.addAllAddPathCases("//NoName.txt", !EXISTS, !DIR);

        Scenario tdata1 = cases.addAllAddPathCases("TestData", EXISTS, DIR);
        Scenario tdata2 = cases.addAllAddPathCases("TestData/", EXISTS, DIR);

        cases.addCase(new Scenario(tdata1, "alphabet.txt", EXISTS, !DIR, "ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        cases.addCase(new Scenario(tdata2, "alphabet.txt", EXISTS, !DIR, "ABCDEFGHIJKLMNOPQRSTUVWXYZ"));

        String urlRef = cases.uriRef.toASCIIString();
        resourceFactory.newResource(URI.create("jar:" + urlRef + "TestData/test.zip!/"));
        Scenario zdata = new Scenario("jar:" + urlRef + "TestData/test.zip!/", EXISTS, DIR);
        cases.addCase(zdata);

        cases.addCase(new Scenario(zdata, "Unknown", !EXISTS, !DIR));
        cases.addCase(new Scenario(zdata, "/Unknown/", !EXISTS, !DIR));

        cases.addCase(new Scenario(zdata, "subdir", EXISTS, DIR));
        cases.addCase(new Scenario(zdata, "/subdir/", EXISTS, DIR));
        cases.addCase(new Scenario(zdata, "alphabet", EXISTS, !DIR,
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        cases.addCase(new Scenario(zdata, "/subdir/alphabet", EXISTS, !DIR,
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ"));

        cases.addAllAddPathCases("/TestData/test/subdir/subsubdir/", EXISTS, DIR);
        cases.addAllAddPathCases("//TestData/test/subdir/subsubdir/", EXISTS, DIR);
        cases.addAllAddPathCases("/TestData//test/subdir/subsubdir/", EXISTS, DIR);
        cases.addAllAddPathCases("/TestData/test//subdir/subsubdir/", EXISTS, DIR);
        cases.addAllAddPathCases("/TestData/test/subdir//subsubdir/", EXISTS, DIR);

        cases.addAllAddPathCases("TestData/test/subdir/subsubdir/", EXISTS, DIR);
        cases.addAllAddPathCases("TestData/test/subdir/subsubdir//", EXISTS, DIR);
        cases.addAllAddPathCases("TestData/test/subdir//subsubdir/", EXISTS, DIR);
        cases.addAllAddPathCases("TestData/test//subdir/subsubdir/", EXISTS, DIR);

        cases.addAllAddPathCases("/TestData/../TestData/test/subdir/subsubdir/", EXISTS, DIR);

        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResourceExists(Scenario data)
    {
        Resource res = data.getResource();
        if (data.exists)
            assertThat("Exists: " + res.getName(), res.exists(), equalTo(data.exists));
        else
            assertFalse(res.exists());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResourceDir(Scenario data)
    {
        Resource res = data.getResource();
        assumeTrue(res != null);
        assertThat("Is Directory: " + data.test, res.isDirectory(), equalTo(data.dir));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResourceContent(Scenario data)
        throws Exception
    {
        Assumptions.assumeTrue(data.content != null);

        InputStream in = data.getResource().newInputStream();
        String c = IO.toString(in);
        assertThat("Content: " + data.test, c, startsWith(data.content));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResourceCopyToDirectory(Scenario data)
        throws Exception
    {
        Resource resource = data.getResource();
        Assumptions.assumeTrue(resource != null);

        Path targetDir = workDir.getEmptyPathDir();
        if (Resources.exists(resource))
        {
            resource.copyTo(targetDir);
            Path targetToTest = resource.isDirectory() ? targetDir : targetDir.resolve(resource.getFileName());
            assertResourceSameAsPath(resource, targetToTest);
        }
        else
        {
            assertThrows(IOException.class, () -> resource.copyTo(targetDir));
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResourceCopyToFile(Scenario data)
        throws Exception
    {
        Resource resource = data.getResource();
        Assumptions.assumeTrue(resource != null);
        Assumptions.assumeFalse(resource.isDirectory());

        String filename = resource.getFileName();
        Path targetDir = workDir.getEmptyPathDir();
        Path targetFile = targetDir.resolve(filename);
        if (Resources.exists(resource))
        {
            resource.copyTo(targetFile);
            assertResourceSameAsPath(resource, targetFile);
        }
        else
        {
            assertThrows(IOException.class, () -> resource.copyTo(targetFile));
        }
    }

    @Test
    public void testNonExistentResource() throws IOException
    {
        Path nonExistentFile = workDir.getPathFile("does-not-exists");
        Resource resource = resourceFactory.newResource(nonExistentFile);
        assertFalse(resource.exists());
        assertThrows(IOException.class, () -> resource.copyTo(workDir.getEmptyPathDir()));
        assertTrue(resource.list().isEmpty());
        assertFalse(resource.contains(resourceFactory.newResource(workDir.getPath())));
        assertEquals("does-not-exists", resource.getFileName());
        assertFalse(resource.isReadable());
        assertEquals(nonExistentFile, resource.getPath());
        assertEquals(Instant.EPOCH, resource.lastModified());
        assertEquals(-1L, resource.length());
        assertThrows(IOException.class, resource::newInputStream);
        assertThrows(IOException.class, resource::newReadableByteChannel);
        assertEquals(nonExistentFile.toUri(), resource.getURI());
        assertFalse(resource.isAlias());
        assertNull(resource.getRealURI());
        assertNotNull(resource.getName());
        Resource subResource = resource.resolve("does-not-exist-too");
        assertFalse(subResource.exists());
        assertEquals(nonExistentFile.resolve("does-not-exist-too"), subResource.getPath());
    }

    @Test
    public void testGlobPath()
    {
        Path testDir = MavenTestingUtils.getTargetTestingPath("testGlobPath");
        FS.ensureEmpty(testDir);

        try
        {
            Path globFile = testDir.resolve("*");
            Files.createFile(globFile);
            assumeTrue(Files.exists(globFile)); // skip test if file wasn't created
            Resource globResource = resourceFactory.newResource(globFile.toAbsolutePath().toString());
            assertNotNull(globResource, "Should have produced a Resource");
        }
        catch (InvalidPathException | IOException e)
        {
            // if unable to reference the glob file, no point testing the rest.
            // this is the path that Microsoft Windows takes.
            assumeTrue(false, "Glob not supported on this OS");
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void testEqualsWindowsAltUriSyntax() throws Exception
    {
        URI a = new URI("file:/C:/foo/bar");
        URI b = new URI("file:///C:/foo/bar");

        Resource ra = resourceFactory.newResource(a);
        Resource rb = resourceFactory.newResource(b);

        assertEquals(rb, ra);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @Disabled("This will create different Resource objects, not sure if this is still results in a problem")
    public void testEqualsWindowsCaseInsensitiveDrive() throws Exception
    {
        URI a = new URI("file:///c:/foo/bar");
        URI b = new URI("file:///C:/foo/bar");
        
        Resource ra = resourceFactory.newResource(a);
        Resource rb = resourceFactory.newResource(b);

        assertEquals(rb, ra);
    }

    @Test
    public void testResourceExtraSlashStripping(WorkDir workDir) throws IOException
    {
        Path docRoot = workDir.getEmptyPathDir();

        Files.createDirectories(docRoot.resolve("d/e/f"));

        Resource ra = resourceFactory.newResource(docRoot);
        Resource rb = ra.resolve("///");
        Resource rc = ra.resolve("///d/e///f");

        assertEquals(ra, rb);
        assertEquals(rc.getURI().getPath(), docRoot.toUri().getPath() + "d/e/f/");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void testWindowsResourceFromString()
    {
        // Check strings that look like URIs but actually are paths.
        Resource ra = resourceFactory.newResource("C:\\foo\\bar");
        Resource rb = resourceFactory.newResource("C:/foo/bar");
        Resource rc = resourceFactory.newResource("C:///foo/bar");

        assertEquals(rb, ra);
        assertEquals(rb, rc);
    }

    @Test
    public void testClimbAboveBase(WorkDir workDir)
    {
        Path testdir = workDir.getEmptyPathDir();
        FS.ensureDirExists(testdir);
        Resource resource = resourceFactory.newResource(testdir);
        assertThrows(IllegalArgumentException.class, () -> resource.resolve(".."));
        assertThrows(IllegalArgumentException.class, () -> resource.resolve("./.."));
        assertThrows(IllegalArgumentException.class, () -> resource.resolve("./../bar"));
    }

    @Test
    public void testResolveStartsWithSlash(WorkDir workDir)
    {
        Path testdir = workDir.getEmptyPathDir();
        Path foo = testdir.resolve("foo");
        FS.ensureDirExists(foo);
        Resource resourceBase = resourceFactory.newResource(testdir);
        // test that a resolve starting with `/` works.
        Resource resourceDir = resourceBase.resolve("/foo");
        assertTrue(Resources.exists(resourceDir));
        assertThat(resourceDir.getURI(), is(foo.toUri()));
        assertTrue(resourceBase.contains(resourceDir));
        assertThat(resourceBase.getPathTo(resourceDir).getName(0).toString(), is("foo"));
    }

    @Test
    public void testNewResourcePathDoesNotExist(WorkDir workDir)
    {
        Path dir = workDir.getEmptyPathDir().resolve("foo/bar");
        // at this point we have a directory reference that does not exist
        Resource resource = resourceFactory.newResource(dir);
        assertFalse(resource.exists());
    }

    @Test
    public void testNewResourceFileDoesNotExists(WorkDir workDir) throws IOException
    {
        Path dir = workDir.getEmptyPathDir().resolve("foo");
        FS.ensureDirExists(dir);
        Path file = dir.resolve("bar.txt");
        // at this point we have a file reference that does not exist
        assertFalse(Files.exists(file));
        Resource resource = resourceFactory.newResource(file);
        assertFalse(resource.exists());
    }

    @Test
    public void testDotAliasDirExists(WorkDir workDir) throws IOException
    {
        Path dir = workDir.getEmptyPathDir().resolve("foo/bar");
        FS.ensureDirExists(dir);
        Resource resource = resourceFactory.newResource(dir);
        Resource dot = resource.resolve(".");
        assertNotNull(dot);
        assertTrue(dot.exists());
        assertTrue(dot.isAlias(), "Reference to '.' is an alias to itself");
        assertTrue(Files.isSameFile(dot.getPath(), Paths.get(dot.getRealURI())));
    }

    @Test
    public void testDotAliasFileExists(WorkDir workDir) throws IOException
    {
        Path dir = workDir.getEmptyPathDir().resolve("foo");
        FS.ensureDirExists(dir);
        Path file = dir.resolve("bar.txt");
        FS.touch(file);
        assertTrue(Files.exists(file));
        Resource resource = resourceFactory.newResource(file);
        // Requesting a resource that would point to a location called ".../testDotAliasFileExists/foo/bar.txt/."
        Resource dot = resource.resolve(".");
        if (OS.WINDOWS.isCurrentOs())
        {
            // windows allows this reference, but it's an alias.
            assertTrue(Resources.exists(dot), "Reference to directory via dot allowed");
            assertTrue(dot.isAlias(), "Reference to dot is an alias to actual bar.txt");
            assertEquals(dot.getRealURI(), file.toUri());
        }
        else
        {
            assertTrue(Resources.missing(dot), "Cannot reference file as a directory");
        }
    }

    @Test
    public void testJrtResourceModule()
    {
        Resource resource = ResourceFactory.root().newResource("jrt:/java.base");

        assertThat(resource.exists(), is(true));
        assertThat(resource.isDirectory(), is(true));
        assertThat(resource.length(), is(0L));
    }

    @Test
    public void testJrtResourceAllModules()
    {
        Resource resource = ResourceFactory.root().newResource("jrt:/");

        assertThat(resource.exists(), is(true));
        assertThat(resource.isDirectory(), is(true));
        assertThat(resource.length(), is(0L));
    }

    private static void assertResourceSameAsPath(Resource resource, Path copy) throws IOException
    {
        if (!resource.isDirectory())
        {
            assertFalse(Files.isDirectory(copy), "Resource is not dir (" + resource + "), copy is dir (" + copy + ")");
            try (InputStream sourceIs = resource.newInputStream();
                 InputStream targetIs = Files.newInputStream(copy))
            {
                String source = IO.toString(sourceIs);
                String target = IO.toString(targetIs);
                assertEquals(source, target, "Resource (" + resource + ") and copy (" + copy + ") contents do not match");
            }
        }
        else
        {
            assertTrue(Files.isDirectory(copy), "Resource is dir (" + resource + "), copy is not dir (" + copy + ")");
            List<Resource> subResources = resource.list();
            for (Resource subResource : subResources)
            {
                assertResourceSameAsPath(subResource, copy.resolve(subResource.getFileName()));
            }
        }
    }
}
