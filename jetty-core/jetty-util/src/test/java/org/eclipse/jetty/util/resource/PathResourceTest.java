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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ExtendWith(WorkDirExtension.class)
@Isolated
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

            assertThat("Resource.getName", resource.getName(), is(path.toAbsolutePath().toString()));
            assertThat("Resource.getFileName", resource.getFileName(), is("MANIFEST.MF"));
        }
    }

    @Test
    public void testDefaultFileSystemGetFile()
    {
        Path exampleJar = MavenTestingUtils.getTestResourcePathFile("example.jar");
        PathResource resource = (PathResource)ResourceFactory.root().newResource(exampleJar);

        Path path = resource.getPath();
        assertThat("File for default FileSystem", path, is(exampleJar));

        assertThat("Resource.getName", resource.getName(), is(exampleJar.toAbsolutePath().toString()));
        assertThat("Resource.getFileName", resource.getFileName(), is("example.jar"));
    }

    @Test
    public void testContains(WorkDir workDir) throws IOException
    {
        Path testPath = workDir.getEmptyPathDir();
        Path fooJar = testPath.resolve("foo.jar");
        Path barJar = testPath.resolve("bar.jar");

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");
        URI fooJarUri = URIUtil.uriJarPrefix(fooJar.toUri(), "!/");
        try (FileSystem zipfs = FileSystems.newFileSystem(fooJarUri, env))
        {
            Path root = zipfs.getPath("/");
            Path dir = root.resolve("deep/dir");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("foo.txt"), "Contents of foo.txt in foo.jar", StandardCharsets.UTF_8);
        }

        URI barJarUri = URIUtil.uriJarPrefix(barJar.toUri(), "!/");
        try (FileSystem zipfs = FileSystems.newFileSystem(barJarUri, env))
        {
            Path root = zipfs.getPath("/");
            Path dir = root.resolve("deep/dir");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("bar.txt"), "Contents of bar.txt in bar.jar", StandardCharsets.UTF_8);
        }

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource fooResource = resourceFactory.newResource(fooJarUri);
            Resource barResource = resourceFactory.newResource(barJarUri);

            Resource fooText = fooResource.resolve("deep/dir/foo.txt");
            assertTrue(fooResource.contains(fooText));

            Resource barText = barResource.resolve("deep/dir/bar.txt");
            assertTrue(barResource.contains(barText));

            assertFalse(fooResource.contains(barText));
            assertFalse(barResource.contains(fooText));
        }
    }

    @Test
    public void testGetPathTo(WorkDir workDir) throws IOException
    {
        Path testPath = workDir.getEmptyPathDir();
        Path fooJar = testPath.resolve("foo.jar");
        Path barJar = testPath.resolve("bar.jar");

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");
        URI fooJarUri = URIUtil.uriJarPrefix(fooJar.toUri(), "!/");
        try (FileSystem zipfs = FileSystems.newFileSystem(fooJarUri, env))
        {
            Path root = zipfs.getPath("/");
            Path dir = root.resolve("deep/dir");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("foo.txt"), "Contents of foo.txt in foo.jar", StandardCharsets.UTF_8);
        }

        URI barJarUri = URIUtil.uriJarPrefix(barJar.toUri(), "!/");
        try (FileSystem zipfs = FileSystems.newFileSystem(barJarUri, env))
        {
            Path root = zipfs.getPath("/");
            Path dir = root.resolve("deep/dir");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("bar.txt"), "Contents of bar.txt in bar.jar", StandardCharsets.UTF_8);
        }

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource fooResource = resourceFactory.newResource(fooJarUri);
            Resource barResource = resourceFactory.newResource(barJarUri);

            Resource fooText = fooResource.resolve("deep/dir/foo.txt");
            Path fooPathRel = fooResource.getPathTo(fooText);
            assertThat(fooPathRel.toString(), is("deep/dir/foo.txt"));

            Resource barText = barResource.resolve("deep/dir/bar.txt");
            Path barPathRel = barResource.getPathTo(barText);
            assertThat(barPathRel.toString(), is("deep/dir/bar.txt"));

            // Attempt to getPathTo cross Resource will return null.
            Path crossPathText = fooResource.getPathTo(barText);
            assertNull(crossPathText);
        }
    }

    @Test
    public void testJarMountNonExistent(WorkDir workDir) throws IOException
    {
        Path tmpPath = workDir.getEmptyPathDir();
        Path testJar = tmpPath.resolve("test.jar");

        URI jarUri = URIUtil.uriJarPrefix(testJar.toUri(), "!/");

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");
        try (FileSystem zipfs = FileSystems.newFileSystem(jarUri, env))
        {
            zipfs.getPath("/");
        }
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resBadDir = resourceFactory.newResource(jarUri.toASCIIString() + "does-not-exist/");
            assertNull(resBadDir);
            Resource resBadFile = resourceFactory.newResource(jarUri.toASCIIString() + "bad/file.txt");
            assertNull(resBadFile);

            if (resourceFactory instanceof ResourceFactoryInternals.Tracking tracking)
            {
                assertThat(tracking.getTrackingCount(), is(1));
            }
        }
    }

    @Test
    public void testMountsForSameJar(WorkDir workDir) throws IOException
    {
        Path tmpPath = workDir.getEmptyPathDir();
        Path testJar = tmpPath.resolve("test.jar");

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI jarUri = URIUtil.uriJarPrefix(testJar.toUri(), "!/");
        try (FileSystem zipfs = FileSystems.newFileSystem(jarUri, env))
        {
            Path root = zipfs.getPath("/");
            Files.writeString(root.resolve("one.txt"), "Contents of one.txt", StandardCharsets.UTF_8);

            Path dir = root.resolve("datainf");
            Files.createDirectory(dir);
            Files.writeString(dir.resolve("two.txt"), "Contents of two.txt", StandardCharsets.UTF_8);
        }

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource oneTxt = resourceFactory.newResource(jarUri.toASCIIString() + "one.txt");
            assertTrue(Resources.isReadableFile(oneTxt));
            Resource twoTxt = resourceFactory.newResource(jarUri.toASCIIString() + "datainf/two.txt");
            assertTrue(Resources.isReadableFile(twoTxt));

            if (resourceFactory instanceof ResourceFactoryInternals.Tracking tracking)
            {
                assertThat(tracking.getTrackingCount(), is(1));
            }
        }
    }

    @Test
    public void testMountsForSameJarDifferentResourceFactories(WorkDir workDir) throws IOException
    {
        Path tmpPath = workDir.getEmptyPathDir();
        Path testJar = tmpPath.resolve("test.jar");

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI jarUri = URIUtil.uriJarPrefix(testJar.toUri(), "!/");
        try (FileSystem zipfs = FileSystems.newFileSystem(jarUri, env))
        {
            Path root = zipfs.getPath("/");
            Files.writeString(root.resolve("one.txt"), "Contents of one.txt", StandardCharsets.UTF_8);

            Path dir = root.resolve("datainf");
            Files.createDirectory(dir);
            Files.writeString(dir.resolve("two.txt"), "Contents of two.txt", StandardCharsets.UTF_8);
        }

        assertThat(FileSystemPool.INSTANCE.mounts(), is(empty()));

        try (ResourceFactory.Closeable resourceFactory1 = ResourceFactory.closeable();
             ResourceFactory.Closeable resourceFactory2 = ResourceFactory.closeable())
        {
            Resource oneTxt = resourceFactory1.newResource(jarUri.toASCIIString() + "one.txt");
            assertTrue(Resources.isReadableFile(oneTxt));

            Resource oneTxt2 = resourceFactory1.newResource(jarUri.toASCIIString() + "one.txt");
            assertTrue(Resources.isReadableFile(oneTxt));

            Resource twoTxt = resourceFactory2.newResource(jarUri.toASCIIString() + "datainf/two.txt");
            assertTrue(Resources.isReadableFile(twoTxt));

            assertThat("Should see only 1 FS Mount", FileSystemPool.INSTANCE.mounts().size(), is(1));

            if (resourceFactory1 instanceof ResourceFactoryInternals.Tracking tracking)
            {
                assertThat(tracking.getTrackingCount(), is(1));
            }

            if (resourceFactory2 instanceof ResourceFactoryInternals.Tracking tracking)
            {
                assertThat(tracking.getTrackingCount(), is(1));
            }

            // Close Resource Factory 1
            resourceFactory1.close();

            if (resourceFactory1 instanceof ResourceFactoryInternals.Tracking tracking)
            {
                assertThat(tracking.getTrackingCount(), is(0));
            }

            // Resource one still works because factory 2 is holding filesystem open
            assertThat(IO.toString(oneTxt.newInputStream()), is("Contents of one.txt"));

            // should not be able to use closed ResourceFactory.Closable
            assertThrows(IllegalStateException.class, () -> resourceFactory1.newResource(jarUri.toASCIIString() + "one.txt"));

            assertThat("Should see only 1 FS Mount", FileSystemPool.INSTANCE.mounts().size(), is(1));

            Resource oneAlt = resourceFactory2.newResource(jarUri.toASCIIString() + "one.txt");
            assertTrue(Resources.isReadableFile(oneAlt));

            // Close Resource Factory 2
            resourceFactory2.close();

            // Neither Resource one nor two  works because filesystem is now closed
            assertThrows(ClosedFileSystemException.class, oneTxt::newInputStream);
            assertThrows(ClosedFileSystemException.class, oneTxt2::newInputStream);

            assertThat("Should see only 0 FS Mount", FileSystemPool.INSTANCE.mounts().size(), is(0));
        }
        finally
        {
            assertThat(FileSystemPool.INSTANCE.mounts(), is(empty()));
        }
    }

    @Test
    public void testJarFileIsAliasFile(WorkDir workDir) throws IOException
    {
        Path tmpPath = workDir.getEmptyPathDir();
        Path testJar = tmpPath.resolve("test.jar");

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
            assertTrue(testText.isAlias());
            assertThat("Resource.getName", testText.getName(), is("/test.txt"));
            assertThat("Resource.getFileName", testText.getFileName(), is("test.txt"));

            // Resolve using encoded characters
            testText = archiveResource.resolve("/test%2Etxt");
            assertTrue(testText.exists());
            assertFalse(testText.isAlias());
            assertThat("Resource.getName", testText.getName(), is("/test.txt"));
            assertThat("Resource.getFileName", testText.getFileName(), is("test.txt"));
        }
    }

    @Test
    public void testJarFileIsAliasDirectory(WorkDir workDir) throws IOException
    {
        Path tmpPath = workDir.getEmptyPathDir();
        boolean supportsUtf8Dir = false;
        Path testJar = tmpPath.resolve("test.jar");

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
            assertTrue(testText.isAlias(), "Should be an alias");

            // Resolve file using encoded characters
            testText = archiveResource.resolve("/dir/test%2Etxt");
            assertTrue(testText.exists());
            assertFalse(testText.isAlias());

            // Resolve file using extension-less directory
            testText = archiveResource.resolve("/dir./test.txt");
            assertFalse(testText.exists());

            // Resolve directory to name, no slash
            Resource dirResource = archiveResource.resolve("/dir");
            assertTrue(dirResource.exists());
            assertFalse(dirResource.isAlias());

            // Resolve content in dir to name, with slash
            Resource textResource = dirResource.resolve("/test.txt");
            assertTrue(textResource.exists());
            assertFalse(textResource.isAlias());
            assertTrue(Resources.isReadableFile(textResource));

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
        Path tmpPath = workDir.getEmptyPathDir();
        Path testJar = tmpPath.resolve("test.jar");

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
            assertFalse(resource.isAlias());
            resource = resourceFactory.newResource(file.toAbsolutePath());
            assertTrue(resource.exists());
            assertFalse(resource.isAlias());
            resource = resourceFactory.newResource(file.toUri());
            assertTrue(resource.exists());
            assertFalse(resource.isAlias());
            resource = resourceFactory.newResource(file.toUri().toString());
            assertTrue(resource.exists());
            assertFalse(resource.isAlias());
            resource = archiveResource.resolve("test.txt");
            assertTrue(resource.exists());
            assertFalse(resource.isAlias());

            // Test alias paths
            resource = resourceFactory.newResource(file0);
            assertTrue(resource.exists());
            assertTrue(resource.isAlias());
            resource = resourceFactory.newResource(file0.toAbsolutePath());
            assertTrue(resource.exists());
            assertTrue(resource.isAlias());
            resource = resourceFactory.newResource(file0.toUri());
            assertTrue(resource.exists());
            assertTrue(resource.isAlias());
            resource = resourceFactory.newResource(file0.toUri().toString());
            assertTrue(resource.exists());
            assertTrue(resource.isAlias());

            resource = archiveResource.resolve("test.txt\0");
            assertTrue(resource.exists());
            assertTrue(resource.isAlias());
        }
        catch (InvalidPathException e)
        {
            // this file system does allow null char ending filenames
            LOG.trace("IGNORED", e);
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

        Resource baseResource = new PathResource(tmpPath);
        assertThat(baseResource.getFileName(), is(tmpPath.getFileName().toString()));

        Resource dirResource = baseResource.resolve("foo-dir");
        assertThat(dirResource.getFileName(), is(dir.getFileName().toString()));

        Resource fileResource = dirResource.resolve("bar.txt");
        assertThat(fileResource.getFileName(), is(file.getFileName().toString()));
    }

    @Test
    public void testSymlink(WorkDir workDir) throws Exception
    {
        Path tmpPath = workDir.getEmptyPathDir();
        Path testJar = tmpPath.resolve("test.jar");
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

            assertThat("targetURI", resBar.getRealURI(), is(resFoo.getURI()));
            assertThat("uri.targetURI", resourceFactory.newResource(resBar.getURI()).getRealURI(), is(resFoo.getURI()));
            assertThat("file.targetURI", resourceFactory.newResource(resBar.getPath()).getRealURI(), is(resFoo.getURI()));
        }
        catch (InvalidPathException e)
        {
            // this file system does allow null char ending filenames
            LOG.trace("IGNORED", e);
        }
    }

    /**
     * Test to ensure that a symlink loop will not trip up the Resource.getAllResources() implementation.
     */
    @Test
    public void testGetAllResourcesSymlinkLoop(WorkDir workDir) throws Exception
    {
        Path testPath = workDir.getEmptyPathDir();

        Path base = testPath.resolve("base");
        Path deep = base.resolve("deep");
        Path deeper = deep.resolve("deeper");

        FS.ensureDirExists(deeper);

        Files.writeString(deeper.resolve("test.txt"), "This is the deeper TEST TXT", StandardCharsets.UTF_8);
        Files.writeString(base.resolve("foo.txt"), "This is the FOO TXT in the Base dir", StandardCharsets.UTF_8);

        boolean symlinkSupported;
        try
        {
            Path bar = deeper.resolve("bar");
            // Create symlink from ${base}/deep/deeper/bar/ to ${base}/deep/
            Files.createSymbolicLink(bar, deep);

            symlinkSupported = true;
        }
        catch (UnsupportedOperationException | FileSystemException e)
        {
            symlinkSupported = false;
        }

        assumeTrue(symlinkSupported, "Symlink not supported");

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newResource(base);

            Collection<Resource> allResources = resource.getAllResources();

            Resource[] expected = {
                resource.resolve("deep/"),
                resource.resolve("deep/deeper/"),
                resource.resolve("deep/deeper/bar/"),
                resource.resolve("deep/deeper/test.txt"),
                resource.resolve("foo.txt")
            };

            assertThat(allResources, containsInAnyOrder(expected));
        }
    }

    @Test
    public void testBrokenSymlink(WorkDir workDir) throws Exception
    {
        Path testDir = workDir.getEmptyPathDir();
        Path resourcePath = testDir.resolve("resource.txt");
        Path symlinkPath = null;
        IO.copy(MavenTestingUtils.getTestResourcePathFile("resource.txt").toFile(), resourcePath.toFile());
        boolean symlinkSupported;
        try
        {
            symlinkPath = Files.createSymbolicLink(testDir.resolve("symlink.txt"), resourcePath);
            symlinkSupported = true;
        }
        catch (UnsupportedOperationException | FileSystemException e)
        {
            symlinkSupported = false;
        }

        assumeTrue(symlinkSupported, "Symlink not supported");
        assertNotNull(symlinkPath);

        PathResource fileResource = new PathResource(resourcePath);
        assertTrue(fileResource.exists());
        PathResource symlinkResource = new PathResource(symlinkPath);
        assertTrue(symlinkResource.exists());

        // Their paths are not equal but not their canonical paths are.
        assertThat(fileResource.getPath(), not(equalTo(symlinkResource.getPath())));
        assertThat(fileResource.getPath(), equalTo(symlinkResource.getRealPath()));
        assertFalse(fileResource.isAlias());
        assertTrue(symlinkResource.isAlias());
        assertTrue(fileResource.exists());
        assertTrue(symlinkResource.exists());

        // After deleting file the Resources do not exist even though symlink file exists.
        assumeTrue(Files.deleteIfExists(resourcePath));
        assertFalse(fileResource.exists());
        assertFalse(symlinkResource.exists());

        // Re-create and test the resources now that the file has been deleted.
        fileResource = new PathResource(resourcePath);
        assertFalse(fileResource.exists());
        assertNull(fileResource.getRealPath());
        assertTrue(symlinkResource.isAlias());
        symlinkResource = new PathResource(symlinkPath);
        assertFalse(symlinkResource.exists());
        assertNull(symlinkResource.getRealPath());
        assertFalse(symlinkResource.isAlias());
    }

    @Test
    public void testResolveNavigation(WorkDir workDir) throws Exception
    {
        Path docroot = workDir.getEmptyPathDir();
        Path dir = docroot.resolve("dir");
        Files.createDirectory(dir);

        Path foo = docroot.resolve("foo");
        Files.createDirectory(foo);

        Path testText = dir.resolve("test.txt");
        Files.createFile(testText);

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource rootRes = resourceFactory.newResource(docroot);
            // Test navigation through a directory that doesn't exist
            Resource fileResViaBar = rootRes.resolve("bar/../dir/test.txt");
            if (OS.WINDOWS.isCurrentOs()) // windows allows navigation through a non-existent directory
                assertTrue(Resources.exists(fileResViaBar));
            else
                assertTrue(Resources.missing(fileResViaBar));

            // Test navigation through a directory that does exist
            Resource fileResViaFoo = rootRes.resolve("foo/../dir/test.txt");
            assertTrue(fileResViaFoo.exists());
        }
    }

    @Test
    public void testUnicodeResolve(WorkDir workDir) throws Exception
    {
        Path docroot = workDir.getEmptyPathDir();
        Path dir = docroot.resolve("dir");
        Files.createDirectory(dir);

        Path swedishText = dir.resolve("swedish-å.txt");
        Files.createFile(swedishText);

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource rootRes = resourceFactory.newResource(docroot);
            Resource dirRes = rootRes.resolve("dir/");
            // This is the heart of the test, we should support this
            Resource fileRes = dirRes.resolve("swedish-å.txt");
            assertTrue(fileRes.exists());
        }
    }

    @Test
    public void testIterable()
    {
        Path rpath = MavenTestingUtils.getTestResourcePathFile("resource.txt");
        Resource resource = ResourceFactory.root().newResource(rpath);
        int count = 0;
        for (Resource r : resource)
            count++;
        assertEquals(1, count);
    }
}
