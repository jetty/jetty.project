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

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.BufferUtil;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.condition.OS.LINUX;
import static org.junit.jupiter.api.condition.OS.MAC;
import static org.junit.jupiter.api.condition.OS.WINDOWS;

@ExtendWith(WorkDirExtension.class)
public class FileSystemResourceTest
{
    public WorkDir workDir;

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

    private Matcher<Resource> isNotAlias()
    {
        return new BaseMatcher<>()
        {
            @Override
            public boolean matches(Object item)
            {
                return !((Resource)item).isAlias();
            }

            @Override
            public void describeTo(Description description)
            {
                description.appendText("isAlias() should return false");
            }

            @Override
            public void describeMismatch(Object item, Description description)
            {
                description.appendText("was ").appendValue(((Resource)item).getTargetURI());
            }
        };
    }

    private Matcher<Resource> isTargetFor(final Resource resource)
    {
        return new BaseMatcher<>()
        {
            @Override
            public boolean matches(Object item)
            {
                final Resource ritem = (Resource)item;
                return ritem.getTargetURI().equals(resource.getTargetURI());
            }

            @Override
            public void describeTo(Description description)
            {
                description.appendText("getTargetURI should return ").appendValue(resource.getURI());
            }

            @Override
            public void describeMismatch(Object item, Description description)
            {
                description.appendText("was ").appendValue(((Resource)item).getTargetURI());
            }
        };
    }

    @Test
    public void testNonAbsoluteURI() throws Exception
    {
        Resource resource = ResourceFactory.root().newResource(new URI("path/to/resource"));
        assertThat(resource, notNullValue());
        assertThat(resource.getURI().toString(), startsWith("file:"));
        assertThat(resource.getURI().toString(), endsWith("/path/to/resource"));

        resource =  ResourceFactory.root().newResource(new URI("/path/to/resource"));
        assertThat(resource, notNullValue());
        assertThat(resource.getURI().toString(), is("file:///path/to/resource"));
    }

    @Test
    public void testNotFileURI()
    {
        assertThrows(FileSystemNotFoundException.class,
            () -> ResourceFactory.root().newResource(new URI("https://www.eclipse.org/jetty/")));
    }

    @Test
    @EnabledOnOs(WINDOWS)
    public void testBogusFilenameWindows()
    {
        // "CON" is a reserved name under windows
        assertThrows(IllegalArgumentException.class,
            () -> ResourceFactory.root().newResource(new URI("file://CON")));
    }

    @Test
    @EnabledOnOs({LINUX, MAC})
    public void testBogusFilenameUnix()
    {
        // A windows path is invalid under unix
        assertThrows(IllegalArgumentException.class, () -> ResourceFactory.root().newResource(URI.create("file://Z:/:")));
    }

    @Test
    public void testNewResourceWithSpace() throws Exception
    {
        Path dir = workDir.getPath().normalize().toRealPath();

        Path baseDir = dir.resolve("base with spaces");
        FS.ensureDirExists(baseDir.toFile());

        Path subdir = baseDir.resolve("sub");
        FS.ensureDirExists(subdir.toFile());

        URL baseUrl = baseDir.toUri().toURL();

        assertThat("url.protocol", baseUrl.getProtocol(), is("file"));

        Resource base = ResourceFactory.root().newResource(baseUrl);
        Resource sub = base.resolve("sub");
        assertThat("sub/.isDirectory", sub.isDirectory(), is(true));

        Resource tmp = sub.resolve("/tmp");
        assertThat("No root", tmp.exists(), is(false));
    }

    @Test
    public void testResolvePathClass() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();

        Path subdir = dir.resolve("sub");
        FS.ensureDirExists(subdir.toFile());

        Resource base = ResourceFactory.root().newResource(dir);
        Resource sub = base.resolve("sub");
        assertThat("sub.isDirectory", sub.isDirectory(), is(true));

        Resource tmp = sub.resolve("/tmp");
        assertThat("No root", tmp.exists(), is(false));
    }

    @Test
    public void testResolveRootPath() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Path subdir = dir.resolve("sub");
        Files.createDirectories(subdir);

        String readableRootDir = findAnyDirectoryOffRoot(dir.getFileSystem());
        assumeTrue(readableRootDir != null, "Readable Root Dir found");

        Resource base = ResourceFactory.root().newResource(dir);
        Resource sub = base.resolve("sub");
        assertThat("sub", sub.isDirectory(), is(true));

        try
        {
            Resource rrd = sub.resolve(readableRootDir);
            // valid path for unix and OSX
            assertThat("Readable Root Dir", rrd.exists(), is(false));
        }
        catch (InvalidPathException e)
        {
            // valid path on Windows
        }
    }

    @Test
    public void testAccessUniCodeFile() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();

        String readableRootDir = findAnyDirectoryOffRoot(dir.getFileSystem());
        assumeTrue(readableRootDir != null, "Readable Root Dir found");

        Path subdir = dir.resolve("sub");
        Files.createDirectories(subdir);

        touchFile(subdir.resolve("swedish-å.txt"), "hi a-with-circle");
        touchFile(subdir.resolve("swedish-ä.txt"), "hi a-with-two-dots");
        touchFile(subdir.resolve("swedish-ö.txt"), "hi o-with-two-dots");

        Resource base = ResourceFactory.root().newResource(subdir);

        // Use decoded Unicode
        Resource refD1 = base.resolve("swedish-å.txt");
        Resource refD2 = base.resolve("swedish-ä.txt");
        Resource refD3 = base.resolve("swedish-ö.txt");

        assertTrue(refD1.exists(), "Ref D1 exists");
        assertTrue(refD2.exists(), "Ref D2 exists");
        assertTrue(refD3.exists(), "Ref D3 exists");

        // Use encoded Unicode
        Resource refE1 = base.resolve("swedish-%C3%A5.txt"); // swedish-å.txt
        Resource refE2 = base.resolve("swedish-%C3%A4.txt"); // swedish-ä.txt
        Resource refE3 = base.resolve("swedish-%C3%B6.txt"); // swedish-ö.txt

        assertTrue(refE1.exists(), "Ref E1 exists");
        assertTrue(refE2.exists(), "Ref E2 exists");
        assertTrue(refE3.exists(), "Ref E3 exists");

        if (LINUX.isCurrentOs())
        {
            assertThat("Ref A1 alias", refE1.isAlias(), is(false));
            assertThat("Ref A2 alias", refE2.isAlias(), is(false));
            assertThat("Ref O1 alias", refE3.isAlias(), is(false));
        }

        assertThat("Ref A1 contents", toString(refE1), is("hi a-with-circle"));
        assertThat("Ref A2 contents", toString(refE2), is("hi a-with-two-dots"));
        assertThat("Ref O1 contents", toString(refE3), is("hi o-with-two-dots"));
    }

    /**
     * Best effort discovery a directory off the provided FileSystem.
     * @param fs the provided FileSystem.
     * @return a directory off the root FileSystem.
     */
    private String findAnyDirectoryOffRoot(FileSystem fs)
    {
        // look for anything that's a directory off of any root paths of the provided FileSystem
        for (Path rootDir : fs.getRootDirectories())
        {
            try (DirectoryStream<Path> dir = Files.newDirectoryStream(rootDir))
            {
                for (Path entry : dir)
                {
                    if (Files.isDirectory(entry) && !Files.isHidden(entry) && !entry.getFileName().toString().contains("$"))
                    {
                        return entry.toAbsolutePath().toString();
                    }
                }
            }
            catch (Exception ignored)
            {
                // Don't care if there's an error, we'll just try the next possible root directory.
                // if no directories are found, then that means the users test environment is
                // super odd, and we cannot continue these tests anyway, and are skipped with an assume().
            }
        }

        return null;
    }

    @Test
    public void testIsContainedIn() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);
        Path foo = dir.resolve("foo");
        Files.createFile(foo);

        Resource base = ResourceFactory.root().newResource(dir);
        Resource res = base.resolve("foo");
        assertThat("is contained in", res.isContainedIn(base), is(true));
    }

    @Test
    public void testIsDirectory() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);
        Path foo = dir.resolve("foo");
        Files.createFile(foo);

        Path subdir = dir.resolve("sub");
        Files.createDirectories(subdir);

        Resource base = ResourceFactory.root().newResource(dir);
        Resource res = base.resolve("foo");
        assertThat("foo.isDirectory", res.isDirectory(), is(false));

        Resource sub = base.resolve("sub");
        assertThat("sub/.isDirectory", sub.isDirectory(), is(true));
    }

    @Test
    public void testLastModified() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Path file = workDir.getPathFile("foo");
        Files.createFile(file);

        Instant expected = Files.getLastModifiedTime(file).toInstant();

        Resource base = ResourceFactory.root().newResource(dir);
        Resource res = base.resolve("foo");
        assertThat("foo.lastModified", res.lastModified(), is(expected));
    }

    @Test
    public void testLastModifiedNotExists()
    {
        Path dir = workDir.getEmptyPathDir();

        Resource base = ResourceFactory.root().newResource(dir);
        Resource res = base.resolve("foo");
        assertThat("foo.lastModified", res.lastModified(), is(Instant.EPOCH));
    }

    @Test
    public void testLength() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path file = dir.resolve("foo");
        touchFile(file, "foo");

        long expected = Files.size(file);

        Resource base = ResourceFactory.root().newResource(dir);
        Resource res = base.resolve("foo");
        assertThat("foo.length", res.length(), is(expected));
    }

    @Test
    public void testLengthNotExists() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Resource base = ResourceFactory.root().newResource(dir);
        Resource res = base.resolve("foo");
        assertThat("foo.length", res.length(), is(0L));
    }

    @Test
    public void testDelete() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);
        Path file = dir.resolve("foo");
        Files.createFile(file);

        Resource base = ResourceFactory.root().newResource(dir);
        // Is it there?
        Resource res = base.resolve("foo");
        assertThat("foo.exists", res.exists(), is(true));
        // delete it
        Files.delete(res.getPath());
        // is it there?
        assertThat("foo.exists", res.exists(), is(false));
    }

    @Test
    public void testDeleteNotExists() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Resource base = ResourceFactory.root().newResource(dir);
        // Is it there?
        Resource res = base.resolve("foo");
        assertThat("foo.exists", res.exists(), is(false));
        // delete it
        Files.deleteIfExists(res.getPath());
        // is it there?
        assertThat("foo.exists", res.exists(), is(false));
    }

    @Test
    public void testName() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        String expected = dir.toAbsolutePath().toString();

        Resource base = ResourceFactory.root().newResource(dir);
        assertThat("base.name", base.getName(), is(expected));
    }

    @Test
    public void testFileName() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        String expected = dir.getFileName().toString();

        Resource base = ResourceFactory.root().newResource(dir);
        assertThat("base.filename", base.getFileName(), is(expected));
    }

    @Test
    public void testInputStream() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path file = dir.resolve("foo");
        String content = "Foo is here";
        touchFile(file, content);

        Resource base = ResourceFactory.root().newResource(dir);
        Resource foo = base.resolve("foo");
        try (InputStream stream = foo.newInputStream();
             InputStreamReader reader = new InputStreamReader(stream);
             StringWriter writer = new StringWriter())
        {
            IO.copy(reader, writer);
            assertThat("Stream", writer.toString(), is(content));
        }
    }

    @Test
    public void testReadableByteChannel() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path file = dir.resolve("foo");
        String content = "Foo is here";

        try (StringReader reader = new StringReader(content);
             BufferedWriter writer = Files.newBufferedWriter(file))
        {
            IO.copy(reader, writer);
        }

        Resource base = ResourceFactory.root().newResource(dir);
        Resource foo = base.resolve("foo");
        try (ReadableByteChannel channel = foo.newReadableByteChannel())
        {
            ByteBuffer buf = ByteBuffer.allocate(256);
            channel.read(buf);
            buf.flip();
            String actual = BufferUtil.toUTF8String(buf);
            assertThat("ReadableByteChannel content", actual, is(content));
        }
    }

    @Test
    public void testGetURI() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path file = dir.resolve("foo");
        Files.createFile(file);

        URI expected = file.toUri();

        Resource base = ResourceFactory.root().newResource(dir);
        Resource foo = base.resolve("foo");
        assertThat("getURI", foo.getURI(), is(expected));
    }

    @Test
    public void testList() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Files.createFile(dir.resolve("foo"));
        Files.createFile(dir.resolve("bar"));
        Files.createDirectories(dir.resolve("tick"));
        Files.createDirectories(dir.resolve("tock"));

        String[] expectedFileNames = {
            "foo",
            "bar",
            "tick",
            "tock"
        };

        Resource base = ResourceFactory.root().newResource(dir);
        List<Resource> listing = base.list();
        List<String> actualFileNames = listing.stream().map(Resource::getFileName).toList();

        assertThat(actualFileNames, containsInAnyOrder(expectedFileNames));
    }

    @Test
    public void testSymlink() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();

        Path foo = dir.resolve("foo");
        Path bar = dir.resolve("bar");

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

        Resource base = ResourceFactory.root().newResource(dir);
        Resource resFoo = base.resolve("foo");
        Resource resBar = base.resolve("bar");

        assertThat("resFoo.uri", resFoo.getURI(), is(foo.toUri()));

        // Access to the same resource, but via a symlink means that they are not equivalent
        assertThat("foo.equals(bar)", resFoo.equals(resBar), is(false));

        assertThat("resource.targetURI", resFoo, isNotAlias());
        assertThat("resource.uri.targetURI", ResourceFactory.root().newResource(resFoo.getURI()), isNotAlias());
        assertThat("resource.file.targetURI", ResourceFactory.root().newResource(resFoo.getPath()), isNotAlias());

        assertThat("targetURI", resBar, isTargetFor(resFoo));
        assertThat("uri.targetURI", ResourceFactory.root().newResource(resBar.getURI()), isTargetFor(resFoo));
        assertThat("file.targetURI", ResourceFactory.root().newResource(resBar.getPath()), isTargetFor(resFoo));
    }

    @Test
    public void testNonExistantSymlink() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path foo = dir.resolve("foo");
        Path bar = dir.resolve("bar");

        boolean symlinkSupported;
        try
        {
            Files.createSymbolicLink(bar, foo);
            symlinkSupported = true;
        }
        catch (UnsupportedOperationException | FileSystemException e)
        {
            symlinkSupported = false;
        }

        assumeTrue(symlinkSupported, "Symlink not supported");

        Resource base = ResourceFactory.root().newResource(dir);
        Resource resFoo = base.resolve("foo");
        Resource resBar = base.resolve("bar");

        assertThat("resFoo.uri", resFoo.getURI(), is(foo.toUri()));

        // Access to the same resource, but via a symlink means that they are not equivalent
        assertThat("foo.equals(bar)", resFoo.equals(resBar), is(false));

        assertThat("resource.targetURI", resFoo, isNotAlias());
        assertThat("resource.uri.targetURI", ResourceFactory.root().newResource(resFoo.getURI()), isNotAlias());
        assertThat("resource.file.targetURI", ResourceFactory.root().newResource(resFoo.getPath()), isNotAlias());

        // Even though resBar contains symlink it is not an alias because the file does not exist.
        assertThat("targetURI", resBar, isNotAlias());
        assertThat("uri.targetURI", ResourceFactory.root().newResource(resBar.getURI()), isNotAlias());
        assertThat("file.targetURI", ResourceFactory.root().newResource(resBar.getPath()), isNotAlias());
    }

    @Test
    public void testCaseInsensitiveAlias() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);
        Path path = dir.resolve("file");
        Files.createFile(path);

        Resource base = ResourceFactory.root().newResource(dir);
        // Reference to actual resource that exists
        Resource resource = base.resolve("file");

        assertThat("resource.targetURI", resource, isNotAlias());
        assertThat("resource.uri.targetURI", ResourceFactory.root().newResource(resource.getURI()), isNotAlias());
        assertThat("resource.file.targetURI", ResourceFactory.root().newResource(resource.getPath()), isNotAlias());

        // On some case insensitive file systems, lets see if an alternate
        // case for the filename results in an alias reference
        Resource alias = base.resolve("FILE");
        if (alias.exists())
        {
            // If it exists, it must be an alias
            assertThat("targetURI", alias, isTargetFor(resource));
            assertThat("targetURI.uri", ResourceFactory.root().newResource(alias.getURI()), isTargetFor(resource));
            assertThat("targetURI.file", ResourceFactory.root().newResource(alias.getPath()), isTargetFor(resource));
        }
    }

    /**
     * Test for Windows feature that exposes 8.3 filename references
     * for long filenames.
     * <p>
     * See: http://support.microsoft.com/kb/142982
     *
     * @throws Exception failed test
     */
    @Test
    @EnabledOnOs(WINDOWS)
    public void testCase8dot3Alias() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path path = dir.resolve("TextFile.Long.txt");
        Files.createFile(path);

        Resource base = ResourceFactory.root().newResource(dir);
        // Long filename
        Resource resource = base.resolve("TextFile.Long.txt");

        assertThat("resource.targetURI", resource, isNotAlias());
        assertThat("resource.uri.targetURI", ResourceFactory.root().newResource(resource.getURI()), isNotAlias());
        assertThat("resource.file.targetURI", ResourceFactory.root().newResource(resource.getPath()), isNotAlias());

        // On some versions of Windows, the long filename can be referenced
        // via a short 8.3 equivalent filename.
        Resource alias = base.resolve("TEXTFI~1.TXT");
        if (alias.exists())
        {
            // If it exists, it must be an alias
            assertThat("targetURI", alias, isTargetFor(resource));
            assertThat("targetURI.uri", ResourceFactory.root().newResource(alias.getURI()), isTargetFor(resource));
            assertThat("targetURI.file", ResourceFactory.root().newResource(alias.getPath()), isTargetFor(resource));
        }
    }

    /**
     * NTFS Alternative Data / File Streams.
     * <p>
     * See: http://msdn.microsoft.com/en-us/library/windows/desktop/aa364404(v=vs.85).aspx
     *
     * @throws Exception failed test
     */
    @Test
    @EnabledOnOs(WINDOWS)
    public void testNTFSFileStreamAlias() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path path = dir.resolve("testfile");
        Files.createFile(path);

        Resource base = ResourceFactory.root().newResource(dir);
        Resource resource = base.resolve("testfile");

        assertThat("resource.targetURI", resource, isNotAlias());
        assertThat("resource.uri.targetURI", ResourceFactory.root().newResource(resource.getURI()), isNotAlias());
        assertThat("resource.file.targetURI", ResourceFactory.root().newResource(resource.getPath()), isNotAlias());

        try
        {
            // Attempt to reference same file, but via NTFS simple stream
            Resource alias = base.resolve("testfile:stream");
            if (alias.exists())
            {
                // If it exists, it must be an alias
                assertThat("resource.targetURI", alias, isTargetFor(resource));
                assertThat("resource.uri.targetURI", ResourceFactory.root().newResource(alias.getURI()), isTargetFor(resource));
                assertThat("resource.file.targetURI", ResourceFactory.root().newResource(alias.getPath()), isTargetFor(resource));
            }
        }
        catch (InvalidPathException e)
        {
            assumeTrue(false, "NTFS simple streams not supported");
        }
    }

    /**
     * NTFS Alternative Data / File Streams.
     * <p>
     * See: http://msdn.microsoft.com/en-us/library/windows/desktop/aa364404(v=vs.85).aspx
     *
     * @throws Exception failed test
     */
    @Test
    @EnabledOnOs(WINDOWS)
    public void testNTFSFileDataStreamAlias() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path path = dir.resolve("testfile");
        Files.createFile(path);

        Resource base = ResourceFactory.root().newResource(dir);
        Resource resource = base.resolve("testfile");

        assertThat("resource.targetURI", resource, isNotAlias());
        assertThat("resource.uri.targetURI", ResourceFactory.root().newResource(resource.getURI()), isNotAlias());
        assertThat("resource.file.targetURI", ResourceFactory.root().newResource(resource.getPath()), isNotAlias());

        try
        {
            // Attempt to reference same file, but via NTFS DATA stream
            Resource alias = base.resolve("testfile::$DATA");
            if (alias.exists())
            {
                assumeTrue(alias.getURI().getScheme().equals("file"));

                // If it exists, it must be an alias
                assertThat("resource.targetURI", alias, isTargetFor(resource));
                assertThat("resource.uri.targetURI", ResourceFactory.root().newResource(alias.getURI()), isTargetFor(resource));
                assertThat("resource.file.targetURI", ResourceFactory.root().newResource(alias.getPath()), isTargetFor(resource));
            }
        }
        catch (InvalidPathException e)
        {
            assumeTrue(false, "NTFS $DATA streams not supported");
        }
    }

    /**
     * NTFS Alternative Data / File Streams.
     * <p>
     * See: http://msdn.microsoft.com/en-us/library/windows/desktop/aa364404(v=vs.85).aspx
     *
     * @throws Exception failed test
     */
    @Test
    @EnabledOnOs(WINDOWS)
    public void testNTFSFileEncodedDataStreamAlias() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path path = dir.resolve("testfile");
        Files.createFile(path);

        Resource base = ResourceFactory.root().newResource(dir);
        Resource resource = base.resolve("testfile");

        assertThat("resource.targetURI", resource, isNotAlias());
        assertThat("resource.uri.targetURI", ResourceFactory.root().newResource(resource.getURI()), isNotAlias());
        assertThat("resource.file.targetURI", ResourceFactory.root().newResource(resource.getPath()), isNotAlias());

        try
        {
            // Attempt to reference same file, but via NTFS DATA stream (encoded addPath version)
            Resource alias = base.resolve("testfile::%24DATA");
            if (alias.exists())
            {
                // If it exists, it must be an alias
                assertThat("resource.targetURI", alias, isTargetFor(resource));
                assertThat("resource.uri.targetURI", ResourceFactory.root().newResource(alias.getURI()), isTargetFor(resource));
                assertThat("resource.file.targetURI", ResourceFactory.root().newResource(alias.getPath()), isTargetFor(resource));
            }
        }
        catch (InvalidPathException e)
        {
            assumeTrue(false, "NTFS $DATA streams not supported");
        }
    }

    @Test
    public void testSemicolon() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();

        try
        {
            // attempt to create file
            Path foo = dir.resolve("foo;");
            Files.createFile(foo);
        }
        catch (InvalidPathException e)
        {
            assumeTrue(false, "Unable to create file with semicolon");
        }

        Resource base = ResourceFactory.root().newResource(dir);
        Resource res = base.resolve("foo;");
        assertThat("Target URI: " + res, res, isNotAlias());
    }

    @Test
    public void testSingleQuote() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        try
        {
            // attempt to create file
            Path foo = dir.resolve("foo' bar");
            Files.createFile(foo);
        }
        catch (InvalidPathException e)
        {
            assumeTrue(false, "Unable to create file with single quote");
        }

        Resource base = ResourceFactory.root().newResource(dir);
        // access via encoded form
        Resource test = base.resolve("foo'%20bar");
        assertTrue(test.exists());
        // access via raw form
        test = base.resolve("foo' bar");
        assertTrue(test.exists());
    }

    @Test
    public void testSingleBackTick() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        try
        {
            // attempt to create file
            Path foo = dir.resolve("foo` bar");
            Files.createFile(foo);
        }
        catch (InvalidPathException e)
        {
            assumeTrue(false, "Unable to create file with single back tick");
        }

        Resource base = ResourceFactory.root().newResource(dir);
        // access via encoded form
        Resource file = base.resolve("foo%60%20bar");
        assertTrue(file.exists());
        // access via raw form
        file = base.resolve("foo` bar");
        assertTrue(file.exists());
    }

    @Test
    public void testBrackets() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        try
        {
            // attempt to create file
            Path foo = dir.resolve("foo[1]");
            Files.createFile(foo);
        }
        catch (InvalidPathException e)
        {
            assumeTrue(false, "Unable to create file with square brackets");
        }

        Resource base = ResourceFactory.root().newResource(dir);
        // access via encoded form
        Resource file = base.resolve("foo%5B1%5D");
        assertTrue(file.exists());
        // access via raw form
        file = base.resolve("foo[1]");
        assertTrue(file.exists());
    }

    @Test
    public void testBraces() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        try
        {
            // attempt to create file
            Path foo = dir.resolve("foo.{bar}.txt");
            Files.createFile(foo);
        }
        catch (InvalidPathException e)
        {
            assumeTrue(false, "Unable to create file with squiggle braces");
        }

        Resource base = ResourceFactory.root().newResource(dir);
        Resource file = base.resolve("foo.%7Bbar%7D.txt");
        assertTrue(file.exists());

        // access in un-encoded way
        file = base.resolve("foo.{bar}.txt");
        assertTrue(file.exists());

    }

    @Test
    public void testCaret() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        try
        {
            // attempt to create file
            Path foo = dir.resolve("foo^3.txt");
            Files.createFile(foo);
        }
        catch (InvalidPathException e)
        {
            assumeTrue(false, "Unable to create file with caret");
        }

        Resource base = ResourceFactory.root().newResource(dir);
        // access via encoded form
        Resource file = base.resolve("foo%5E3.txt");
        assertTrue(file.exists());
        // access via raw form
        file = base.resolve("foo^3.txt");
        assertTrue(file.exists());
    }

    @Test
    public void testPipe() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        try
        {
            // attempt to create file
            Path foo = dir.resolve("foo|bar.txt");
            Files.createFile(foo);
        }
        catch (InvalidPathException e)
        {
            assumeTrue(false, "Unable to create file with pipe symbol");
        }

        Resource base = ResourceFactory.root().newResource(dir);
        // access via encoded form
        Resource file = base.resolve("foo%7Cbar.txt");
        assertTrue(file.exists());
        // access via raw form
        file = base.resolve("foo|bar.txt");
        assertTrue(file.exists());
    }

    /**
     * The most basic access example
     *
     * @throws Exception failed test
     */
    @Test
    public void testExistNormal() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path path = dir.resolve("a.jsp");
        Files.createFile(path);

        URI ref = workDir.getPath().toUri().resolve("a.jsp");
        Resource fileres = ResourceFactory.root().newResource(ref);
        assertThat("Resource: " + fileres, fileres.exists(), is(true));
    }

    @Test
    public void testSingleQuoteInFileName() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path fooA = dir.resolve("foo's.txt");
        Path fooB = dir.resolve("f o's.txt");

        Files.createFile(fooA);
        Files.createFile(fooB);

        URI refQuoted = dir.resolve("foo's.txt").toUri();

        Resource fileres = ResourceFactory.root().newResource(refQuoted);
        assertThat("Exists: " + refQuoted, fileres.exists(), is(true));
        assertThat("Target URI: " + refQuoted, fileres, isNotAlias());

        URI refEncoded = dir.toUri().resolve("foo%27s.txt");

        fileres = ResourceFactory.root().newResource(refEncoded);
        assertThat("Exists: " + refEncoded, fileres.exists(), is(true));
        assertThat("Target URI: " + refEncoded, fileres, isNotAlias());

        URI refQuoteSpace = dir.toUri().resolve("f%20o's.txt");

        fileres = ResourceFactory.root().newResource(refQuoteSpace);
        assertThat("Exists: " + refQuoteSpace, fileres.exists(), is(true));
        assertThat("Target URI: " + refQuoteSpace, fileres, isNotAlias());

        URI refEncodedSpace = dir.toUri().resolve("f%20o%27s.txt");

        fileres = ResourceFactory.root().newResource(refEncodedSpace);
        assertThat("Exists: " + refEncodedSpace, fileres.exists(), is(true));
        assertThat("Target URI: " + refEncodedSpace, fileres, isNotAlias());

        URI refA = dir.toUri().resolve("foo's.txt");
        URI refB = dir.toUri().resolve("foo%27s.txt");

        // show that simple URI.equals() doesn't work
        String msg = "URI[a].equals(URI[b])" + System.lineSeparator() +
            "URI[a] = " + refA + System.lineSeparator() +
            "URI[b] = " + refB;
        assertThat(msg, refA.equals(refB), is(false));

        // now show that Resource.equals() does work
        Resource a = ResourceFactory.root().newResource(refA);
        Resource b = ResourceFactory.root().newResource(refB);
        assertThat("A.equals(B)", a.equals(b), is(true));
    }

    @Test
    public void testExistBadURINull() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path path = dir.resolve("a.jsp");
        Files.createFile(path);

        try
        {
            // request with null at end
            URI uri = workDir.getPath().toUri().resolve("a.jsp%00");
            assertThat("Null URI", uri, notNullValue());

            Resource r = ResourceFactory.root().newResource(uri);

            // if we have r, then it better not exist
            assertFalse(r.exists());
        }
        catch (IllegalArgumentException e)
        {
            // Exception is acceptable
        }
    }

    @Test
    public void testExistBadURINullX() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path path = dir.resolve("a.jsp");
        Files.createFile(path);

        try
        {
            // request with null and x at end
            URI uri = workDir.getPath().toUri().resolve("a.jsp%00x");
            assertThat("NullX URI", uri, notNullValue());

            Resource r = ResourceFactory.root().newResource(uri);

            // if we have r, then it better not exist
            assertFalse(r.exists());
        }
        catch (IllegalArgumentException e)
        {
            // Exception is acceptable
        }
    }

    @Test
    public void testResolveWindowsSlash() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path basePath = dir.resolve("base");
        FS.ensureDirExists(basePath);
        Path dirPath = basePath.resolve("aa");
        FS.ensureDirExists(dirPath);
        Path filePath = dirPath.resolve("foo.txt");
        Files.createFile(filePath);

        Resource base = ResourceFactory.root().newResource(basePath);
        try
        {
            assertThat("Exists: " + basePath, base.exists(), is(true));
            assertThat("Target URI: " + basePath, base, isNotAlias());

            Resource r = base.resolve("aa%5C/foo.txt");
            if (org.junit.jupiter.api.condition.OS.WINDOWS.isCurrentOs())
            {
                assertThat("getURI()", r.getPath().toString(), containsString("aa\\foo.txt"));
                assertThat("getURI()", r.getURI().toASCIIString(), containsString("aa%5C/foo.txt"));

                assertThat("isAlias()", r.isAlias(), is(true));
                assertThat("getTargetURI()", r.getTargetURI(), notNullValue());
                assertThat("getTargetURI()", r.getTargetURI().toASCIIString(), containsString("aa/foo.txt"));
                assertThat("Exists: " + r, r.exists(), is(true));
            }
            else
            {
                assertThat("getURI()", r.getPath().toString(), containsString("aa\\/foo.txt"));
                assertThat("getURI()", r.getURI().toASCIIString(), containsString("aa%5C/foo.txt"));

                assertThat("isAlias()", r.isAlias(), is(false));
                assertThat("Exists: " + r, r.exists(), is(false));
            }
        }
        catch (IllegalArgumentException e)
        {
            // Exception is acceptable
            assertThat(e.getCause(), instanceOf(InvalidPathException.class));
        }
    }

    @Test
    public void testResolveWindowsExtensionLess() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path basePath = dir.resolve("base");
        FS.ensureDirExists(basePath);
        Path dirPath = basePath.resolve("aa");
        FS.ensureDirExists(dirPath);
        Path filePath = dirPath.resolve("foo.txt");
        Files.createFile(filePath);

        Resource base = ResourceFactory.root().newResource(basePath);
        try
        {
            assertThat("Exists: " + basePath, base.exists(), is(true));
            assertThat("Target URI: " + basePath, base, isNotAlias());

            Resource r = base.resolve("aa./foo.txt");
            assertThat("getURI()", r.getURI().toASCIIString(), containsString("aa./foo.txt"));

            if (OS.WINDOWS.isCurrentOs())
            {
                assertThat("isAlias()", r.isAlias(), is(true));
                assertThat("getTargetURI()", r.getTargetURI(), notNullValue());
                assertThat("getTargetURI()", r.getTargetURI().toASCIIString(), containsString("aa/foo.txt"));
                assertThat("Exists: " + r, r.exists(), is(true));
            }
            else
            {
                assertThat("isAlias()", r.isAlias(), is(false));
                assertThat("Exists: " + r, r.exists(), is(false));
            }
        }
        catch (IllegalArgumentException e)
        {
            // Exception is acceptable
            assertThat(e.getCause(), instanceOf(InvalidPathException.class));
        }
    }

    @Test
    public void testResolveInitialSlash() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path basePath = dir.resolve("base");
        FS.ensureDirExists(basePath);
        Path filePath = basePath.resolve("foo.txt");
        Files.createFile(filePath);

        Resource base = ResourceFactory.root().newResource(basePath);
        try
        {
            assertThat("Exists: " + basePath, base.exists(), is(true));
            assertThat("Target URI: " + basePath, base, isNotAlias());

            Resource r = base.resolve("/foo.txt");
            assertThat("getURI()", r.getURI().toASCIIString(), containsString("/foo.txt"));

            assertThat("isAlias()", r.isAlias(), is(false));
            assertThat("Exists: " + r, r.exists(), is(true));
        }
        catch (IllegalArgumentException e)
        {
            // Exception is acceptable
            assertThat(e.getCause(), instanceOf(InvalidPathException.class));
        }
    }

    @Test
    public void testResolveInitialDoubleSlash() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path basePath = dir.resolve("base");
        FS.ensureDirExists(basePath);
        Path filePath = basePath.resolve("foo.txt");
        Files.createFile(filePath);

        Resource base = ResourceFactory.root().newResource(basePath);
        try
        {
            assertThat("Exists: " + basePath, base.exists(), is(true));
            assertThat("Target URI: " + basePath, base, isNotAlias());

            Resource r = base.resolve("//foo.txt");
            assertThat("getURI()", r.getURI().toASCIIString(), containsString("/foo.txt"));

            assertThat("isAlias()", r.isAlias(), is(false));
            assertThat(r, isNotAlias());
        }
        catch (IllegalArgumentException e)
        {
            // Exception is acceptable
            assertThat(e.getCause(), instanceOf(InvalidPathException.class));
        }
    }

    @Test
    public void testResolveDoubleSlash() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path basePath = dir.resolve("base");
        FS.ensureDirExists(basePath);
        Path dirPath = basePath.resolve("aa");
        FS.ensureDirExists(dirPath);
        Path filePath = dirPath.resolve("foo.txt");
        Files.createFile(filePath);

        Resource base = ResourceFactory.root().newResource(basePath);
        try
        {
            assertThat("Exists: " + basePath, base.exists(), is(true));
            assertThat("Target URI: " + basePath, base, isNotAlias());

            Resource r = base.resolve("aa//foo.txt");
            assertThat("getURI()", r.getURI().toASCIIString(), containsString("aa/foo.txt"));

            assertThat("isAlias()", r.isAlias(), is(false));
            assertThat(r, isNotAlias());
        }
        catch (IllegalArgumentException e)
        {
            // Exception is acceptable
            assertThat(e.getCause(), instanceOf(InvalidPathException.class));
        }
    }

    @Test
    public void testEncoding() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path specials = dir.resolve("a file with,spe#ials");
        Files.createFile(specials);

        Resource res = ResourceFactory.root().newResource(specials);
        assertThat("Specials URL", res.getURI().toASCIIString(), containsString("a%20file%20with,spe%23ials"));
        assertThat("Specials Filename", res.getPath().toString(), containsString("a file with,spe#ials"));

        Files.delete(res.getPath());
        assertThat("File should have been deleted.", res.exists(), is(false));
    }

    @Test
    public void testUtf8Dir() throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Path utf8Dir;

        try
        {
            utf8Dir = dir.resolve("bãm");
            Files.createDirectories(utf8Dir);
        }
        catch (InvalidPathException e)
        {
            assumeTrue(false, "Unable to create directory with utf-8 character");
            return;
        }

        Path file = utf8Dir.resolve("file.txt");
        Files.createFile(file);

        Resource base = ResourceFactory.root().newResource(utf8Dir);
        assertThat("Exists: " + utf8Dir, base.exists(), is(true));
        assertThat("Target URI: " + utf8Dir, base, isNotAlias());

        Resource r = base.resolve("file.txt");
        assertThat("Exists: " + r, r.exists(), is(true));
        assertThat("Target URI: " + r, r, isNotAlias());
    }

    @Test
    @EnabledOnOs(WINDOWS)
    public void testUncPath() throws Exception
    {
        Resource base = ResourceFactory.root().newResource(URI.create("file:////127.0.0.1/path"));
        Resource resource = base.resolve("WEB-INF/");
        assertThat("getURI()", resource.getURI().toASCIIString(), containsString("path/WEB-INF/"));
        assertThat("isAlias()", resource.isAlias(), is(false));
        assertThat(resource, isNotAlias());
    }

    private String toString(Resource resource) throws IOException
    {
        try (InputStream inputStream = resource.newInputStream();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream())
        {
            IO.copy(inputStream, outputStream);
            return outputStream.toString("utf-8");
        }
    }

    private void touchFile(Path outputFile, String content) throws IOException
    {
        try (StringReader reader = new StringReader(content);
             BufferedWriter writer = Files.newBufferedWriter(outputFile))
        {
            IO.copy(reader, writer);
        }
    }
}
