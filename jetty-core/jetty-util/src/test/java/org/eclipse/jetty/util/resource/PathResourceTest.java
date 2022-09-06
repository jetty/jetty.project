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
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

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
}
