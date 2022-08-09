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

package org.eclipse.jetty.util.resource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.util.URIUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PathResourceTest
{
    @BeforeEach
    public void beforeEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @AfterEach
    public void afterEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @Test
    public void testNonDefaultFileSystemGetInputStream() throws IOException
    {
        Path exampleJar = MavenTestingUtils.getTestResourcePathFile("example.jar");

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource jarFileResource = resourceFactory.newJarFileResource(exampleJar.toUri());
            Path manifestPath = jarFileResource.getPath().resolve("/META-INF/MANIFEST.MF");
            assertThat(manifestPath, is(not(nullValue())));

            PathResource resource = (PathResource)resourceFactory.newResource(manifestPath);

            try (InputStream inputStream = resource.newInputStream())
            {
                assertThat("InputStream", inputStream, is(not(nullValue())));
            }
        }
    }

    @Test
    public void testNonDefaultFileSystemGetReadableByteChannel() throws IOException
    {
        Path exampleJar = MavenTestingUtils.getTestResourcePathFile("example.jar");

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource jarFileResource = resourceFactory.newJarFileResource(exampleJar.toUri());
            Path manifestPath = jarFileResource.getPath().resolve("/META-INF/MANIFEST.MF");
            assertThat(manifestPath, is(not(nullValue())));

            PathResource resource = (PathResource)resourceFactory.newResource(manifestPath);

            try (ReadableByteChannel channel = resource.newReadableByteChannel())
            {
                assertThat("ReadableByteChannel", channel, is(not(nullValue())));
            }
        }
    }

    @Test
    public void testNonDefaultFileSystemGetPath()
    {
        Path exampleJar = MavenTestingUtils.getTestResourcePathFile("example.jar");

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource jarFileResource = resourceFactory.newJarFileResource(exampleJar.toUri());
            Path manifestPath = jarFileResource.getPath().resolve("/META-INF/MANIFEST.MF");
            assertThat(manifestPath, is(not(nullValue())));

            Resource resource = resourceFactory.newResource(manifestPath);
            Path path = resource.getPath();
            assertThat("Path should not be null even for non-default FileSystem", path, notNullValue());
        }
    }

    @Test
    public void testDefaultFileSystemGetFile()
    {
        Path exampleJar = MavenTestingUtils.getTestResourcePathFile("example.jar");
        PathResource resource = (PathResource)ResourceFactory.root().newResource(exampleJar);

        Path path = resource.getPath();
        assertThat("File for default FileSystem", path, is(exampleJar));
    }

    @Test
    public void testSame()
    {
        Path rpath = MavenTestingUtils.getTestResourcePathFile("resource.txt");
        Path epath = MavenTestingUtils.getTestResourcePathFile("example.jar");
        PathResource rPathResource = (PathResource)ResourceFactory.root().newResource(rpath);
        PathResource ePathResource = (PathResource)ResourceFactory.root().newResource(epath);

        assertThat(rPathResource.isSame(rPathResource), Matchers.is(true));
        assertThat(rPathResource.isSame(ePathResource), Matchers.is(false));

        PathResource ePathResource2 = null;
        try
        {
            Path epath2 = Files.createSymbolicLink(MavenTestingUtils.getTargetPath().resolve("testSame-symlink"), epath.getParent()).resolve("example.jar");
            ePathResource2 = (PathResource)ResourceFactory.root().newResource(epath2);
        }
        catch (Throwable th)
        {
            // Assume symbolic links are not supported
        }
        if (ePathResource2 != null)
        {
            assertThat(ePathResource.isSame(ePathResource2), Matchers.is(true));
            assertThat(ePathResource.equals(ePathResource2), Matchers.is(false));
        }
    }

    @Test
    public void testJarFileIsAliasFile(WorkDir workDir) throws IOException
    {
        Path testJar = workDir.getEmptyPathDir().resolve("test.jar");

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI jarUri = URIUtil.uriJarPrefix(testJar.toUri(), "!/");
        try (FileSystem zipfs = FileSystems.newFileSystem(jarUri, env))
        {
            Path root = zipfs.getPath("/");
            Files.writeString(root.resolve("test.txt"), "Contents of test.txt", StandardCharsets.UTF_8);
        }

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource archiveResource = resourceFactory.newResource(jarUri);

            Resource testText = archiveResource.resolve("test.txt");
            assertTrue(testText.exists());
            assertFalse(testText.isAlias());

            // Resolve to name, but different case
            testText = archiveResource.resolve("/TEST.TXT");
            assertFalse(testText.exists());

            // Resolve using path navigation
            testText = archiveResource.resolve("/foo/../test.txt");
            assertTrue(testText.exists());
            assertFalse(testText.isAlias());

            // Resolve using encoded characters
            testText = archiveResource.resolve("/test%2Etxt");
            assertTrue(testText.exists());
            assertFalse(testText.isAlias());
        }
    }

    @Test
    public void testJarFileIsAliasDirectory(WorkDir workDir) throws IOException
    {
        Path testJar = workDir.getEmptyPathDir().resolve("test.jar");

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI jarUri = URIUtil.uriJarPrefix(testJar.toUri(), "!/");
        try (FileSystem zipfs = FileSystems.newFileSystem(jarUri, env))
        {
            Path root = zipfs.getPath("/");
            Path dir = root.resolve("dir");
            Files.createDirectory(dir);
            Files.writeString(dir.resolve("test.txt"), "Contents of test.txt", StandardCharsets.UTF_8);
        }

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource archiveResource = resourceFactory.newResource(jarUri);

            Resource testText = archiveResource.resolve("/dir/test.txt");
            assertTrue(testText.exists());
            assertFalse(testText.isAlias());

            // Resolve file to name, but different case
            testText = archiveResource.resolve("/dir/TEST.TXT");
            assertFalse(testText.exists());
            testText = archiveResource.resolve("/DIR/test.txt");
            assertFalse(testText.exists());

            // Resolve file using path navigation
            testText = archiveResource.resolve("foo/../dir/test.txt");
            assertTrue(testText.exists());
            assertFalse(testText.isAlias());

            // Resolve file using encoded characters
            testText = archiveResource.resolve("dir/test%2Etxt");
            assertTrue(testText.exists());
            assertFalse(testText.isAlias());

            // Resolve directory to name, no slash
            Resource dirResource = archiveResource.resolve("/dir");
            assertTrue(dirResource.exists());
            assertFalse(dirResource.isAlias());

            // Resolve directory to name, with slash
            dirResource = archiveResource.resolve("/dir/");
            assertTrue(dirResource.exists());
            assertFalse(dirResource.isAlias());
        }
    }
}
