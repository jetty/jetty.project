//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.start;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Rule;
import org.junit.Test;

public class PathFinderTest
{
    @Rule
    public TestingDir testdir = new TestingDir();

    @Test
    public void testFindInis() throws IOException
    {
        File homeDir = MavenTestingUtils.getTestResourceDir("hb.1/home");
        Path homePath = homeDir.toPath().toAbsolutePath();
        File baseDir = testdir.getEmptyDir();
        Path basePath = baseDir.toPath().toAbsolutePath();

        PathFinder finder = new PathFinder();
        finder.setFileMatcher("glob:**/*.ini");
        finder.setBase(homePath);

        Files.walkFileTree(homePath,EnumSet.of(FileVisitOption.FOLLOW_LINKS),30,finder);

        List<String> expected = new ArrayList<>();
        expected.add("${jetty.home}/start.d/jmx.ini");
        expected.add("${jetty.home}/start.d/jndi.ini");
        expected.add("${jetty.home}/start.d/jsp.ini");
        expected.add("${jetty.home}/start.d/logging.ini");
        expected.add("${jetty.home}/start.d/ssl.ini");
        expected.add("${jetty.home}/start.ini");
        FSTest.toOsSeparators(expected);

        BaseHome hb = new BaseHome(new String[] { "jetty.home=" + homePath.toString(), "jetty.base=" + basePath.toString() });
        BaseHomeTest.assertPathList(hb,"Files found",expected,finder);
    }

    @Test
    public void testFindMods() throws IOException
    {
        File homeDir = MavenTestingUtils.getTestResourceDir("dist-home");
        Path homePath = homeDir.toPath().toAbsolutePath();
        File baseDir = testdir.getEmptyDir();
        Path basePath = baseDir.toPath().toAbsolutePath();

        List<String> expected = new ArrayList<>();
        File modulesDir = new File(homeDir,"modules");
        for (File file : modulesDir.listFiles())
        {
            if (file.getName().endsWith(".mod"))
            {
                expected.add("${jetty.home}/modules/" + file.getName());
            }
        }
        FSTest.toOsSeparators(expected);
        
        Path modulesPath = modulesDir.toPath();

        PathFinder finder = new PathFinder();
        finder.setFileMatcher(PathMatchers.getMatcher("modules/*.mod"));
        finder.setBase(modulesPath);
        
        Files.walkFileTree(modulesPath,EnumSet.of(FileVisitOption.FOLLOW_LINKS),1,finder);

        BaseHome hb = new BaseHome(new String[] { "jetty.home=" + homePath.toString(), "jetty.base=" + basePath.toString() });
        BaseHomeTest.assertPathList(hb,"Files found",expected,finder);
    }
}
