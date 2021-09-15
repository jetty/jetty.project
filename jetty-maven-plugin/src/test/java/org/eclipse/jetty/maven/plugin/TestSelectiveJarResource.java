//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.maven.plugin;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class TestSelectiveJarResource
{
    public WorkDir workDir;

    @Test
    public void testIncludesNoExcludes() throws Exception
    {
        Path unpackDir = workDir.getEmptyPathDir();

        Path testJar = MavenTestingUtils.getTestResourcePathFile("selective-jar-test.jar");
        try (SelectiveJarResource sjr = new SelectiveJarResource(new URL("jar:" + testJar.toUri().toASCIIString() + "!/")))
        {
            sjr.setCaseSensitive(false);
            List<String> includes = new ArrayList<>();
            includes.add("**/*.html");
            sjr.setIncludes(includes);
            sjr.copyTo(unpackDir.toFile());
            assertTrue(Files.exists(unpackDir.resolve("top.html")));
            assertTrue(Files.exists(unpackDir.resolve("aa/a1.html")));
            assertTrue(Files.exists(unpackDir.resolve("aa/a2.html")));
            assertTrue(Files.exists(unpackDir.resolve("aa/deep/a3.html")));
            assertTrue(Files.exists(unpackDir.resolve("bb/b1.html")));
            assertTrue(Files.exists(unpackDir.resolve("bb/b2.html")));
            assertTrue(Files.exists(unpackDir.resolve("cc/c1.html")));
            assertTrue(Files.exists(unpackDir.resolve("cc/c2.html")));
        }
    }

    @Test
    public void testExcludesNoIncludes() throws Exception
    {
        Path unpackDir = workDir.getEmptyPathDir();

        Path testJar = MavenTestingUtils.getTestResourcePathFile("selective-jar-test.jar");
        try (SelectiveJarResource sjr = new SelectiveJarResource(new URL("jar:" + testJar.toUri().toASCIIString() + "!/")))
        {
            sjr.setCaseSensitive(false);
            List<String> excludes = new ArrayList<>();
            excludes.add("**/*");
            sjr.setExcludes(excludes);
            sjr.copyTo(unpackDir.toFile());
            assertFalse(Files.exists(unpackDir.resolve("top.html")));
            assertFalse(Files.exists(unpackDir.resolve("aa/a1.html")));
            assertFalse(Files.exists(unpackDir.resolve("aa/a2.html")));
            assertFalse(Files.exists(unpackDir.resolve("aa/deep/a3.html")));
            assertFalse(Files.exists(unpackDir.resolve("bb/b1.html")));
            assertFalse(Files.exists(unpackDir.resolve("bb/b2.html")));
            assertFalse(Files.exists(unpackDir.resolve("cc/c1.html")));
            assertFalse(Files.exists(unpackDir.resolve("cc/c2.html")));
        }
    }
    
    @Test
    public void testIncludesExcludes() throws Exception
    {
        Path unpackDir = workDir.getEmptyPathDir();

        Path testJar = MavenTestingUtils.getTestResourcePathFile("selective-jar-test.jar");
        try (SelectiveJarResource sjr = new SelectiveJarResource(new URL("jar:" + testJar.toUri().toASCIIString() + "!/")))
        {
            sjr.setCaseSensitive(false);
            List<String> excludes = new ArrayList<>();
            excludes.add("**/deep/*");
            sjr.setExcludes(excludes);
            List<String> includes = new ArrayList<>();
            includes.add("bb/*");
            sjr.setIncludes(includes);
            sjr.copyTo(unpackDir.toFile());
            assertFalse(Files.exists(unpackDir.resolve("top.html")));
            assertFalse(Files.exists(unpackDir.resolve("aa/a1.html")));
            assertFalse(Files.exists(unpackDir.resolve("aa/a2.html")));
            assertFalse(Files.exists(unpackDir.resolve("aa/deep/a3.html")));
            assertTrue(Files.exists(unpackDir.resolve("bb/b1.html")));
            assertTrue(Files.exists(unpackDir.resolve("bb/b2.html")));
            assertFalse(Files.exists(unpackDir.resolve("cc/c1.html")));
            assertFalse(Files.exists(unpackDir.resolve("cc/c2.html")));
        }
    }
}
