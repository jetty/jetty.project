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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ExtendWith(WorkDirExtension.class)
public class ResourceTest
{
    private static final boolean DIR = true;
    private static final boolean EXISTS = true;
    private static final List<Closeable> TO_CLOSE = new ArrayList<>();

    static class Scenario
    {
        Resource resource;
        String test;
        boolean exists;
        boolean dir;
        String content;

        Scenario(Scenario data, String path, boolean exists, boolean dir)
            throws Exception
        {
            this.test = data.resource + "+" + path;
            resource = data.resource.resolve(path);
            this.exists = exists;
            this.dir = dir;
        }

        Scenario(Scenario data, String path, boolean exists, boolean dir, String content)
            throws Exception
        {
            this.test = data.resource + "+" + path;
            resource = data.resource.resolve(path);
            this.exists = exists;
            this.dir = dir;
            this.content = content;
        }

        Scenario(URL url, boolean exists, boolean dir)
            throws Exception
        {
            this.test = url.toString();
            this.exists = exists;
            this.dir = dir;
            resource = Resource.newResource(url);
        }

        Scenario(String url, boolean exists, boolean dir)
            throws Exception
        {
            this.test = url;
            this.exists = exists;
            this.dir = dir;
            resource = Resource.newResource(url);
        }

        Scenario(URI uri, boolean exists, boolean dir)
            throws Exception
        {
            this.test = uri.toASCIIString();
            this.exists = exists;
            this.dir = dir;
            resource = Resource.newResource(uri);
        }

        Scenario(File file, boolean exists, boolean dir)
        {
            this.test = file.toString();
            this.exists = exists;
            this.dir = dir;
            resource = Resource.newResource(file.toPath());
        }

        Scenario(String url, boolean exists, boolean dir, String content)
            throws Exception
        {
            this.test = url;
            this.exists = exists;
            this.dir = dir;
            this.content = content;
            resource = Resource.newResource(url);
        }

        @Override
        public String toString()
        {
            return this.test;
        }
    }

    static class Scenarios extends ArrayList<Arguments>
    {
        final File fileRef;
        final URI uriRef;
        final String relRef;

        final Scenario[] baseCases;

        public Scenarios(String ref) throws Exception
        {
            // relative directory reference
            this.relRef = FS.separators(ref);
            // File object reference
            this.fileRef = MavenTestingUtils.getProjectDir(relRef);
            // URI reference
            this.uriRef = fileRef.toURI();

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
            addCase(new Scenario(new File(fileRef, subpath), exists, dir));
        }

        public Scenario addAllAddPathCases(String subpath, boolean exists, boolean dir) throws Exception
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

    public static Stream<Arguments> scenarios() throws Exception
    {
        Scenarios cases = new Scenarios("src/test/resources/");

        File testDir = MavenTestingUtils.getTargetTestingDir(ResourceTest.class.getName());
        FS.ensureEmpty(testDir);
        File tmpFile = new File(testDir, "test.tmp");
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
        TO_CLOSE.add(Resource.mount(URI.create("jar:" + urlRef + "TestData/test.zip!/")));
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

    public WorkDir workDir;

    @AfterAll
    public static void tearDown()
    {
        TO_CLOSE.forEach(IO::close);
        TO_CLOSE.clear();
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResourceExists(Scenario data)
    {
        assertThat("Exists: " + data.resource.getName(), data.resource.exists(), equalTo(data.exists));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResourceDir(Scenario data)
    {
        assertThat("Is Directory: " + data.test, data.resource.isDirectory(), equalTo(data.dir));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testEncodeAddPath(Scenario data)
        throws Exception
    {
        if (data.dir)
        {
            Resource r = data.resource.resolve("foo%25/b%20r");
            assertThat(r.getPath().toString(), Matchers.anyOf(Matchers.endsWith("foo%/b r"), Matchers.endsWith("foo%\\b r")));
            assertThat(r.getURI().toString(), Matchers.endsWith("/foo%25/b%20r"));
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResourceContent(Scenario data)
        throws Exception
    {
        Assumptions.assumeTrue(data.content != null);

        InputStream in = data.resource.newInputStream();
        String c = IO.toString(in);
        assertThat("Content: " + data.test, c, startsWith(data.content));
    }

    @Test
    public void testGlobPath() throws IOException
    {
        Path testDir = MavenTestingUtils.getTargetTestingPath("testGlobPath");
        FS.ensureEmpty(testDir);

        try
        {
            String globReference = testDir.toAbsolutePath() + File.separator + '*';
            Resource globResource = Resource.newResource(globReference);
            assertNotNull(globResource, "Should have produced a Resource");
        }
        catch (InvalidPathException e)
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

        Resource ra = Resource.newResource(a);
        Resource rb = Resource.newResource(b);

        assertEquals(rb, ra);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void testEqualsWindowsCaseInsensitiveDrive() throws Exception
    {
        URI a = new URI("file:///c:/foo/bar");
        URI b = new URI("file:///C:/foo/bar");
        
        Resource ra = Resource.newResource(a);
        Resource rb = Resource.newResource(b);

        assertEquals(rb, ra);
    }

    @Test
    public void testResourceExtraSlashStripping() throws Exception
    {
        Resource ra = Resource.newResource("file:/a/b/c");
        Resource rb = ra.resolve("///");
        Resource rc = ra.resolve("///d/e///f");

        assertEquals(ra, rb);
        assertEquals(rc.getURI().getPath(), "/a/b/c/d/e/f");

        Resource rd = Resource.newResource("file:///a/b/c");
        Resource re = rd.resolve("///");
        Resource rf = rd.resolve("///d/e///f");

        assertEquals(rd, re);
        assertEquals(rf.getURI().getPath(), "/a/b/c/d/e/f");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void testWindowsResourceFromString() throws Exception
    {
        // Check strings that look like URIs but actually are paths.
        Resource ra = Resource.newResource("C:\\foo\\bar");
        Resource rb = Resource.newResource("C:/foo/bar");
        Resource rc = Resource.newResource("C:///foo/bar");

        assertEquals(rb, ra);
        assertEquals(rb, rc);
    }

    @Test
    public void testClimbAboveBase() throws Exception
    {
        Resource resource = Resource.newResource("/foo/bar");
        assertThrows(IOException.class, () -> resource.resolve(".."));

        assertThrows(IOException.class, () -> resource.resolve("./.."));

        assertThrows(IOException.class, () -> resource.resolve("./../bar"));
    }

    @Test
    @Disabled
    public void testDotAlias() throws Exception
    {
        Resource resource = Resource.newResource("/foo/bar");
        Resource same = resource.resolve(".");
        assertNotNull(same);
        assertTrue(same.isAlias());
    }

    @Test
    public void testJarReferenceAsURINotYetMounted() throws Exception
    {
        Path jar = MavenTestingUtils.getTestResourcePathFile("example.jar");
        URI jarFileUri = URIUtil.toJarFileUri(jar.toUri());
        assertNotNull(jarFileUri);
        assertThrows(IllegalStateException.class, () -> Resource.newResource(jarFileUri));
    }
}
