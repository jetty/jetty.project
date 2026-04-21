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
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WorkDirExtension.class)
public class PathFinderTest
{
    @Test
    public void testFindInis(WorkDir workDir) throws IOException
    {
        Path basePath = workDir.getPath();
        Path homeDir = MavenPaths.findTestResourceDir("hb.1/home");
        Path homePath = homeDir.toAbsolutePath();

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
        expected.replaceAll(FS::separators);

        BaseHome hb = new BaseHome(new String[]{"jetty.home=" + homePath.toString(), "jetty.base=" + basePath.toString()});
        BaseHomeTest.assertPathList(hb, "Files found", expected, finder);
    }

    @Test
    public void testFindMods(WorkDir workDir) throws IOException
    {
        Path basePath = workDir.getEmptyPathDir();
        Path homeDir = MavenPaths.findTestResourceDir("dist-home");
        Path homePath = homeDir.toAbsolutePath();

        List<String> expected = new ArrayList<>();
        Path modulesDir = homeDir.resolve("modules");
        try (Stream<Path> listStream = Files.list(modulesDir))
        {
            listStream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".mod"))
                .map(p -> FS.separators("${jetty.home}/modules/" + p.getFileName()))
                .forEach(expected::add);
        }

        PathFinder finder = new PathFinder();
        finder.setFileMatcher(PathMatchers.getMatcher("modules/*.mod"));
        finder.setBase(modulesDir);

        Files.walkFileTree(modulesDir, EnumSet.of(FileVisitOption.FOLLOW_LINKS), 1, finder);

        BaseHome hb = new BaseHome(new String[]{"jetty.home=" + homePath, "jetty.base=" + basePath});
        BaseHomeTest.assertPathList(hb, "Files found", expected, finder);
    }
}
