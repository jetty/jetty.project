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

package org.eclipse.jetty.start.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;

/**
 * Utility class to rebuild the src/test/resources/dist-home from the active build tree.
 * <p>
 * Not really meant to be run with each build. Nor is it a good idea to attempt to do that (as this would introduce a dependency from jetty-start ->
 * jetty-home which is a circular dependency)
 */
public class RebuildTestResources
{
    public static final String JETTY_VERSION = "9.3";

    public static void main(String[] args)
    {
        File realDistHome = MavenTestingUtils.getProjectDir("../jetty-home/target/jetty-home");
        File outputDir = MavenTestingUtils.getTestResourceDir("dist-home");
        try
        {
            new RebuildTestResources(realDistHome, outputDir).rebuild();
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
    }

    private static interface FileCopier
    {
        public void copy(Path from, Path to) throws IOException;
    }

    private static class NormalFileCopier implements FileCopier
    {
        @Override
        public void copy(Path from, Path to) throws IOException
        {
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static class TouchFileCopier implements FileCopier
    {
        @Override
        public void copy(Path from, Path to) throws IOException
        {
            if (Files.exists(to))
            {
                // skip if it exists
                return;
            }
            Files.createFile(to);
        }
    }

    private static interface Renamer
    {
        public String getName(Path path);
    }

    private static class NoRenamer implements Renamer
    {
        @Override
        public String getName(Path path)
        {
            return path.getFileName().toString();
        }
    }

    private static class RegexRenamer implements Renamer
    {
        private final Pattern pat;
        private final String replacement;

        public RegexRenamer(String regex, String replacement)
        {
            this.pat = Pattern.compile(regex);
            this.replacement = replacement;
        }

        @Override
        public String getName(Path path)
        {
            String origName = path.getFileName().toString();
            return pat.matcher(origName).replaceAll(replacement);
        }
    }

    private final Path destDir;
    private final Path srcDir;

    public RebuildTestResources(File realDistHome, File outputDir) throws IOException
    {
        this.srcDir = realDistHome.toPath().toRealPath();
        this.destDir = outputDir.toPath();
    }

    private void copyLibs() throws IOException
    {
        System.out.println("Copying libs (lib dir) ...");
        Path libsDir = destDir.resolve("lib");
        FS.ensureDirExists(libsDir.toFile());

        PathMatcher matcher = getPathMatcher("glob:**.jar");
        Renamer renamer = new RegexRenamer("-9\\.[0-9.]*(v[0-9-]*)?(-SNAPSHOT)?(RC[0-9])?(M[0-9])?", "-" + JETTY_VERSION);
        FileCopier copier = new TouchFileCopier();
        copyDir(srcDir.resolve("lib"), libsDir, matcher, renamer, copier);
    }

    private void copyModules() throws IOException
    {
        System.out.println("Copying modules ...");
        Path modulesDir = destDir.resolve("modules");
        FS.ensureDirExists(modulesDir.toFile());

        PathMatcher matcher = getPathMatcher("glob:**.mod");
        Renamer renamer = new NoRenamer();
        FileCopier copier = new NormalFileCopier();
        copyDir(srcDir.resolve("modules"), modulesDir, matcher, renamer, copier);
    }

    private void copyXmls() throws IOException
    {
        System.out.println("Copying xmls (etc dir) ...");
        Path xmlDir = destDir.resolve("etc");
        FS.ensureDirExists(xmlDir.toFile());

        PathMatcher matcher = getPathMatcher("glob:**.xml");
        Renamer renamer = new NoRenamer();
        FileCopier copier = new TouchFileCopier();
        copyDir(srcDir.resolve("etc"), xmlDir, matcher, renamer, copier);
    }

    private void rebuild() throws IOException
    {
        copyModules();
        copyLibs();
        copyXmls();
        System.out.println("Done");
    }

    private PathMatcher getPathMatcher(String pattern)
    {
        return FileSystems.getDefault().getPathMatcher(pattern);
    }

    private void copyDir(Path from, Path to, PathMatcher fileMatcher, Renamer renamer, FileCopier copier) throws IOException
    {
        Files.createDirectories(to);

        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(from))
        {
            for (Path path : directoryStream)
            {
                String name = renamer.getName(path);
                Path dest = to.resolve(name);
                if (Files.isDirectory(path))
                {
                    copyDir(path, dest, fileMatcher, renamer, copier);
                }
                else
                {
                    if (fileMatcher.matches(path))
                    {
                        copier.copy(path, dest);
                    }
                }
            }
        }
    }
}
