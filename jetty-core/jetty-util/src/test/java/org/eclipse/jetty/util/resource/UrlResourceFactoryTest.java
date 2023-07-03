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

package org.eclipse.jetty.util.resource;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UrlResourceFactoryTest
{
    @Test
    @Tag("external")
    public void testHttps() throws IOException
    {
        URLResourceFactory urlResourceFactory = new URLResourceFactory();
        urlResourceFactory.setConnectTimeout(1000);

        ResourceFactory.registerResourceFactory("https", urlResourceFactory);
        try
        {
            Resource resource = ResourceFactory.root().newResource(URI.create("https://webtide.com/"));
            assertThat(resource, notNullValue());
            assertTrue(resource.exists());

            try (InputStream in = resource.newInputStream())
            {
                String result = IO.toString(in, StandardCharsets.UTF_8);
                assertThat(result, containsString("webtide.com"));
            }

            assertThat(resource.lastModified().toEpochMilli(), not(Instant.EPOCH));
            assertThat(resource.length(), not(-1));
            assertTrue(resource.isDirectory());
            assertThat(resource.getFileName(), is(""));

            Resource blogs = resource.resolve("blog/");
            assertThat(blogs, notNullValue());
            assertTrue(blogs.exists());
            assertThat(blogs.lastModified().toEpochMilli(), not(Instant.EPOCH));
            assertThat(blogs.length(), not(-1));
            assertTrue(blogs.isDirectory());
            assertThat(blogs.getFileName(), is(""));

            Resource favicon = resource.resolve("favicon.ico");
            assertThat(favicon, notNullValue());
            assertTrue(favicon.exists());
            assertThat(favicon.lastModified().toEpochMilli(), not(Instant.EPOCH));
            assertThat(favicon.length(), not(-1));
            assertFalse(favicon.isDirectory());
            assertThat(favicon.getFileName(), is("favicon.ico"));
        }
        finally
        {
            ResourceFactory.unregisterResourceFactory("https");
        }
    }

    @Test
    public void testFileUrl() throws Exception
    {
        Path path = MavenTestingUtils.getTestResourcePath("example.jar");
        int fileSize = (int)Files.size(path);
        URL fileUrl = path.toUri().toURL();
        URLResourceFactory urlResourceFactory = new URLResourceFactory();
        Resource resource = urlResourceFactory.newResource(fileUrl);

        assertThat(resource.isDirectory(), is(false));

        try (ReadableByteChannel channel = resource.newReadableByteChannel())
        {
            ByteBuffer buffer = ByteBuffer.allocate(fileSize);
            int read = channel.read(buffer);
            assertThat(read, is(fileSize));
        }
    }

    @Test
    public void testJarFileUrl() throws Exception
    {
        Path path = MavenTestingUtils.getTestResourcePath("example.jar");
        URL jarFileUrl = new URL("jar:" + path.toUri().toASCIIString() + "!/WEB-INF/web.xml");
        int fileSize = (int)fileSize(jarFileUrl);
        URLResourceFactory urlResourceFactory = new URLResourceFactory();
        Resource resource = urlResourceFactory.newResource(jarFileUrl);

        assertThat(resource.isDirectory(), is(false));

        try (ReadableByteChannel channel = resource.newReadableByteChannel())
        {
            ByteBuffer buffer = ByteBuffer.allocate(fileSize);
            int read = channel.read(buffer);
            assertThat(read, is(fileSize));
        }
    }

    @Test
    public void testResolveUri() throws MalformedURLException
    {
        Path path = MavenTestingUtils.getTestResourcePath("example.jar");
        URI jarFileUri = URI.create("jar:" + path.toUri().toASCIIString() + "!/WEB-INF/");

        URLResourceFactory urlResourceFactory = new URLResourceFactory();
        Resource resource = urlResourceFactory.newResource(jarFileUri.toURL());

        Resource webResource = resource.resolve("/web.xml");
        assertTrue(Resources.isReadableFile(webResource));
        URI expectedURI = URI.create(jarFileUri.toASCIIString() + "web.xml");
        assertThat(webResource.getURI(), is(expectedURI));
    }

    @Test
    public void testResolveUriNoPath() throws MalformedURLException
    {
        Path path = MavenTestingUtils.getTestResourcePath("example.jar");
        URI jarFileUri = URI.create("file:" + path.toUri().toASCIIString());

        URLResourceFactory urlResourceFactory = new URLResourceFactory();
        Resource resource = urlResourceFactory.newResource(jarFileUri.toURL());

        Resource webResource = resource.resolve("web.xml");
        assertThat(webResource.isDirectory(), is(false));

        URI expectedURI = URIUtil.correctFileURI(URI.create("file:" + path.toUri().toASCIIString() + "/web.xml"));
        assertThat(webResource.getURI(), is(expectedURI));
    }

    private static long fileSize(URL url) throws IOException
    {
        try (InputStream is = url.openStream())
        {
            long totalRead = 0;
            byte[] buffer = new byte[512];
            while (true)
            {
                int read = is.read(buffer);
                if (read == -1)
                    break;
                totalRead += read;
            }
            return totalRead;
        }
    }
}
