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

package org.eclipse.jetty.util;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.stream.Collectors;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.MultiReleaseJarFile.VersionedJarEntry;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MultiReleaseJarFileTest
{
    private File example = MavenTestingUtils.getTestResourceFile("example.jar");

    @Test
    public void testExampleJarIsMR() throws Exception
    {
        try (MultiReleaseJarFile jarFile = new MultiReleaseJarFile(example))
        {
            assertTrue(jarFile.isMultiRelease(), "Expected " + example + " to be MultiRelease JAR File");
        }
    }

    @Test
    public void testBase() throws Exception
    {
        try (MultiReleaseJarFile jarFile = new MultiReleaseJarFile(example, 8, false))
        {
            assertThat(jarFile.getEntry("META-INF/MANIFEST.MF").getVersion(), is(0));
            assertThat(jarFile.getEntry("org/example/OnlyInBase.class").getVersion(), is(0));
            assertThat(jarFile.getEntry("org/example/InBoth$InnerBase.class").getVersion(), is(0));
            assertThat(jarFile.getEntry("org/example/InBoth$InnerBoth.class").getVersion(), is(0));
            assertThat(jarFile.getEntry("org/example/InBoth.class").getVersion(), is(0));

            assertThat(jarFile.stream().map(VersionedJarEntry::getName).collect(Collectors.toSet()),
                Matchers.containsInAnyOrder(
                    "META-INF/MANIFEST.MF",
                    "org/example/OnlyInBase.class",
                    "org/example/InBoth$InnerBase.class",
                    "org/example/InBoth$InnerBoth.class",
                    "org/example/InBoth.class",
                    "WEB-INF/web.xml",
                    "WEB-INF/classes/App.class",
                    "WEB-INF/lib/depend.jar"));
        }
    }

    @Test
    public void test9() throws Exception
    {
        try (MultiReleaseJarFile jarFile = new MultiReleaseJarFile(example, 9, false))
        {
            assertThat(jarFile.getEntry("META-INF/MANIFEST.MF").getVersion(), is(0));
            assertThat(jarFile.getEntry("org/example/OnlyInBase.class").getVersion(), is(0));
            assertThat(jarFile.getEntry("org/example/InBoth$InnerBoth.class").getVersion(), is(9));
            assertThat(jarFile.getEntry("org/example/InBoth.class").getVersion(), is(9));
            assertThat(jarFile.getEntry("org/example/OnlyIn9.class").getVersion(), is(9));
            assertThat(jarFile.getEntry("org/example/onlyIn9/OnlyIn9.class").getVersion(), is(9));
            assertThat(jarFile.getEntry("org/example/InBoth$Inner9.class").getVersion(), is(9));

            assertThat(jarFile.stream().map(VersionedJarEntry::getName).collect(Collectors.toSet()),
                Matchers.containsInAnyOrder(
                    "META-INF/MANIFEST.MF",
                    "org/example/OnlyInBase.class",
                    "org/example/InBoth$InnerBoth.class",
                    "org/example/InBoth.class",
                    "org/example/OnlyIn9.class",
                    "org/example/onlyIn9/OnlyIn9.class",
                    "org/example/InBoth$Inner9.class",
                    "WEB-INF/web.xml",
                    "WEB-INF/classes/App.class",
                    "WEB-INF/lib/depend.jar"));
        }
    }

    @Test
    public void test10() throws Exception
    {
        try (MultiReleaseJarFile jarFile = new MultiReleaseJarFile(example, 10, false))
        {
            assertThat(jarFile.getEntry("META-INF/MANIFEST.MF").getVersion(), is(0));
            assertThat(jarFile.getEntry("org/example/OnlyInBase.class").getVersion(), is(0));
            assertThat(jarFile.getEntry("org/example/InBoth.class").getVersion(), is(10));
            assertThat(jarFile.getEntry("org/example/In10Only.class").getVersion(), is(10));

            assertThat(jarFile.stream().map(VersionedJarEntry::getName).collect(Collectors.toSet()),
                Matchers.containsInAnyOrder(
                    "META-INF/MANIFEST.MF",
                    "org/example/OnlyInBase.class",
                    "org/example/InBoth.class",
                    "org/example/In10Only.class",
                    "WEB-INF/web.xml",
                    "WEB-INF/classes/App.class",
                    "WEB-INF/lib/depend.jar"));
        }
    }

    @Test
    public void testClassLoaderJava9() throws Exception
    {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{example.toURI().toURL()}))
        {
            assertThat(IO.toString(loader.getResource("org/example/OnlyInBase.class").openStream()), is("org/example/OnlyInBase.class"));
            assertThat(IO.toString(loader.getResource("org/example/OnlyIn9.class").openStream()), is("META-INF/versions/9/org/example/OnlyIn9.class"));
            assertThat(IO.toString(loader.getResource("WEB-INF/web.xml").openStream()), is("META-INF/versions/9/WEB-INF/web.xml"));
            assertThat(IO.toString(loader.getResource("WEB-INF/classes/App.class").openStream()), is("META-INF/versions/9/WEB-INF/classes/App.class"));
            assertThat(IO.toString(loader.getResource("WEB-INF/lib/depend.jar").openStream()), is("META-INF/versions/9/WEB-INF/lib/depend.jar"));
        }
    }
}
