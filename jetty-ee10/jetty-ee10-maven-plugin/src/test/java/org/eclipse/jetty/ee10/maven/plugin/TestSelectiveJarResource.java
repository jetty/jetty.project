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

package org.eclipse.jetty.ee10.maven.plugin;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.eclipse.jetty.maven.SelectiveJarResource;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class TestSelectiveJarResource
{

    @Test
    public void testIncludesNoExcludes(WorkDir workDir) throws Exception
    {
        Path unpackDir = workDir.getPath();
        Path testJar = MavenTestingUtils.getTestResourcePathFile("selective-jar-test.jar");
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newJarFileResource(testJar.toUri());
            SelectiveJarResource sjr = new SelectiveJarResource(resource);
            sjr.setCaseSensitive(false);
            List<String> includes = new ArrayList<>();
            includes.add("**/*.html");
            sjr.setIncludes(includes);
            sjr.copyTo(unpackDir);
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
    public void testExcludesNoIncludes(WorkDir workDir) throws Exception
    {
        Path unpackDir = workDir.getPath();
        Path testJar = MavenTestingUtils.getTestResourcePathFile("selective-jar-test.jar");
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newJarFileResource(testJar.toUri());
            SelectiveJarResource sjr = new SelectiveJarResource(resource);
            sjr.setCaseSensitive(false);
            List<String> excludes = new ArrayList<>();
            excludes.add("**/*");
            sjr.setExcludes(excludes);
            sjr.copyTo(unpackDir);
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
    public void testIncludesExcludes(WorkDir workDir) throws Exception
    {
        Path unpackDir = workDir.getPath();
        Path testJar = MavenTestingUtils.getTestResourcePathFile("selective-jar-test.jar");
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newJarFileResource(testJar.toUri());
            SelectiveJarResource sjr = new SelectiveJarResource(resource);
            sjr.setCaseSensitive(false);
            List<String> excludes = new ArrayList<>();
            excludes.add("**/deep/*");
            sjr.setExcludes(excludes);
            List<String> includes = new ArrayList<>();
            includes.add("bb/*");
            sjr.setIncludes(includes);
            sjr.copyTo(unpackDir);
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

    @Test
    public void testCopyToWithManifestAndMavenDescriptor(WorkDir workDir) throws Exception
    {
        Path base = workDir.getPath();
        Path jar = base.resolve("overlay.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        String pomEntry = "META-INF/maven/com.example/demo/pom.xml";
        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream jarOutput = new JarOutputStream(output, manifest))
        {
            jarOutput.putNextEntry(new JarEntry(pomEntry));
            jarOutput.write("<project/>".getBytes(StandardCharsets.UTF_8));
            jarOutput.closeEntry();
        }

        Path unpackDir = base.resolve("unpacked");
        Files.createDirectories(unpackDir);
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newJarFileResource(jar.toUri());
            SelectiveJarResource selectiveJarResource = new SelectiveJarResource(resource);
            selectiveJarResource.copyTo(unpackDir);
        }

        Path manifestFile = unpackDir.resolve("META-INF/MANIFEST.MF");
        Path pomFile = unpackDir.resolve(pomEntry);
        assertTrue(Files.isRegularFile(manifestFile));
        assertTrue(Files.readString(manifestFile).contains("Manifest-Version"));
        assertEquals("<project/>", Files.readString(pomFile));
    }
}
