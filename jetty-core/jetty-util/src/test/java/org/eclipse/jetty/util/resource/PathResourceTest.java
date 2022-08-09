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
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.util.URIUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class PathResourceTest
{
    private static final Logger LOG = LoggerFactory.getLogger(PathResourceTest.class);

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
    public void testSameViaSymlink()
    {
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Path rpath = MavenTestingUtils.getTestResourcePathFile("resource.txt");
            Path epath = MavenTestingUtils.getTestResourcePathFile("example.jar");

            PathResource rPathResource = (PathResource)resourceFactory.newResource(rpath);
            PathResource ePathResource = (PathResource)resourceFactory.newResource(epath);

            assertThat(rPathResource.isSame(rPathResource), is(true));
            assertThat(rPathResource.isSame(ePathResource), is(false));

            PathResource ePathResource2 = null;
            boolean symlinkSupported;
            try
            {
                Path sameSymlink = MavenTestingUtils.getTargetPath().resolve("testSame-symlink");
                Path epath2 = Files.createSymbolicLink(sameSymlink, epath.getParent()).resolve("example.jar");
                ePathResource2 = (PathResource)resourceFactory.newResource(epath2);
                symlinkSupported = true;
            }
            catch (Throwable th)
            {
                symlinkSupported = false;
            }

            assumeTrue(symlinkSupported, "Symlink not supported");
            assertThat(ePathResource.isSame(ePathResource2), is(true));
            assertThat(ePathResource.equals(ePathResource2), is(false));
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
        boolean supportsUtf8Dir = false;
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

            try
            {
                Path utf8Dir = root.resolve("bãm");
                Files.createDirectories(utf8Dir);
                System.out.println("bam = " + utf8Dir.toUri().toASCIIString());
                supportsUtf8Dir = true;
            }
            catch (InvalidPathException e)
            {
                LOG.debug("IGNORE", e);
            }
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
            testText = archiveResource.resolve("/foo/../dir/test.txt");
            assertTrue(testText.exists());
            assertFalse(testText.isAlias());

            // Resolve file using encoded characters
            testText = archiveResource.resolve("/dir/test%2Etxt");
            assertTrue(testText.exists());
            assertFalse(testText.isAlias());

            // Resolve file using extension-less directory
            testText = archiveResource.resolve("/dir./test.txt");
            assertFalse(testText.exists());
            assertFalse(testText.isAlias());

            // Resolve directory to name, no slash
            Resource dirResource = archiveResource.resolve("/dir");
            assertTrue(dirResource.exists());
            assertFalse(dirResource.isAlias());

            // Resolve directory to name, with slash
            dirResource = archiveResource.resolve("/dir/");
            assertTrue(dirResource.exists());
            assertFalse(dirResource.isAlias());

            if (supportsUtf8Dir)
            {
                // Resolve utf8 directory in raw
                dirResource = archiveResource.resolve("/bãm/");
                assertTrue(dirResource.exists());
                assertFalse(dirResource.isAlias());

                // Resolve utf8 directory encoded
                dirResource = archiveResource.resolve("/b%C3%A3m/");
                assertTrue(dirResource.exists());
                assertFalse(dirResource.isAlias());
            }
        }
    }

    @Test
    public void testNullCharEndingFilename(WorkDir workDir) throws Exception
    {
        Path testJar = workDir.getEmptyPathDir().resolve("test.jar");

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI jarUri = URIUtil.uriJarPrefix(testJar.toUri(), "!/");
        try (FileSystem zipfs = FileSystems.newFileSystem(jarUri, env))
        {
            Path root = zipfs.getPath("/");

            Path file0 = root.resolve("test.txt\0");
            assumeTrue(Files.exists(file0));
            // Skip if this file system does not get tricked by ending filenames with nul

            Files.writeString(file0, "Contents of test.txt%00", StandardCharsets.UTF_8);
            assertThat(file0 + " exists", Files.exists(file0), is(true));  // This could be an alias
        }
        catch (InvalidPathException e)
        {
            LOG.debug("IGNORE", e);
            assumeTrue(false, "FileSystem does not support null character");
        }

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource archiveResource = resourceFactory.newResource(jarUri);

            Path root = archiveResource.getPath();
            Path file = root.resolve("test.txt");
            Path file0 = root.resolve("test.txt\0");

            // Test not alias paths
            Resource resource = resourceFactory.newResource(file);
            assertTrue(resource.exists());
            assertNull(resource.getAlias());
            resource = resourceFactory.newResource(file.toAbsolutePath());
            assertTrue(resource.exists());
            assertNull(resource.getAlias());
            resource = resourceFactory.newResource(file.toUri());
            assertTrue(resource.exists());
            assertNull(resource.getAlias());
            resource = resourceFactory.newResource(file.toUri().toString());
            assertTrue(resource.exists());
            assertNull(resource.getAlias());
            resource = archiveResource.resolve("test.txt");
            assertTrue(resource.exists());
            assertNull(resource.getAlias());

            // Test alias paths
            resource = resourceFactory.newResource(file0);
            assertTrue(resource.exists());
            assertNotNull(resource.getAlias());
            resource = resourceFactory.newResource(file0.toAbsolutePath());
            assertTrue(resource.exists());
            assertNotNull(resource.getAlias());
            resource = resourceFactory.newResource(file0.toUri());
            assertTrue(resource.exists());
            assertNotNull(resource.getAlias());
            resource = resourceFactory.newResource(file0.toUri().toString());
            assertTrue(resource.exists());
            assertNotNull(resource.getAlias());

            resource = archiveResource.resolve("test.txt\0");
            assertTrue(resource.exists());
            assertNotNull(resource.getAlias());
        }
        catch (InvalidPathException e)
        {
            // this file system does allow null char ending filenames
            LOG.trace("IGNORED", e);
        }
    }

    @Test
    public void testSymlink(WorkDir workDir) throws Exception
    {
        Path testJar = workDir.getEmptyPathDir().resolve("test.jar");
        Path foo = null;
        Path bar = null;

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI jarUri = URIUtil.uriJarPrefix(testJar.toUri(), "!/");
        try (FileSystem zipfs = FileSystems.newFileSystem(jarUri, env))
        {
            Path root = zipfs.getPath("/");

            foo = root.resolve("foo");
            bar = root.resolve("bar");

            boolean symlinkSupported;
            try
            {
                Files.createFile(foo);
                Files.createSymbolicLink(bar, foo);
                symlinkSupported = true;
            }
            catch (UnsupportedOperationException | FileSystemException e)
            {
                symlinkSupported = false;
            }

            assumeTrue(symlinkSupported, "Symlink not supported");
        }
        catch (InvalidPathException e)
        {
            LOG.debug("IGNORE", e);
            assumeTrue(false, "FileSystem does not support null character");
        }

        assertNotNull(foo);
        assertNotNull(bar);

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource archiveResource = resourceFactory.newResource(jarUri);
            Path dir = archiveResource.getPath();

            Resource base = ResourceFactory.root().newResource(dir);
            Resource resFoo = base.resolve("foo");
            Resource resBar = base.resolve("bar");

            assertThat("resFoo.uri", resFoo.getURI(), is(foo.toUri()));

            // Access to the same resource, but via a symlink means that they are not equivalent
            assertThat("foo.equals(bar)", resFoo.equals(resBar), is(false));

            assertThat("resource.alias", resFoo.isAlias(), is(false));

            assertThat("resource.uri.alias", resourceFactory.newResource(resFoo.getURI()).isAlias(), is(false));
            assertThat("resource.file.alias", resourceFactory.newResource(resFoo.getPath()).isAlias(), is(false));

            assertThat("alias", resBar.getAlias(), is(resFoo.getURI()));
            assertThat("uri.alias", resourceFactory.newResource(resBar.getURI()).getAlias(), is(resFoo.getURI()));
            assertThat("file.alias", resourceFactory.newResource(resBar.getPath()).getAlias(), is(resFoo.getURI()));
        }
        catch (InvalidPathException e)
        {
            // this file system does allow null char ending filenames
            LOG.trace("IGNORED", e);
        }
    }
}
