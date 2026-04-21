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

package org.eclipse.jetty.start;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.start.config.ConfigSources;
import org.eclipse.jetty.start.config.JettyBaseConfigSource;
import org.eclipse.jetty.start.config.JettyHomeConfigSource;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

public class BaseHomeTest
{
    public static void assertPathList(BaseHome hb, String message, List<String> expected, PathFinder finder)
    {
        List<String> actual = new ArrayList<>();
        for (Path path : finder.getHits())
        {
            actual.add(hb.toShortForm(path));
        }

        if (actual.size() != expected.size())
        {
            System.out.printf("Actual Path(s): %,d hits%n", actual.size());
            for (String path : actual)
            {
                System.out.printf(" %s%n", path);
            }
            System.out.printf("Expected Path(s): %,d entries%n", expected.size());
            for (String path : expected)
            {
                System.out.printf(" %s%n", path);
            }
        }
        assertThat(message + ": " + Utils.join(actual, ", "), actual, containsInAnyOrder(expected.toArray()));
    }

    public static void assertPathList(BaseHome hb, String message, List<String> expected, List<Path> paths)
    {
        List<String> actual = new ArrayList<>();
        for (Path path : paths)
        {
            actual.add(hb.toShortForm(path));
        }

        if (actual.size() != expected.size())
        {
            System.out.printf("Actual Path(s): %,d hits%n", actual.size());
            for (String path : actual)
            {
                System.out.printf(" %s%n", path);
            }
            System.out.printf("Expected Path(s): %,d entries%n", expected.size());
            for (String path : expected)
            {
                System.out.printf(" %s%n", path);
            }
        }
        assertThat(message + ": " + Utils.join(actual, ", "), actual, containsInAnyOrder(expected.toArray()));
    }

    @Test
    public void testGetPathOnlyHome() throws IOException
    {
        Path homeDir = MavenPaths.findTestResourceDir("hb.1/home");

        ConfigSources config = new ConfigSources();
        config.add(new JettyHomeConfigSource(homeDir));

        BaseHome hb = new BaseHome(config);
        Path startIni = hb.getPath("start.ini");

        String ref = hb.toShortForm(startIni);
        assertThat("Reference", ref, startsWith("${jetty.home}"));

        String contents = Files.readString(startIni, UTF_8);
        assertThat("Contents", contents, containsString("Home Ini"));
    }

    @Test
    public void testGetPathsOnlyHome() throws IOException
    {
        Path homeDir = MavenPaths.findTestResourceDir("hb.1/home");

        ConfigSources config = new ConfigSources();
        config.add(new JettyHomeConfigSource(homeDir));

        BaseHome hb = new BaseHome(config);
        List<Path> paths = hb.getPaths("start.d/*");

        List<String> expected = new ArrayList<>();
        expected.add("${jetty.home}/start.d/jmx.ini");
        expected.add("${jetty.home}/start.d/jndi.ini");
        expected.add("${jetty.home}/start.d/jsp.ini");
        expected.add("${jetty.home}/start.d/logging.ini");
        expected.add("${jetty.home}/start.d/ssl.ini");
        expected.replaceAll(FS::separators);

        assertPathList(hb, "Paths found", expected, paths);
    }

    @Test
    public void testGetPathsOnlyHomeInisOnly() throws IOException
    {
        Path homeDir = MavenPaths.findTestResourceDir("hb.1/home");

        ConfigSources config = new ConfigSources();
        config.add(new JettyHomeConfigSource(homeDir));

        BaseHome hb = new BaseHome(config);
        List<Path> paths = hb.getPaths("start.d/*.ini");

        List<String> expected = new ArrayList<>();
        expected.add("${jetty.home}/start.d/jmx.ini");
        expected.add("${jetty.home}/start.d/jndi.ini");
        expected.add("${jetty.home}/start.d/jsp.ini");
        expected.add("${jetty.home}/start.d/logging.ini");
        expected.add("${jetty.home}/start.d/ssl.ini");
        expected.replaceAll(FS::separators);

        assertPathList(hb, "Paths found", expected, paths);
    }

    @Test
    public void testGetPathsBoth() throws IOException
    {
        Path homeDir = MavenPaths.findTestResourceDir("hb.1/home");
        Path baseDir = MavenPaths.findTestResourceDir("hb.1/base");

        ConfigSources config = new ConfigSources();
        config.add(new JettyBaseConfigSource(baseDir));
        config.add(new JettyHomeConfigSource(homeDir));

        BaseHome hb = new BaseHome(config);
        List<Path> paths = hb.getPaths("start.d/*.ini");

        List<String> expected = new ArrayList<>();
        expected.add("${jetty.base}/start.d/jmx.ini");
        expected.add("${jetty.home}/start.d/jndi.ini");
        expected.add("${jetty.home}/start.d/jsp.ini");
        expected.add("${jetty.base}/start.d/logging.ini");
        expected.add("${jetty.home}/start.d/ssl.ini");
        expected.add("${jetty.base}/start.d/myapp.ini");
        expected.replaceAll(FS::separators);

        assertPathList(hb, "Paths found", expected, paths);
    }

    @Test
    public void testDefault() throws IOException
    {
        BaseHome bh = new BaseHome();
        assertThat("Home", bh.getHome(), notNullValue());
        assertThat("Base", bh.getBase(), notNullValue());
    }

    @Test
    public void testGetPathBoth() throws IOException
    {
        Path homeDir = MavenPaths.findTestResourceDir("hb.1/home");
        Path baseDir = MavenPaths.findTestResourceDir("hb.1/base");

        ConfigSources config = new ConfigSources();
        config.add(new JettyBaseConfigSource(baseDir));
        config.add(new JettyHomeConfigSource(homeDir));

        BaseHome hb = new BaseHome(config);
        Path startIni = hb.getPath("start.ini");

        String ref = hb.toShortForm(startIni);
        assertThat("Reference", ref, startsWith("${jetty.base}"));

        String contents = Files.readString(startIni, UTF_8);
        assertThat("Contents", contents, containsString("Base Ini"));
    }
}
