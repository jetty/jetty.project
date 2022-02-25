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
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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

public class ResourceTest
{
    private static final boolean DIR = true;
    private static final boolean EXISTS = true;

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
            resource = data.resource.addPath(path);
            this.exists = exists;
            this.dir = dir;
        }

        Scenario(Scenario data, String path, boolean exists, boolean dir, String content)
            throws Exception
        {
            this.test = data.resource + "+" + path;
            resource = data.resource.addPath(path);
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
            resource = Resource.newResource(file);
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

        cases.addCase(new Scenario("jar:file:/somejar.jar!/content/", !EXISTS, !DIR));
        cases.addCase(new Scenario("jar:file:/somejar.jar!/", !EXISTS, !DIR));

        String urlRef = cases.uriRef.toASCIIString();
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
            Resource r = data.resource.addPath("foo%/b r");
            assertThat(r.getURI().toString(), Matchers.endsWith("/foo%25/b%20r"));
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResourceContent(Scenario data)
        throws Exception
    {
        Assumptions.assumeTrue(data.content != null);

        InputStream in = data.resource.getInputStream();
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
    public void testClimbAboveBase() throws Exception
    {
        Resource resource = Resource.newResource("/foo/bar");
        assertThrows(MalformedURLException.class, () -> resource.addPath(".."));

        Resource same = resource.addPath(".");
        assertNotNull(same);
        assertTrue(same.isAlias());

        assertThrows(MalformedURLException.class, () -> resource.addPath("./.."));

        assertThrows(MalformedURLException.class, () -> resource.addPath("./../bar"));
    }
}
