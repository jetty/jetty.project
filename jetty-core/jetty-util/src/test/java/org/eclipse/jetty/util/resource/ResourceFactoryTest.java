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

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.URIUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class ResourceFactoryTest
{
    public WorkDir workDir;

    @ParameterizedTest
    @ValueSource(strings = {
        "keystore.p12", "/keystore.p12",
        "TestData/alphabet.txt", "/TestData/alphabet.txt",
        "TestData/", "/TestData/", "TestData", "/TestData"
    })
    @Disabled
    public void testNewClassLoaderResourceExists(String reference) throws IOException
    {
        Path alt = workDir.getEmptyPathDir().resolve("alt");
        Files.createDirectories(alt.resolve("TestData"));
        URI altURI = alt.toUri();
        ClassLoader loader = new URLClassLoader(new URL[] {altURI.toURL()});

        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource altResource = resourceFactory.newResource(altURI);

            Thread.currentThread().setContextClassLoader(loader);
            Resource resource = resourceFactory.newClassLoaderResource(reference);
            assertNotNull(resource, "Reference [" + reference + "] should be found");
            assertTrue(resource.exists(), "Reference [" + reference + "] -> Resource[" + resource + "] should exist");

            if (resource.isDirectory())
            {
                assertThat(resource, instanceOf(CombinedResource.class));
                AtomicBoolean fromWorkDir = new AtomicBoolean();
                AtomicBoolean fromResources = new AtomicBoolean();
                resource.forEach(r ->
                {
                    if (r.isContainedIn(altResource))
                        fromWorkDir.set(true);
                    else
                        fromResources.set(true);
                });
                assertTrue(fromWorkDir.get());
                assertTrue(fromResources.get());
            }
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(oldLoader);
            workDir.ensureEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"does-not-exist.dat", "non-existent/dir/contents.txt", "/../"})
    public void testNewClassLoaderResourceDoesNotExists(String reference)
    {
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newClassLoaderResource(reference);
            assertNull(resource, "Reference [" + reference + "] should not be found");
        }
    }

    public static List<String> badReferenceNamesSource()
    {
        List<String> names = new ArrayList<>();
        names.add(null);
        names.add("");
        names.add(" ");
        names.add("\r");
        names.add("\r\n");
        names.add("  \t  ");
        return names;
    }

    @ParameterizedTest
    @MethodSource("badReferenceNamesSource")
    public void testNewClassLoaderResourceDoesBadInput(String reference)
    {
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            assertThrows(IllegalArgumentException.class,
                () -> resourceFactory.newClassLoaderResource(reference),
                "Bad Reference [" + reference + "] should have failed");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "keystore.p12", "/keystore.p12",
        "TestData/alphabet.txt", "/TestData/alphabet.txt",
        "TestData/", "/TestData/", "TestData", "/TestData"
    })
    public void testNewClassPathResourceExists(String reference)
    {
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newClassPathResource(reference);
            assertNotNull(resource, "Reference [" + reference + "] should be found");
            assertTrue(resource.exists(), "Reference [" + reference + "] -> Resource[" + resource + "] should exist");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"does-not-exist.dat", "non-existent/dir/contents.txt", "/../"})
    public void testNewClassPathResourceDoesNotExists(String reference)
    {
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newClassPathResource(reference);
            assertNull(resource, "Reference [" + reference + "] should not be found");
        }
    }

    @ParameterizedTest
    @MethodSource("badReferenceNamesSource")
    public void testNewClassPathResourceDoesBadInput(String reference)
    {
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            assertThrows(IllegalArgumentException.class,
                () -> resourceFactory.newClassPathResource(reference),
                "Bad Reference [" + reference + "] should have failed");
        }
    }

    @Test
    public void testCustomUriSchemeNotRegistered()
    {
        // Try this as a normal String input first.
        // We are subject to the URIUtil.toURI(String) behaviors here.
        // Since the `ftp` scheme is not registered, it's not recognized as a supported URI.
        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
            () -> ResourceFactory.root().newResource("ftp://webtide.com/favicon.ico"));
        assertThat(iae.getMessage(), containsString("URI scheme not registered: ftp"));

        // Now try it as a formal URI object as input.
        URI uri = URI.create("ftp://webtide.com/favicon.ico");
        // This is an unsupported URI scheme
        iae = assertThrows(IllegalArgumentException.class, () -> ResourceFactory.root().newResource(uri));
        assertThat(iae.getMessage(), containsString("URI scheme not registered: ftp"));
    }

    @Test
    public void testCustomUriSchemeRegistered()
    {
        ResourceFactory.registerResourceFactory("custom", new CustomResourceFactory());
        // Try as a normal String input
        Resource resource = ResourceFactory.root().newResource("custom://foo");
        assertThat(resource.getURI(), is(URI.create("custom://foo")));
        assertThat(resource.getName(), is("custom-impl"));

        // Try as a formal URI object as input
        URI uri = URI.create("custom://foo");
        resource = ResourceFactory.root().newResource(uri);
        assertThat(resource.getURI(), is(URI.create("custom://foo")));
        assertThat(resource.getName(), is("custom-impl"));
    }

    @Test
    @Tag("external")
    public void testRegisterHttpsUrlFactory() throws MalformedURLException
    {
        URI uri = URI.create("https://webtide.com/");

        // Verify that environment can access the URI.
        URL url = uri.toURL();
        try
        {
            HttpURLConnection http = (HttpURLConnection)url.openConnection();
            Assumptions.assumeTrue(http.getResponseCode() == HttpURLConnection.HTTP_OK);
        }
        catch (IOException e)
        {
            Assumptions.abort("Unable to connect to " + uri);
        }

        ResourceFactory.registerResourceFactory("https", new URLResourceFactory());
        // Try as a normal String input
        Resource resource = ResourceFactory.root().newResource("https://webtide.com/");
        assertThat(resource.getURI(), is(URI.create("https://webtide.com/")));
        assertThat(resource.getName(), is("https://webtide.com/"));

        // Try as a formal URI object as input
        resource = ResourceFactory.root().newResource(uri);
        assertThat(resource.getURI(), is(URI.create("https://webtide.com/")));
        assertThat(resource.getName(), is("https://webtide.com/"));

        // Try a sub-resource
        Resource subResource = resource.resolve("favicon.ico");
        assertThat(subResource.getFileName(), is("favicon.ico"));
        assertThat(subResource.length(), greaterThan(0L));
    }

    public static Stream<Arguments> newResourceCases()
    {
        List<Arguments> args = new ArrayList<>();

        if (OS.WINDOWS.isCurrentOs())
        {
            // Windows format (absolute and relative)
            args.add(Arguments.of("C:\\path\\to\\foo.jar", "file:///C:/path/to/foo.jar"));
            args.add(Arguments.of("D:\\path\\to\\bogus.txt", "file:///D:/path/to/bogus.txt"));
            args.add(Arguments.of("\\path\\to\\foo.jar", "file:///C:/path/to/foo.jar"));
            args.add(Arguments.of("\\path\\to\\bogus.txt", "file:///C:/path/to/bogus.txt"));
            // unix format (relative)
            args.add(Arguments.of("C:/path/to/foo.jar", "file:///C:/path/to/foo.jar"));
            args.add(Arguments.of("D:/path/to/bogus.txt", "file:///D:/path/to/bogus.txt"));
            args.add(Arguments.of("/path/to/foo.jar", "file:///C:/path/to/foo.jar"));
            args.add(Arguments.of("/path/to/bogus.txt", "file:///C:/path/to/bogus.txt"));
            // URI format (absolute)
            args.add(Arguments.of("file:///D:/path/to/zed.jar", "file:///D:/path/to/zed.jar"));
            args.add(Arguments.of("file:/e:/zed/yotta.txt", "file:///e:/zed/yotta.txt"));
        }
        else
        {
            // URI (and unix) format (relative)
            args.add(Arguments.of("/path/to/foo.jar", "file:///path/to/foo.jar"));
            args.add(Arguments.of("/path/to/bogus.txt", "file:///path/to/bogus.txt"));
        }
        // URI format (absolute)
        args.add(Arguments.of("file:///path/to/zed.jar", "file:///path/to/zed.jar"));
        Path testJar = MavenPaths.findTestResourceFile("jar-file-resource.jar");
        URI jarFileUri = URIUtil.toJarFileUri(testJar.toUri());
        args.add(Arguments.of(jarFileUri.toASCIIString(), jarFileUri.toASCIIString()));

        return args.stream();
    }

    @ParameterizedTest
    @MethodSource("newResourceCases")
    public void testNewResource(String inputRaw, String expectedUri)
    {
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            URI actual = resourceFactory.newResource(inputRaw).getURI();
            URI expected = URI.create(expectedUri);
            assertEquals(expected, actual);
        }
    }

    @Test
    public void testSplitSingleJar()
    {
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Path testJar = MavenPaths.findTestResourceFile("jar-file-resource.jar");
            String input = testJar.toUri().toASCIIString();
            List<Resource> resources = resourceFactory.split(input);
            String expected = URIUtil.toJarFileUri(testJar.toUri()).toASCIIString();
            assertThat(resources.get(0).getURI().toString(), is(expected));
        }
    }

    @Test
    public void testSplitSinglePath()
    {
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Path testJar = MavenPaths.findTestResourceFile("jar-file-resource.jar");
            String input = testJar.toString();
            List<Resource> resources = resourceFactory.split(input);
            String expected = URIUtil.toJarFileUri(testJar.toUri()).toASCIIString();
            assertThat(resources.get(0).getURI().toString(), is(expected));
        }
    }

    @Test
    public void testSplitOnComma()
    {
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Path base = workDir.getEmptyPathDir();
            Path dir = base.resolve("dir");
            FS.ensureDirExists(dir);
            Path foo = dir.resolve("foo");
            FS.ensureDirExists(foo);
            Path bar = dir.resolve("bar");
            FS.ensureDirExists(bar);

            // This represents the user-space raw configuration
            String config = String.format("%s,%s,%s", dir, foo, bar);

            // Split using commas
            List<URI> uris = resourceFactory.split(config).stream().map(Resource::getURI).toList();

            URI[] expected = new URI[]{
                dir.toUri(),
                foo.toUri(),
                bar.toUri()
            };
            assertThat(uris, contains(expected));
        }
    }

    @Test
    public void testSplitOnPipe()
    {
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Path base = workDir.getEmptyPathDir();
            Path dir = base.resolve("dir");
            FS.ensureDirExists(dir);
            Path foo = dir.resolve("foo");
            FS.ensureDirExists(foo);
            Path bar = dir.resolve("bar");
            FS.ensureDirExists(bar);

            // This represents the user-space raw configuration
            String config = String.format("%s|%s|%s", dir, foo, bar);

            // Split using commas
            List<URI> uris = resourceFactory.split(config).stream().map(Resource::getURI).toList();

            URI[] expected = new URI[]{
                dir.toUri(),
                foo.toUri(),
                bar.toUri()
            };
            assertThat(uris, contains(expected));
        }
    }

    @Test
    public void testSplitOnSemicolon()
    {
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Path base = workDir.getEmptyPathDir();
            Path dir = base.resolve("dir");
            FS.ensureDirExists(dir);
            Path foo = dir.resolve("foo");
            FS.ensureDirExists(foo);
            Path bar = dir.resolve("bar");
            FS.ensureDirExists(bar);

            // This represents the user-space raw configuration
            String config = String.format("%s;%s;%s", dir, foo, bar);

            // Split using commas
            List<URI> uris = resourceFactory.split(config).stream().map(Resource::getURI).toList();

            URI[] expected = new URI[]{
                dir.toUri(),
                foo.toUri(),
                bar.toUri()
            };
            assertThat(uris, contains(expected));
        }
    }

    @Test
    public void testSplitOnPathSeparatorWithGlob() throws IOException
    {
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Path base = workDir.getEmptyPathDir();
            Path dir = base.resolve("dir");
            FS.ensureDirExists(dir);
            Path foo = dir.resolve("foo");
            FS.ensureDirExists(foo);
            Path bar = dir.resolve("bar");
            FS.ensureDirExists(bar);
            Files.copy(MavenPaths.findTestResourceFile("jar-file-resource.jar"), bar.resolve("lib-foo.jar"));
            Files.copy(MavenPaths.findTestResourceFile("jar-file-resource.jar"), bar.resolve("lib-zed.zip"));
            Path exampleJar = base.resolve("example.jar");
            Files.copy(MavenPaths.findTestResourceFile("example.jar"), exampleJar);

            // This represents a classpath with a glob
            String config = String.join(File.pathSeparator, List.of(
                dir.toString(), foo.toString(), bar + File.separator + "*", exampleJar.toString()
            ));

            // Split using commas
            List<URI> uris = resourceFactory.split(config, File.pathSeparator).stream().map(Resource::getURI).toList();

            URI[] expected = new URI[]{
                dir.toUri(),
                foo.toUri(),
                // Should see the two archives as `jar:file:` URI entries
                URIUtil.toJarFileUri(bar.resolve("lib-foo.jar").toUri()),
                URIUtil.toJarFileUri(bar.resolve("lib-zed.zip").toUri()),
                URIUtil.toJarFileUri(exampleJar.toUri())
            };

            assertThat(uris, contains(expected));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {";", "|", ","})
    public void testSplitOnDelimWithGlob(String delimChar) throws IOException
    {
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            // TIP: don't allow raw delim to show up in base dir, otherwise the string split later will be wrong.
            Path base = MavenPaths.targetTestDir("testSplitOnPipeWithGlob_%02x".formatted((byte)delimChar.charAt(0)));
            FS.ensureEmpty(base);
            Path dir = base.resolve("dir");
            FS.ensureDirExists(dir);
            Path foo = dir.resolve("foo");
            FS.ensureDirExists(foo);
            Path bar = dir.resolve("bar");
            FS.ensureDirExists(bar);
            Files.copy(MavenPaths.findTestResourceFile("jar-file-resource.jar"), bar.resolve("lib-foo.jar"));
            Files.copy(MavenPaths.findTestResourceFile("jar-file-resource.jar"), bar.resolve("lib-zed.zip"));
            Path exampleJar = base.resolve("example.jar");
            Files.copy(MavenPaths.findTestResourceFile("example.jar"), exampleJar);

            // This represents the user-space raw configuration with a glob
            String config = String.join(delimChar, List.of(
                dir.toString(), foo.toString(), bar + File.separator + "*", exampleJar.toString()
            ));

            // Split using commas
            List<URI> uris = resourceFactory.split(config).stream().map(Resource::getURI).toList();

            URI[] expected = new URI[]{
                dir.toUri(),
                foo.toUri(),
                // Should see the two archives as `jar:file:` URI entries
                URIUtil.toJarFileUri(bar.resolve("lib-foo.jar").toUri()),
                URIUtil.toJarFileUri(bar.resolve("lib-zed.zip").toUri()),
                URIUtil.toJarFileUri(exampleJar.toUri())
            };

            assertThat(uris, contains(expected));
        }
    }

    public static class CustomResourceFactory implements ResourceFactory
    {
        @Override
        public Resource newResource(URI uri)
        {
            return new Resource()
            {
                @Override
                public Path getPath()
                {
                    return null;
                }

                @Override
                public boolean isContainedIn(Resource container)
                {
                    return false;
                }

                @Override
                public boolean isDirectory()
                {
                    return false;
                }

                @Override
                public boolean isReadable()
                {
                    return false;
                }

                @Override
                public URI getURI()
                {
                    return uri;
                }

                @Override
                public String getName()
                {
                    return "custom-impl";
                }

                @Override
                public String getFileName()
                {
                    return null;
                }

                @Override
                public Resource resolve(String subUriPath)
                {
                    return null;
                }
            };
        }
    }
}
