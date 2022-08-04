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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ExtendWith(WorkDirExtension.class)
public class MultiReleaseJarFileTest
{
    public WorkDir workDir;

    private void createExampleJar(Path outputJar) throws IOException
    {
        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI uri = URI.create("jar:" + outputJar.toUri().toASCIIString());
        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            Path root = zipfs.getPath("/");

            writeString(root.resolve("META-INF/MANIFEST.MF"), """
                Manifest-Version: 1.0
                Multi-Release: true
                Created-By: MultiReleaseJarFileTest
                """);

            writeString(root.resolve("META-INF/versions/10/org/example/In10Only.class"), "In10Only (versions/10)");
            writeString(root.resolve("META-INF/versions/10/org/example/InBoth.class"), "InBoth (versions/10)");

            writeString(root.resolve("META-INF/versions/9/org/example/Nowhere$NoOuter.class"), "Nowhere$NoOuter (versions/9)");
            writeString(root.resolve("META-INF/versions/9/org/example/InBoth$Inner9.class"), "InBoth$Inner9 (versions/9)");
            writeString(root.resolve("META-INF/versions/9/org/example/InBoth$InnerBoth.class"), "InBoth$Inner9 (versions/9)");
            writeString(root.resolve("META-INF/versions/9/org/example/OnlyIn9.class"), "OnlyIn9 (versions/9)");
            writeString(root.resolve("META-INF/versions/9/org/example/InBoth.class"), "InBoth (versions/9)");
            writeString(root.resolve("META-INF/versions/9/org/example/onlyIn9/OnlyIn9.class"), "OnlyIn9 (versions/9)");
            writeString(root.resolve("META-INF/versions/9/WEB-INF/classes/App.class"), "WEB-INF/classes/App.class (versions/9)");
            writeString(root.resolve("META-INF/versions/9/WEB-INF/lib/depend.jar"), "WEB-INF/lib/depend.jar (versions/9)");
            writeString(root.resolve("META-INF/versions/9/WEB-INF/web.xml"), "WEB-INF/web.xml (versions/9)");

            writeString(root.resolve("WEB-INF/classes/App.class"), "WEB-INF/classes/App.class (base)");
            writeString(root.resolve("WEB-INF/lib/depend.jar"), "WEB-INF/lib/depend.jar (base)");
            writeString(root.resolve("WEB-INF/web.xml"), "WEB-INF/web.xml (base)");

            writeString(root.resolve("org/example/InBoth$InnerBoth.class"), "InBoth$InnerBoth (base)");
            writeString(root.resolve("org/example/OnlyInBase.class"), "OnlyInBase (base)");
            writeString(root.resolve("org/example/InBoth.class"), "InBoth (base)");
            writeString(root.resolve("org/example/InBoth$InnerBase.class"), "InBoth$InnerBase (base)");
        }
    }

    private void writeString(Path outputPath, String contents) throws IOException
    {
        FS.ensureDirExists(outputPath.getParent());
        Files.writeString(outputPath, contents, StandardCharsets.UTF_8);
    }

    @Test
    public void testStreamAllFiles() throws Exception
    {
        Path exampleJar = workDir.getEmptyPathDir().resolve("multirelease.jar");
        createExampleJar(exampleJar);

        try (MultiReleaseJarFile jarFile = new MultiReleaseJarFile(exampleJar))
        {
            Set<String> actual = jarFile.stream()
                .filter(Files::isRegularFile)
                .map(Path::toString).collect(Collectors.toSet());
            String[] expected = {
                // exists in base only
                "/org/example/OnlyInBase.class",
                "/org/example/InBoth$InnerBase.class",
                // exists in versions/9
                "/org/example/Nowhere$NoOuter.class",
                "/org/example/InBoth$Inner9.class",
                "/org/example/OnlyIn9.class",
                "/org/example/onlyIn9/OnlyIn9.class",
                // exists in versions/10
                "/org/example/In10Only.class",
                // exists in base and is overridden by version specific entry
                "/org/example/InBoth.class",
                "/org/example/InBoth$InnerBoth.class",
                "/WEB-INF/web.xml",
                "/WEB-INF/classes/App.class",
                "/WEB-INF/lib/depend.jar",
                "/META-INF/MANIFEST.MF"
            };

            assertThat(actual, Matchers.containsInAnyOrder(expected));
        }
    }

    @Test
    public void testClassLoaderWithMultiReleaseBehaviors() throws Exception
    {
        Path exampleJar = workDir.getEmptyPathDir().resolve("multirelease.jar");
        createExampleJar(exampleJar);

        try (URLClassLoader loader = new URLClassLoader(new URL[]{exampleJar.toUri().toURL()}))
        {
            assertThat(readFile(loader.getResource("org/example/OnlyInBase.class")), is("OnlyInBase (base)"));
            assertThat(readFile(loader.getResource("org/example/OnlyIn9.class")), is("OnlyIn9 (versions/9)"));
            assertThat(readFile(loader.getResource("org/example/InBoth.class")), is("InBoth (versions/10)"));
            assertThat(readFile(loader.getResource("WEB-INF/web.xml")), is("WEB-INF/web.xml (versions/9)"));
            assertThat(readFile(loader.getResource("WEB-INF/classes/App.class")), is("WEB-INF/classes/App.class (versions/9)"));
            assertThat(readFile(loader.getResource("WEB-INF/lib/depend.jar")), is("WEB-INF/lib/depend.jar (versions/9)"));
        }
    }

    private String readFile(URL url) throws IOException
    {
        try (InputStream in = url.openStream())
        {
            return IO.toString(in, StandardCharsets.UTF_8);
        }
    }

    @Test
    public void testStreamAllFilesForEachEntries() throws IOException
    {
        List<String> entries = new ArrayList<>();

        Path exampleJar = workDir.getEmptyPathDir().resolve("multirelease.jar");
        createExampleJar(exampleJar);

        try (MultiReleaseJarFile jarFile = new MultiReleaseJarFile(exampleJar);
             Stream<Path> jarEntryStream = jarFile.stream()
                 .filter(Files::isRegularFile))
        {
            jarEntryStream.forEach(e ->
                entries.add(e.toString()));
        }

        String[] expected = {
            // exists in base only
            "/org/example/OnlyInBase.class",
            "/org/example/InBoth$InnerBase.class",
            // exists in versions/9
            "/org/example/Nowhere$NoOuter.class",
            "/org/example/InBoth$Inner9.class",
            "/org/example/OnlyIn9.class",
            "/org/example/onlyIn9/OnlyIn9.class",
            // exists in versions/10
            "/org/example/In10Only.class",
            // exists in base and is overridden by version specific entry
            "/org/example/InBoth.class",
            "/org/example/InBoth$InnerBoth.class",
            "/WEB-INF/web.xml",
            "/WEB-INF/classes/App.class",
            "/WEB-INF/lib/depend.jar",
            "/META-INF/MANIFEST.MF"
        };

        assertThat(entries, Matchers.containsInAnyOrder(expected));
    }
}
