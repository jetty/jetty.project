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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
@Isolated
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
            assertThat(blogs.getFileName(), is("blog"));

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
    public void testGetFileName(WorkDir workDir) throws IOException
    {
        Path tmpPath = workDir.getEmptyPathDir();
        Path dir = tmpPath.resolve("foo-dir");
        FS.ensureDirExists(dir);
        Path file = dir.resolve("bar.txt");
        Files.writeString(file, "This is bar.txt", StandardCharsets.UTF_8);

        URLResourceFactory urlResourceFactory = new URLResourceFactory();

        Resource baseResource = urlResourceFactory.newResource(tmpPath);
        assertThat(baseResource.getFileName(), endsWith(""));

        Resource dirResource = baseResource.resolve("foo-dir/");
        assertThat(dirResource.getFileName(), endsWith("foo-dir"));

        Resource fileResource = dirResource.resolve("bar.txt");
        assertThat(fileResource.getFileName(), endsWith("bar.txt"));
    }

    @Test
    public void testInputStreamCleanedUp() throws Exception
    {
        Path path = MavenTestingUtils.getTestResourcePath("example.jar");
        URI jarFileUri = URI.create("jar:" + path.toUri().toASCIIString() + "!/WEB-INF/");

        AtomicInteger cleanedRefCount = new AtomicInteger();
        URLResourceFactory urlResourceFactory = new URLResourceFactory();
        URLResourceFactory.ON_SWEEP_LISTENER = in ->
        {
            if (in != null)
                cleanedRefCount.incrementAndGet();
        };
        Resource resource = urlResourceFactory.newResource(jarFileUri.toURL());
        Resource webResource = resource.resolve("/web.xml");
        assertTrue(webResource.exists());
        resource = null;
        webResource = null;
        assertThat(resource, nullValue());
        assertThat(webResource, nullValue());

        await().atMost(5, TimeUnit.SECONDS).until(() ->
        {
            System.gc();
            return cleanedRefCount.get() > 0;
        });
    }

    @Test
    public void testTakenInputStreamNotClosedOnCleanUp() throws Exception
    {
        Path path = MavenTestingUtils.getTestResourcePath("example.jar");
        URI jarFileUri = URI.create("jar:" + path.toUri().toASCIIString() + "!/WEB-INF/");

        AtomicInteger cleanedRefCount = new AtomicInteger();
        URLResourceFactory urlResourceFactory = new URLResourceFactory();
        URLResourceFactory.ON_SWEEP_LISTENER = in -> cleanedRefCount.incrementAndGet();
        Resource resource = urlResourceFactory.newResource(jarFileUri.toURL());
        Resource webResource = resource.resolve("/web.xml");
        assertTrue(webResource.exists());
        InputStream in = webResource.newInputStream();
        resource = null;
        webResource = null;
        assertThat(resource, nullValue());
        assertThat(webResource, nullValue());
        await().atMost(5, TimeUnit.SECONDS).until(() ->
        {
            System.gc();
            return cleanedRefCount.get() > 0;
        });

        String webXml = IO.toString(in);
        assertThat(webXml, is("WEB-INF/web.xml"));
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

        ByteBuffer buffer = BufferUtil.toBuffer(resource, false);
        assertThat(buffer.remaining(), is(fileSize));
    }

    @Test
    public void testIsDirectory()
    {
        URLResourceFactory urlResourceFactory = new URLResourceFactory();
        Resource resource = urlResourceFactory.newResource("file:/does/not/exist/ends/with/a/slash/");
        assertThat(resource.isDirectory(), is(false));
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

        ByteBuffer buffer = BufferUtil.toBuffer(resource, false);
        assertThat(buffer.remaining(), is(fileSize));
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

    /**
     * Test resolve where the input path is for a parent directory location.
     * (An attempt to break out of the base resource)
     */
    @Test
    public void testResolveUriParent() throws MalformedURLException
    {
        Path path = MavenTestingUtils.getTestResourcePath("example.jar");
        URI jarFileUri = URI.create("jar:" + path.toUri().toASCIIString() + "!/WEB-INF/");

        URLResourceFactory urlResourceFactory = new URLResourceFactory();
        Resource baseResource = urlResourceFactory.newResource(jarFileUri.toURL());
        assertThrows(IllegalArgumentException.class, () ->
        {
            baseResource.resolve("../META-INF/MANIFEST.MF");
        });
    }

    /**
     * Test resolve where the base URI has no path.
     */
    @Test
    public void testResolveNestedUriNoPath() throws MalformedURLException
    {
        Path path = MavenTestingUtils.getTestResourcePath("example.jar");
        URI jarFileUri = URI.create("file:" + path.toUri().toASCIIString());
        // We now have `file:file:/path/to/example.jar` URI (with two nested "file" schemes)
        // The first `file` will be opaque and contain no path.

        URLResourceFactory urlResourceFactory = new URLResourceFactory();
        Resource resource = urlResourceFactory.newResource(jarFileUri.toURL());
        assertThat("file:file:/path/to/example.jar cannot exist", resource.exists(), is(false));
        assertThat(resource.isDirectory(), is(false));

        Resource webResource = resource.resolve("/WEB-INF/web.xml");
        assertThat("resource /path/to/example.jar/WEB-INF/web.xml doesn't exist", webResource.exists(), is(false));
        assertThat(webResource.isDirectory(), is(false));

        URI expectedURI = URIUtil.correctURI(URI.create("file:" + path.toUri().toASCIIString() + "/WEB-INF/web.xml"));
        assertThat(webResource.getURI(), is(expectedURI));
    }

    /**
     * Test resolve where the base URI has no path.
     */
    @Test
    public void testResolveFromFile() throws MalformedURLException
    {
        Path path = MavenTestingUtils.getTestResourcePath("example.jar");
        URI jarFileUri = path.toUri();

        URLResourceFactory urlResourceFactory = new URLResourceFactory();
        Resource baseResource = urlResourceFactory.newResource(jarFileUri.toURL());
        assertThat(baseResource.exists(), is(true));
        assertThat(baseResource.isDirectory(), is(false));

        Resource webResource = baseResource.resolve("/WEB-INF/web.xml");
        assertThat("resource /path/to/example.jar/WEB-INF/web.xml doesn't exist", webResource.exists(), is(false));
        assertThat(webResource.isDirectory(), is(false));

        URI expectedURI = URIUtil.correctURI(URI.create(path.toUri().toASCIIString() + "/WEB-INF/web.xml"));
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
