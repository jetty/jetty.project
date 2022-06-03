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
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.BufferUtil;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    public static Stream<Class<PathResource>> fsResourceProvider()
    {
        return Stream.of(PathResource.class);
    }

    public Resource newResource(Class<? extends Resource> resourceClass, URL url) throws Exception
    {
        try
        {
            return resourceClass.getConstructor(URL.class).newInstance(url);
        }
        catch (InvocationTargetException e)
        {
            try
            {
                throw e.getTargetException();
            }
            catch (Exception | Error ex)
            {
                throw ex;
            }
            catch (Throwable th)
            {
                throw new Error(th);
            }
        }
    }

    public Resource newResource(Class<? extends Resource> resourceClass, URI uri) throws Exception
    {
        try
        {
            return resourceClass.getConstructor(URI.class).newInstance(uri);
        }
        catch (InvocationTargetException e)
        {
            try
            {
                throw e.getTargetException();
            }
            catch (Exception | Error ex)
            {
                throw ex;
            }
            catch (Throwable th)
            {
                throw new Error(th);
            }
        }
    }

    public Resource newResource(Class<? extends Resource> resourceClass, Path path) throws Exception
    {
        try
        {
            return resourceClass.getConstructor(Path.class).newInstance(path);
        }
        catch (InvocationTargetException e)
        {
            try
            {
                throw e.getTargetException();
            }
            catch (Exception | Error ex)
            {
                throw ex;
            }
            catch (Throwable th)
            {
                throw new Error(th);
            }
        }
    }

    private Matcher<Resource> hasNoAlias()
    {
        return new BaseMatcher<>()
        {
            @Override
            public boolean matches(Object item)
            {
                final Resource res = (Resource)item;
                return !res.isAlias();
            }

            @Override
            public void describeTo(Description description)
            {
                description.appendText("getAlias should return null");
            }

            @Override
            public void describeMismatch(Object item, Description description)
            {
                description.appendText("was ").appendValue(((Resource)item).getAlias());
            }
        };
    }

    private Matcher<Resource> isAliasFor(final Resource resource)
    {
        return new BaseMatcher<>()
        {
            @Override
            public boolean matches(Object item)
            {
                final Resource ritem = (Resource)item;
                final URI alias = ritem.getAlias();
                if (alias == null)
                {
                    return resource.getAlias() == null;
                }
                else
                {
                    return alias.equals(resource.getURI());
                }
            }

            @Override
            public void describeTo(Description description)
            {
                description.appendText("getAlias should return ").appendValue(resource.getURI());
            }

            @Override
            public void describeMismatch(Object item, Description description)
            {
                description.appendText("was ").appendValue(((Resource)item).getAlias());
            }
        };
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testNonAbsoluteURI(Class<PathResource> resourceClass)
    {
        assertThrows(IllegalArgumentException.class,
            () -> newResource(resourceClass, new URI("path/to/resource")));
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testNotFileURI(Class<PathResource> resourceClass)
    {
        assertThrows(IllegalArgumentException.class,
            () -> newResource(resourceClass, new URI("https://www.eclipse.org/jetty/")));
    }

    @ParameterizedTest
    @EnabledOnOs(WINDOWS)
    @MethodSource("fsResourceProvider")
    public void testBogusFilenameWindows(Class<PathResource> resourceClass)
    {
        // "CON" is a reserved name under windows
        assertThrows(IllegalArgumentException.class,
            () -> newResource(resourceClass, new URI("file://CON")));
    }

    @ParameterizedTest
    @EnabledOnOs({LINUX, MAC})
    @MethodSource("fsResourceProvider")
    public void testBogusFilenameUnix(Class<PathResource> resourceClass)
    {
        // A windows path is invalid under unix
        assertThrows(IllegalArgumentException.class,
            () -> newResource(resourceClass, new URI("file://Z:/:")));
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testNewResourceWithSpace(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getPath().normalize().toRealPath();

        Path baseDir = dir.resolve("base with spaces");
        FS.ensureDirExists(baseDir.toFile());

        Path subdir = baseDir.resolve("sub");
        FS.ensureDirExists(subdir.toFile());

        URL baseUrl = baseDir.toUri().toURL();

        assertThat("url.protocol", baseUrl.getProtocol(), is("file"));

        try (Resource base = newResource(resourceClass, baseUrl))
        {
            Resource sub = base.getResource("sub");
            assertThat("sub/.isDirectory", sub.isDirectory(), is(true));

            Resource tmp = sub.getResource("/tmp");
            assertThat("No root", tmp.exists(), is(false));
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testAddPathClass(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();

        Path subdir = dir.resolve("sub");
        FS.ensureDirExists(subdir.toFile());

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource sub = base.getResource("sub");
            assertThat("sub/.isDirectory", sub.isDirectory(), is(true));

            Resource tmp = sub.getResource("/tmp");
            assertThat("No root", tmp.exists(), is(false));
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testAddRootPath(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Path subdir = dir.resolve("sub");
        Files.createDirectories(subdir);

        String readableRootDir = findRootDir(dir.getFileSystem());
        assumeTrue(readableRootDir != null, "Readable Root Dir found");

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource sub = base.getResource("sub");
            assertThat("sub", sub.isDirectory(), is(true));

            try
            {
                Resource rrd = sub.getResource(readableRootDir);
                // valid path for unix and OSX
                assertThat("Readable Root Dir", rrd.exists(), is(false));
            }
            catch (MalformedURLException | InvalidPathException e)
            {
                // valid path on Windows
            }
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testAccessUniCodeFile(Class resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();

        String readableRootDir = findRootDir(dir.getFileSystem());
        assumeTrue(readableRootDir != null, "Readable Root Dir found");

        Path subdir = dir.resolve("sub");
        Files.createDirectories(subdir);

        touchFile(subdir.resolve("swedish-å.txt"), "hi a-with-circle");
        touchFile(subdir.resolve("swedish-ä.txt"), "hi a-with-two-dots");
        touchFile(subdir.resolve("swedish-ö.txt"), "hi o-with-two-dots");

        try (Resource base = newResource(resourceClass, subdir))
        {
            Resource refA1 = base.getResource("swedish-å.txt");
            Resource refA2 = base.getResource("swedish-ä.txt");
            Resource refO1 = base.getResource("swedish-ö.txt");

            assertThat("Ref A1 exists", refA1.exists(), is(true));
            assertThat("Ref A2 exists", refA2.exists(), is(true));
            assertThat("Ref O1 exists", refO1.exists(), is(true));

            if (LINUX.isCurrentOs())
            {
                assertThat("Ref A1 alias", refA1.isAlias(), is(false));
                assertThat("Ref A2 alias", refA2.isAlias(), is(false));
                assertThat("Ref O1 alias", refO1.isAlias(), is(false));
            }

            assertThat("Ref A1 contents", toString(refA1), is("hi a-with-circle"));
            assertThat("Ref A2 contents", toString(refA2), is("hi a-with-two-dots"));
            assertThat("Ref O1 contents", toString(refO1), is("hi o-with-two-dots"));
        }
    }

    private String findRootDir(FileSystem fs)
    {
        // look for a directory off of a root path
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
                // FIXME why ignoring exceptions??
            }
        }

        return null;
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testIsContainedIn(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);
        Path foo = dir.resolve("foo");
        Files.createFile(foo);

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource res = base.getResource("foo");
            assertThat("is contained in", res.isContainedIn(base), is(true));
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testIsDirectory(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);
        Path foo = dir.resolve("foo");
        Files.createFile(foo);

        Path subdir = dir.resolve("sub");
        Files.createDirectories(subdir);

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource res = base.getResource("foo");
            assertThat("foo.isDirectory", res.isDirectory(), is(false));

            Resource sub = base.getResource("sub");
            assertThat("sub/.isDirectory", sub.isDirectory(), is(true));
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testLastModified(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Path file = workDir.getPathFile("foo");
        Files.createFile(file);

        long expected = Files.getLastModifiedTime(file).toMillis();

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource res = base.getResource("foo");
            assertThat("foo.lastModified", res.lastModified() / 1000 * 1000, lessThanOrEqualTo(expected));
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testLastModifiedNotExists(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource res = base.getResource("foo");
            assertThat("foo.lastModified", res.lastModified(), is(0L));
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testLength(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path file = dir.resolve("foo");
        touchFile(file, "foo");

        long expected = Files.size(file);

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource res = base.getResource("foo");
            assertThat("foo.length", res.length(), is(expected));
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testLengthNotExists(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource res = base.getResource("foo");
            assertThat("foo.length", res.length(), is(0L));
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testDelete(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);
        Path file = dir.resolve("foo");
        Files.createFile(file);

        try (Resource base = newResource(resourceClass, dir))
        {
            // Is it there?
            Resource res = base.getResource("foo");
            assertThat("foo.exists", res.exists(), is(true));
            // delete it
            assertThat("foo.delete", res.delete(), is(true));
            // is it there?
            assertThat("foo.exists", res.exists(), is(false));
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testDeleteNotExists(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        try (Resource base = newResource(resourceClass, dir))
        {
            // Is it there?
            Resource res = base.getResource("foo");
            assertThat("foo.exists", res.exists(), is(false));
            // delete it
            assertThat("foo.delete", res.delete(), is(false));
            // is it there?
            assertThat("foo.exists", res.exists(), is(false));
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testName(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        String expected = dir.toAbsolutePath().toString();

        try (Resource base = newResource(resourceClass, dir))
        {
            assertThat("base.name", base.getName(), is(expected));
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testInputStream(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path file = dir.resolve("foo");
        String content = "Foo is here";
        touchFile(file, content);

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource foo = base.getResource("foo");
            try (InputStream stream = foo.getInputStream();
                 InputStreamReader reader = new InputStreamReader(stream);
                 StringWriter writer = new StringWriter())
            {
                IO.copy(reader, writer);
                assertThat("Stream", writer.toString(), is(content));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testReadableByteChannel(Class<PathResource> resourceClass) throws Exception
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

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource foo = base.getResource("foo");
            try (ReadableByteChannel channel = foo.getReadableByteChannel())
            {
                ByteBuffer buf = ByteBuffer.allocate(256);
                channel.read(buf);
                buf.flip();
                String actual = BufferUtil.toUTF8String(buf);
                assertThat("ReadableByteChannel content", actual, is(content));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testGetURI(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path file = dir.resolve("foo");
        Files.createFile(file);

        URI expected = file.toUri();

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource foo = base.getResource("foo");
            assertThat("getURI", foo.getURI(), is(expected));
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testList(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Files.createFile(dir.resolve("foo"));
        Files.createFile(dir.resolve("bar"));
        Files.createDirectories(dir.resolve("tick"));
        Files.createDirectories(dir.resolve("tock"));

        List<String> expected = new ArrayList<>();
        expected.add("foo");
        expected.add("bar");
        expected.add("tick/");
        expected.add("tock/");

        try (Resource base = newResource(resourceClass, dir))
        {
            String[] list = base.list();
            List<String> actual = Arrays.asList(list);

            assertEquals(expected.size(), actual.size());
            for (String s : expected)
            {
                assertTrue(actual.contains(s));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testSymlink(Class<PathResource> resourceClass) throws Exception
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

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource resFoo = base.getResource("foo");
            Resource resBar = base.getResource("bar");

            assertThat("resFoo.uri", resFoo.getURI(), is(foo.toUri()));

            // Access to the same resource, but via a symlink means that they are not equivalent
            assertThat("foo.equals(bar)", resFoo.equals(resBar), is(false));

            assertThat("resource.alias", resFoo, hasNoAlias());
            assertThat("resource.uri.alias", newResource(resourceClass, resFoo.getURI()), hasNoAlias());
            assertThat("resource.file.alias", newResource(resourceClass, resFoo.getPath()), hasNoAlias());

            assertThat("alias", resBar, isAliasFor(resFoo));
            assertThat("uri.alias", newResource(resourceClass, resBar.getURI()), isAliasFor(resFoo));
            assertThat("file.alias", newResource(resourceClass, resBar.getPath()), isAliasFor(resFoo));
        }
    }

    @ParameterizedTest
    @ValueSource(classes = PathResource.class) // FileResource does not support this
    public void testNonExistantSymlink(Class<PathResource> resourceClass) throws Exception
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

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource resFoo = base.getResource("foo");
            Resource resBar = base.getResource("bar");

            assertThat("resFoo.uri", resFoo.getURI(), is(foo.toUri()));

            // Access to the same resource, but via a symlink means that they are not equivalent
            assertThat("foo.equals(bar)", resFoo.equals(resBar), is(false));

            assertThat("resource.alias", resFoo, hasNoAlias());
            assertThat("resource.uri.alias", newResource(resourceClass, resFoo.getURI()), hasNoAlias());
            assertThat("resource.file.alias", newResource(resourceClass, resFoo.getPath()), hasNoAlias());

            assertThat("alias", resBar, isAliasFor(resFoo));
            assertThat("uri.alias", newResource(resourceClass, resBar.getURI()), isAliasFor(resFoo));
            assertThat("file.alias", newResource(resourceClass, resBar.getPath()), isAliasFor(resFoo));
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testCaseInsensitiveAlias(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);
        Path path = dir.resolve("file");
        Files.createFile(path);

        try (Resource base = newResource(resourceClass, dir))
        {
            // Reference to actual resource that exists
            Resource resource = base.getResource("file");

            assertThat("resource.alias", resource, hasNoAlias());
            assertThat("resource.uri.alias", newResource(resourceClass, resource.getURI()), hasNoAlias());
            assertThat("resource.file.alias", newResource(resourceClass, resource.getPath()), hasNoAlias());

            // On some case insensitive file systems, lets see if an alternate
            // case for the filename results in an alias reference
            Resource alias = base.getResource("FILE");
            if (alias.exists())
            {
                // If it exists, it must be an alias
                assertThat("alias", alias, isAliasFor(resource));
                assertThat("alias.uri", newResource(resourceClass, alias.getURI()), isAliasFor(resource));
                assertThat("alias.file", newResource(resourceClass, alias.getPath()), isAliasFor(resource));
            }
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
    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    @EnabledOnOs(WINDOWS)
    public void testCase8dot3Alias(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path path = dir.resolve("TextFile.Long.txt");
        Files.createFile(path);

        try (Resource base = newResource(resourceClass, dir))
        {
            // Long filename
            Resource resource = base.getResource("TextFile.Long.txt");

            assertThat("resource.alias", resource, hasNoAlias());
            assertThat("resource.uri.alias", newResource(resourceClass, resource.getURI()), hasNoAlias());
            assertThat("resource.file.alias", newResource(resourceClass, resource.getPath()), hasNoAlias());

            // On some versions of Windows, the long filename can be referenced
            // via a short 8.3 equivalent filename.
            Resource alias = base.getResource("TEXTFI~1.TXT");
            if (alias.exists())
            {
                // If it exists, it must be an alias
                assertThat("alias", alias, isAliasFor(resource));
                assertThat("alias.uri", newResource(resourceClass, alias.getURI()), isAliasFor(resource));
                assertThat("alias.file", newResource(resourceClass, alias.getPath()), isAliasFor(resource));
            }
        }
    }

    /**
     * NTFS Alternative Data / File Streams.
     * <p>
     * See: http://msdn.microsoft.com/en-us/library/windows/desktop/aa364404(v=vs.85).aspx
     *
     * @throws Exception failed test
     */
    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    @EnabledOnOs(WINDOWS)
    public void testNTFSFileStreamAlias(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path path = dir.resolve("testfile");
        Files.createFile(path);

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource resource = base.getResource("testfile");

            assertThat("resource.alias", resource, hasNoAlias());
            assertThat("resource.uri.alias", newResource(resourceClass, resource.getURI()), hasNoAlias());
            assertThat("resource.file.alias", newResource(resourceClass, resource.getPath()), hasNoAlias());

            try
            {
                // Attempt to reference same file, but via NTFS simple stream
                Resource alias = base.getResource("testfile:stream");
                if (alias.exists())
                {
                    // If it exists, it must be an alias
                    assertThat("resource.alias", alias, isAliasFor(resource));
                    assertThat("resource.uri.alias", newResource(resourceClass, alias.getURI()), isAliasFor(resource));
                    assertThat("resource.file.alias", newResource(resourceClass, alias.getPath()), isAliasFor(resource));
                }
            }
            catch (InvalidPathException e)
            {
                assumeTrue(false, "NTFS simple streams not supported");
            }
        }
    }

    /**
     * NTFS Alternative Data / File Streams.
     * <p>
     * See: http://msdn.microsoft.com/en-us/library/windows/desktop/aa364404(v=vs.85).aspx
     *
     * @throws Exception failed test
     */
    @ParameterizedTest
    @ValueSource(classes = PathResource.class) // not supported on FileResource
    @EnabledOnOs(WINDOWS)
    public void testNTFSFileDataStreamAlias(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path path = dir.resolve("testfile");
        Files.createFile(path);

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource resource = base.getResource("testfile");

            assertThat("resource.alias", resource, hasNoAlias());
            assertThat("resource.uri.alias", newResource(resourceClass, resource.getURI()), hasNoAlias());
            assertThat("resource.file.alias", newResource(resourceClass, resource.getPath()), hasNoAlias());

            try
            {
                // Attempt to reference same file, but via NTFS DATA stream
                Resource alias = base.getResource("testfile::$DATA");
                if (alias.exists())
                {
                    assumeTrue(alias.getURI().getScheme().equals("file"));

                    // If it exists, it must be an alias
                    assertThat("resource.alias", alias, isAliasFor(resource));
                    assertThat("resource.uri.alias", newResource(resourceClass, alias.getURI()), isAliasFor(resource));
                    assertThat("resource.file.alias", newResource(resourceClass, alias.getPath()), isAliasFor(resource));
                }
            }
            catch (InvalidPathException e)
            {
                assumeTrue(false, "NTFS $DATA streams not supported");
            }
        }
    }

    /**
     * NTFS Alternative Data / File Streams.
     * <p>
     * See: http://msdn.microsoft.com/en-us/library/windows/desktop/aa364404(v=vs.85).aspx
     *
     * @throws Exception failed test
     */
    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    @EnabledOnOs(WINDOWS)
    public void testNTFSFileEncodedDataStreamAlias(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path path = dir.resolve("testfile");
        Files.createFile(path);

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource resource = base.getResource("testfile");

            assertThat("resource.alias", resource, hasNoAlias());
            assertThat("resource.uri.alias", newResource(resourceClass, resource.getURI()), hasNoAlias());
            assertThat("resource.file.alias", newResource(resourceClass, resource.getPath()), hasNoAlias());

            try
            {
                // Attempt to reference same file, but via NTFS DATA stream (encoded addPath version) 
                Resource alias = base.getResource("testfile::%24DATA");
                if (alias.exists())
                {
                    // If it exists, it must be an alias
                    assertThat("resource.alias", alias, isAliasFor(resource));
                    assertThat("resource.uri.alias", newResource(resourceClass, alias.getURI()), isAliasFor(resource));
                    assertThat("resource.file.alias", newResource(resourceClass, alias.getPath()), isAliasFor(resource));
                }
            }
            catch (InvalidPathException e)
            {
                assumeTrue(false, "NTFS $DATA streams not supported");
            }
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testSemicolon(Class<PathResource> resourceClass) throws Exception
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

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource res = base.getResource("foo;");
            assertThat("Alias: " + res, res, hasNoAlias());
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testSingleQuote(Class<PathResource> resourceClass) throws Exception
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

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource res = base.getResource("foo' bar");
            assertThat("Alias: " + res, res.getAlias(), nullValue());
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testSingleBackTick(Class<PathResource> resourceClass) throws Exception
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

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource res = base.getResource("foo` bar");
            assertThat("Alias: " + res, res.getAlias(), nullValue());
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testBrackets(Class<PathResource> resourceClass) throws Exception
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

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource res = base.getResource("foo[1]");
            assertThat("Alias: " + res, res.getAlias(), nullValue());
        }
    }

    @ParameterizedTest
    @ValueSource(classes = PathResource.class) // FileResource does not support this
    public void testBraces(Class<PathResource> resourceClass) throws Exception
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

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource res = base.getResource("foo.{bar}.txt");
            assertThat("Alias: " + res, res.getAlias(), nullValue());
        }
    }

    @ParameterizedTest
    @ValueSource(classes = PathResource.class) // FileResource does not support this
    public void testCaret(Class<PathResource> resourceClass) throws Exception
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

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource res = base.getResource("foo^3.txt");
            assertThat("Alias: " + res, res.getAlias(), nullValue());
        }
    }

    @ParameterizedTest
    @ValueSource(classes = PathResource.class) // FileResource does not support this
    public void testPipe(Class<PathResource> resourceClass) throws Exception
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

        try (Resource base = newResource(resourceClass, dir))
        {
            Resource res = base.getResource("foo|bar.txt");
            assertThat("Alias: " + res, res.getAlias(), nullValue());
        }
    }

    /**
     * The most basic access example
     *
     * @throws Exception failed test
     */
    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testExistNormal(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path path = dir.resolve("a.jsp");
        Files.createFile(path);

        URI ref = workDir.getPath().toUri().resolve("a.jsp");
        try (Resource fileres = newResource(resourceClass, ref))
        {
            assertThat("Resource: " + fileres, fileres.exists(), is(true));
        }
    }

    @ParameterizedTest
    @ValueSource(classes = PathResource.class) // FileResource not supported here
    public void testSingleQuoteInFileName(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path fooA = dir.resolve("foo's.txt");
        Path fooB = dir.resolve("f o's.txt");

        Files.createFile(fooA);
        Files.createFile(fooB);

        URI refQuoted = dir.resolve("foo's.txt").toUri();

        try (Resource fileres = newResource(resourceClass, refQuoted))
        {
            assertThat("Exists: " + refQuoted, fileres.exists(), is(true));
            assertThat("Alias: " + refQuoted, fileres, hasNoAlias());
        }

        URI refEncoded = dir.toUri().resolve("foo%27s.txt");

        try (Resource fileres = newResource(resourceClass, refEncoded))
        {
            assertThat("Exists: " + refEncoded, fileres.exists(), is(true));
            assertThat("Alias: " + refEncoded, fileres, hasNoAlias());
        }

        URI refQuoteSpace = dir.toUri().resolve("f%20o's.txt");

        try (Resource fileres = newResource(resourceClass, refQuoteSpace))
        {
            assertThat("Exists: " + refQuoteSpace, fileres.exists(), is(true));
            assertThat("Alias: " + refQuoteSpace, fileres, hasNoAlias());
        }

        URI refEncodedSpace = dir.toUri().resolve("f%20o%27s.txt");

        try (Resource fileres = newResource(resourceClass, refEncodedSpace))
        {
            assertThat("Exists: " + refEncodedSpace, fileres.exists(), is(true));
            assertThat("Alias: " + refEncodedSpace, fileres, hasNoAlias());
        }

        URI refA = dir.toUri().resolve("foo's.txt");
        URI refB = dir.toUri().resolve("foo%27s.txt");

        // show that simple URI.equals() doesn't work
        String msg = "URI[a].equals(URI[b])" + System.lineSeparator() +
            "URI[a] = " + refA + System.lineSeparator() +
            "URI[b] = " + refB;
        assertThat(msg, refA.equals(refB), is(false));

        // now show that Resource.equals() does work
        try (Resource a = newResource(resourceClass, refA);
             Resource b = newResource(resourceClass, refB))
        {
            assertThat("A.equals(B)", a.equals(b), is(true));
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testExistBadURINull(Class<PathResource> resourceClass) throws Exception
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

            Resource r = newResource(resourceClass, uri);

            // if we have r, then it better not exist
            assertFalse(r.exists());
        }
        catch (InvalidPathException e)
        {
            // Exception is acceptable
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testExistBadURINullX(Class<PathResource> resourceClass) throws Exception
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

            Resource r = newResource(resourceClass, uri);

            // if we have r, then it better not exist
            assertFalse(r.exists());
        }
        catch (InvalidPathException e)
        {
            // Exception is acceptable
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testAddPathWindowsSlash(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path basePath = dir.resolve("base");
        FS.ensureDirExists(basePath);
        Path dirPath = basePath.resolve("aa");
        FS.ensureDirExists(dirPath);
        Path filePath = dirPath.resolve("foo.txt");
        Files.createFile(filePath);

        try (Resource base = newResource(resourceClass, basePath))
        {
            assertThat("Exists: " + basePath, base.exists(), is(true));
            assertThat("Alias: " + basePath, base, hasNoAlias());

            Resource r = base.getResource("aa\\/foo.txt");
            assertThat("getURI()", r.getURI().toASCIIString(), containsString("aa%5C/foo.txt"));

            if (org.junit.jupiter.api.condition.OS.WINDOWS.isCurrentOs())
            {
                assertThat("isAlias()", r.isAlias(), is(true));
                assertThat("getAlias()", r.getAlias(), notNullValue());
                assertThat("getAlias()", r.getAlias().toASCIIString(), containsString("aa/foo.txt"));
                assertThat("Exists: " + r, r.exists(), is(true));
            }
            else
            {
                assertThat("isAlias()", r.isAlias(), is(false));
                assertThat("Exists: " + r, r.exists(), is(false));
            }
        }
        catch (InvalidPathException e)
        {
            // Exception is acceptable
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testAddPathWindowsExtensionLess(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path basePath = dir.resolve("base");
        FS.ensureDirExists(basePath);
        Path dirPath = basePath.resolve("aa");
        FS.ensureDirExists(dirPath);
        Path filePath = dirPath.resolve("foo.txt");
        Files.createFile(filePath);

        try (Resource base = newResource(resourceClass, basePath))
        {
            assertThat("Exists: " + basePath, base.exists(), is(true));
            assertThat("Alias: " + basePath, base, hasNoAlias());

            Resource r = base.getResource("aa./foo.txt");
            assertThat("getURI()", r.getURI().toASCIIString(), containsString("aa./foo.txt"));

            if (OS.WINDOWS.isCurrentOs())
            {
                assertThat("isAlias()", r.isAlias(), is(true));
                assertThat("getAlias()", r.getAlias(), notNullValue());
                assertThat("getAlias()", r.getAlias().toASCIIString(), containsString("aa/foo.txt"));
                assertThat("Exists: " + r, r.exists(), is(true));
            }
            else
            {
                assertThat("isAlias()", r.isAlias(), is(false));
                assertThat("Exists: " + r, r.exists(), is(false));
            }
        }
        catch (InvalidPathException e)
        {
            // Exception is acceptable
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testAddInitialSlash(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path basePath = dir.resolve("base");
        FS.ensureDirExists(basePath);
        Path filePath = basePath.resolve("foo.txt");
        Files.createFile(filePath);

        try (Resource base = newResource(resourceClass, basePath))
        {
            assertThat("Exists: " + basePath, base.exists(), is(true));
            assertThat("Alias: " + basePath, base, hasNoAlias());

            Resource r = base.getResource("/foo.txt");
            assertThat("getURI()", r.getURI().toASCIIString(), containsString("/foo.txt"));

            assertThat("isAlias()", r.isAlias(), is(false));
            assertThat("Exists: " + r, r.exists(), is(true));
        }
        catch (InvalidPathException e)
        {
            // Exception is acceptable
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testAddInitialDoubleSlash(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path basePath = dir.resolve("base");
        FS.ensureDirExists(basePath);
        Path filePath = basePath.resolve("foo.txt");
        Files.createFile(filePath);

        try (Resource base = newResource(resourceClass, basePath))
        {
            assertThat("Exists: " + basePath, base.exists(), is(true));
            assertThat("Alias: " + basePath, base, hasNoAlias());

            Resource r = base.getResource("//foo.txt");
            assertThat("getURI()", r.getURI().toASCIIString(), containsString("//foo.txt"));

            assertThat("isAlias()", r.isAlias(), is(true));
            assertThat("getAlias()", r.getAlias(), notNullValue());
            assertThat("getAlias()", r.getAlias().toASCIIString(), containsString("/foo.txt"));
            assertThat("Exists: " + r, r.exists(), is(true));
        }
        catch (InvalidPathException e)
        {
            // Exception is acceptable
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testAddDoubleSlash(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path basePath = dir.resolve("base");
        FS.ensureDirExists(basePath);
        Path dirPath = basePath.resolve("aa");
        FS.ensureDirExists(dirPath);
        Path filePath = dirPath.resolve("foo.txt");
        Files.createFile(filePath);

        try (Resource base = newResource(resourceClass, basePath))
        {
            assertThat("Exists: " + basePath, base.exists(), is(true));
            assertThat("Alias: " + basePath, base, hasNoAlias());

            Resource r = base.getResource("aa//foo.txt");
            assertThat("getURI()", r.getURI().toASCIIString(), containsString("aa//foo.txt"));

            assertThat("isAlias()", r.isAlias(), is(true));
            assertThat("getAlias()", r.getAlias(), notNullValue());
            assertThat("getAlias()", r.getAlias().toASCIIString(), containsString("aa/foo.txt"));
            assertThat("Exists: " + r, r.exists(), is(true));
        }
        catch (InvalidPathException e)
        {
            // Exception is acceptable
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testEncoding(Class<PathResource> resourceClass) throws Exception
    {
        Path dir = workDir.getEmptyPathDir();
        Files.createDirectories(dir);

        Path specials = dir.resolve("a file with,spe#ials");
        Files.createFile(specials);

        try (Resource res = newResource(resourceClass, specials))
        {
            assertThat("Specials URL", res.getURI().toASCIIString(), containsString("a%20file%20with,spe%23ials"));
            assertThat("Specials Filename", res.getPath().toString(), containsString("a file with,spe#ials"));

            res.delete();
            assertThat("File should have been deleted.", res.exists(), is(false));
        }
    }

    @ParameterizedTest
    @MethodSource("fsResourceProvider")
    public void testUtf8Dir(Class<PathResource> resourceClass) throws Exception
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

        try (Resource base = newResource(resourceClass, utf8Dir))
        {
            assertThat("Exists: " + utf8Dir, base.exists(), is(true));
            assertThat("Alias: " + utf8Dir, base, hasNoAlias());

            Resource r = base.getResource("file.txt");
            assertThat("Exists: " + r, r.exists(), is(true));
            assertThat("Alias: " + r, r, hasNoAlias());
        }
    }

    @ParameterizedTest
    @ValueSource(classes = PathResource.class) // FileResource does not support this
    @EnabledOnOs(WINDOWS)
    public void testUncPath(Class<PathResource> resourceClass) throws Exception
    {
        try (Resource base = newResource(resourceClass, URI.create("file:////127.0.0.1/path")))
        {
            Resource resource = base.getResource("WEB-INF/");
            assertThat("getURI()", resource.getURI().toASCIIString(), containsString("path/WEB-INF/"));
            assertThat("isAlias()", resource.isAlias(), is(false));
            assertThat("getAlias()", resource.getAlias(), nullValue());
        }
    }

    private String toString(Resource resource) throws IOException
    {
        try (InputStream inputStream = resource.getInputStream();
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
