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
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WorkDirExtension.class)
public class PathFinderTest
{
    public WorkDir testdir;

    @Test
    public void testFindInis() throws IOException
    {
        File homeDir = MavenTestingUtils.getTestResourceDir("hb.1/home");
        Path homePath = homeDir.toPath().toAbsolutePath();
        Path basePath = testdir.getEmptyPathDir();

        PathFinder finder = new PathFinder();
        finder.setFileMatcher("glob:**/*.ini");
        finder.setBase(homePath);

        Files.walkFileTree(homePath, EnumSet.of(FileVisitOption.FOLLOW_LINKS), 30, finder);

        List<String> expected = new ArrayList<>();
        expected.add("${jetty.home}/start.d/jmx.ini");
        expected.add("${jetty.home}/start.d/jndi.ini");
        expected.add("${jetty.home}/start.d/jsp.ini");
        expected.add("${jetty.home}/start.d/logging.ini");
        expected.add("${jetty.home}/start.d/ssl.ini");
        expected.add("${jetty.home}/start.ini");
        FSTest.toFsSeparators(expected);

        BaseHome hb = new BaseHome(new String[]{"jetty.home=" + homePath.toString(), "jetty.base=" + basePath.toString()});
        BaseHomeTest.assertPathList(hb, "Files found", expected, finder);
    }

    @Test
    public void testFindMods() throws IOException
    {
        File homeDir = MavenTestingUtils.getTestResourceDir("dist-home");
        Path homePath = homeDir.toPath().toAbsolutePath();
        Path basePath = testdir.getEmptyPathDir();

        List<String> expected = new ArrayList<>();
        File modulesDir = new File(homeDir, "modules");
        for (File file : modulesDir.listFiles())
        {
            if (file.getName().endsWith(".mod"))
            {
                expected.add("${jetty.home}/modules/" + file.getName());
            }
        }
        FSTest.toFsSeparators(expected);

        Path modulesPath = modulesDir.toPath();

        PathFinder finder = new PathFinder();
        finder.setFileMatcher(PathMatchers.getMatcher("modules/*.mod"));
        finder.setBase(modulesPath);

        Files.walkFileTree(modulesPath, EnumSet.of(FileVisitOption.FOLLOW_LINKS), 1, finder);

        BaseHome hb = new BaseHome(new String[]{"jetty.home=" + homePath.toString(), "jetty.base=" + basePath.toString()});
        BaseHomeTest.assertPathList(hb, "Files found", expected, finder);
    }
}
