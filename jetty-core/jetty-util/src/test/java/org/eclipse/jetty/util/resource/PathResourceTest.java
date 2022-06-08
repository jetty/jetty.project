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
import java.net.URISyntaxException;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

public class PathResourceTest
{
    @Test
    public void testNonDefaultFileSystemGetInputStream() throws URISyntaxException, IOException
    {
        Path exampleJar = MavenTestingUtils.getTestResourcePathFile("example.jar");

        URI uri = new URI("jar", exampleJar.toUri().toASCIIString(), null);

        Map<String, Object> env = new HashMap<>();
        env.put("multi-release", "runtime");

        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            Path manifestPath = zipfs.getPath("/META-INF/MANIFEST.MF");
            assertThat(manifestPath, is(not(nullValue())));

            PathResource resource = (PathResource)Resource.newResource(manifestPath);

            try (InputStream inputStream = resource.getInputStream())
            {
                assertThat("InputStream", inputStream, is(not(nullValue())));
            }
        }
    }

    @Test
    public void testNonDefaultFileSystemGetReadableByteChannel() throws URISyntaxException, IOException
    {
        Path exampleJar = MavenTestingUtils.getTestResourcePathFile("example.jar");

        URI uri = new URI("jar", exampleJar.toUri().toASCIIString(), null);

        Map<String, Object> env = new HashMap<>();
        env.put("multi-release", "runtime");

        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            Path manifestPath = zipfs.getPath("/META-INF/MANIFEST.MF");
            assertThat(manifestPath, is(not(nullValue())));

            PathResource resource = (PathResource)Resource.newResource(manifestPath);

            try (ReadableByteChannel channel = resource.getReadableByteChannel())
            {
                assertThat("ReadableByteChannel", channel, is(not(nullValue())));
            }
        }
    }

    @Test
    public void testNonDefaultFileSystemGetFile() throws URISyntaxException, IOException
    {
        Path exampleJar = MavenTestingUtils.getTestResourcePathFile("example.jar");

        URI uri = new URI("jar", exampleJar.toUri().toASCIIString(), null);

        Map<String, Object> env = new HashMap<>();
        env.put("multi-release", "runtime");

        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            Path manifestPath = zipfs.getPath("/META-INF/MANIFEST.MF");
            assertThat(manifestPath, is(not(nullValue())));

            PathResource resource = (PathResource)Resource.newResource(manifestPath);
            Path path = resource.getPath();
            assertThat("File should be null for non-default FileSystem", path, is(nullValue()));
        }
    }

    @Test
    public void testDefaultFileSystemGetFile() throws Exception
    {
        Path exampleJar = MavenTestingUtils.getTestResourcePathFile("example.jar");
        PathResource resource = (PathResource)Resource.newResource(exampleJar);

        Path path = resource.getPath();
        assertThat("File for default FileSystem", path, is(exampleJar));
    }

    @Test
    public void testSame()
    {
        Path rpath = MavenTestingUtils.getTestResourcePathFile("resource.txt");
        Path epath = MavenTestingUtils.getTestResourcePathFile("example.jar");
        PathResource rPathResource = (PathResource)Resource.newResource(rpath);
        PathResource ePathResource = (PathResource)Resource.newResource(epath);

        assertThat(rPathResource.isSame(rPathResource), Matchers.is(true));
        assertThat(rPathResource.isSame(ePathResource), Matchers.is(false));

        PathResource ePathResource2 = null;
        try
        {
            Path epath2 = Files.createSymbolicLink(MavenTestingUtils.getTargetPath().resolve("testSame-symlink"), epath.getParent()).resolve("example.jar");
            ePathResource2 = (PathResource)Resource.newResource(epath2);
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
}
