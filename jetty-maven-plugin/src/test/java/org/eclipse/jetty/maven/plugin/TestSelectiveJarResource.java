//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.maven.plugin;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 
 *
 */
public class TestSelectiveJarResource
{
    File unpackParent;
    
    @BeforeEach
    public void setUp() throws Exception
    {
        unpackParent = MavenTestingUtils.getTargetTestingDir("selective-jar-resource");
        unpackParent.mkdirs();
    }
    
    @Test
    public void testIncludesNoExcludes() throws Exception
    {
        File unpackDir = File.createTempFile("inc", "exc", unpackParent);
        unpackDir.delete();
        unpackDir.mkdirs();

        File testJar = MavenTestingUtils.getTestResourceFile("selective-jar-test.jar");
        try (SelectiveJarResource sjr = new SelectiveJarResource(new URL("jar:" + Resource.toURL(testJar).toString() + "!/"));)
        {
            sjr.setCaseSensitive(false);
            List<String> includes = new ArrayList<>();
            includes.add("**/*.html");
            sjr.setIncludes(includes);
            sjr.copyTo(unpackDir);
            assertTrue(Files.exists(unpackDir.toPath().resolve("top.html")));
            assertTrue(Files.exists(unpackDir.toPath().resolve("aa/a1.html")));
            assertTrue(Files.exists(unpackDir.toPath().resolve("aa/a2.html")));
            assertTrue(Files.exists(unpackDir.toPath().resolve("aa/deep/a3.html")));
            assertTrue(Files.exists(unpackDir.toPath().resolve("bb/b1.html")));
            assertTrue(Files.exists(unpackDir.toPath().resolve("bb/b2.html")));
            assertTrue(Files.exists(unpackDir.toPath().resolve("cc/c1.html")));
            assertTrue(Files.exists(unpackDir.toPath().resolve("cc/c2.html")));
        }
    }

    @Test
    public void testExcludesNoIncludes() throws Exception
    {
        File unpackDir = File.createTempFile("exc", "inc", unpackParent);
        unpackDir.delete();
        unpackDir.mkdirs();
        File testJar = MavenTestingUtils.getTestResourceFile("selective-jar-test.jar");
       
        try (SelectiveJarResource sjr = new SelectiveJarResource(new URL("jar:" + Resource.toURL(testJar).toString() + "!/"));)
        {
            sjr.setCaseSensitive(false);
            List<String> excludes = new ArrayList<>();
            excludes.add("**/*");
            sjr.setExcludes(excludes);
            sjr.copyTo(unpackDir);
            assertFalse(Files.exists(unpackDir.toPath().resolve("top.html")));
            assertFalse(Files.exists(unpackDir.toPath().resolve("aa/a1.html")));
            assertFalse(Files.exists(unpackDir.toPath().resolve("aa/a2.html")));
            assertFalse(Files.exists(unpackDir.toPath().resolve("aa/deep/a3.html")));
            assertFalse(Files.exists(unpackDir.toPath().resolve("bb/b1.html")));
            assertFalse(Files.exists(unpackDir.toPath().resolve("bb/b2.html")));
            assertFalse(Files.exists(unpackDir.toPath().resolve("cc/c1.html")));
            assertFalse(Files.exists(unpackDir.toPath().resolve("cc/c2.html")));
        }
    }
    
    @Test
    public void testIncludesExcludes() throws Exception
    {
        File unpackDir = File.createTempFile("exc", "andinc", unpackParent);
        unpackDir.delete();
        unpackDir.mkdirs();
        File testJar = MavenTestingUtils.getTestResourceFile("selective-jar-test.jar");
       
        try (SelectiveJarResource sjr = new SelectiveJarResource(new URL("jar:" + Resource.toURL(testJar).toString() + "!/"));)
        {
            sjr.setCaseSensitive(false);
            List<String> excludes = new ArrayList<>();
            excludes.add("**/deep/*");
            sjr.setExcludes(excludes);
            List<String> includes = new ArrayList<>();
            includes.add("bb/*");
            sjr.setIncludes(includes);
            sjr.copyTo(unpackDir);
            assertFalse(Files.exists(unpackDir.toPath().resolve("top.html")));
            assertFalse(Files.exists(unpackDir.toPath().resolve("aa/a1.html")));
            assertFalse(Files.exists(unpackDir.toPath().resolve("aa/a2.html")));
            assertFalse(Files.exists(unpackDir.toPath().resolve("aa/deep/a3.html")));
            assertTrue(Files.exists(unpackDir.toPath().resolve("bb/b1.html")));
            assertTrue(Files.exists(unpackDir.toPath().resolve("bb/b2.html")));
            assertFalse(Files.exists(unpackDir.toPath().resolve("cc/c1.html")));
            assertFalse(Files.exists(unpackDir.toPath().resolve("cc/c2.html")));
        }
    }
}
