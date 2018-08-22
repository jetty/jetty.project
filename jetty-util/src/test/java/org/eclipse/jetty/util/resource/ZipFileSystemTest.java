//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util.resource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.IO;
import org.junit.Rule;
import org.junit.Test;

@SuppressWarnings("Duplicates")
public class ZipFileSystemTest
{
    private static final int RUNTIME_PLATFORM;
    private static final boolean RUNTIME_SUPPORTS_JEP238;

    static
    {
        String javaVer[] = System.getProperty("java.specification.version").split("\\.");
        RUNTIME_PLATFORM = Integer.parseInt(javaVer[0]);
        RUNTIME_SUPPORTS_JEP238 = RUNTIME_PLATFORM >= 9;
    }

    @Rule
    public TestingDir testingDir = new TestingDir();

    @Test
    public void testBasicJar() throws IOException, URISyntaxException
    {
        MultiReleaseJarCreator creator = new MultiReleaseJarCreator(testingDir);
        Path jarPath = creator.createBasicJar();
        URI uri = new URI("jar", jarPath.toUri().toASCIIString(), null);
        System.err.println("URI = " + uri);

        Map<String, Object> env = new HashMap<>();
        env.put("multi-release", "runtime");

        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            dumpZipFs(zipfs);

            Path greetingsSourcePath = zipfs.getPath("/hello/Greetings.java");
            String source = toString(greetingsSourcePath);
            assertThat("Source", source, containsString("Hello from zipfs base"));

            source = toString(zipfs.getPath("README.txt"));
            assertThat("README Source", source, is(MultiReleaseJarCreator.README_ROOT));
        }
    }

    @Test
    public void testMultiRelease9() throws IOException, URISyntaxException
    {
        MultiReleaseJarCreator creator = new MultiReleaseJarCreator(testingDir);
        Path jarPath = creator.createMultiReleaseJar9();
        URI uri = new URI("jar", jarPath.toUri().toASCIIString(), null);
        System.err.println("URI = " + uri);

        Map<String, Object> env = new HashMap<>();
        env.put("multi-release", "9");

        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            dumpZipFs(zipfs);

            if (RUNTIME_PLATFORM >= 9)
            {
                Path greetingsSourcePath = zipfs.getPath("/hello/Greetings.java");
                String source = toString(greetingsSourcePath);
                assertThat("Source", source, containsString("Hello from versions/9"));
            }

            // test readme
            if (RUNTIME_SUPPORTS_JEP238)
            {
                String source = toString(zipfs.getPath("README.txt"));
                assertThat("README Source", source, is(MultiReleaseJarCreator.README_VER9));
            }
            else
            {
                String source = toString(zipfs.getPath("README.txt"));
                assertThat("README Source", source, is(MultiReleaseJarCreator.README_ROOT));
            }
        }
    }

    @Test
    public void testMultiOpen() throws Exception
    {
        MultiReleaseJarCreator creator = new MultiReleaseJarCreator(testingDir);
        Path jarPath = creator.createMultiReleaseJar9();
        URI uri = new URI("jar", jarPath.toUri().toASCIIString(), null);
        System.err.println("URI = " + uri);

        Map<String, Object> env = new HashMap<>();
        env.put("multi-release", "9");

        FileSystem zipfs1 = FileSystems.newFileSystem(uri, env);
        zipfs1.close();
        FileSystem zipfs2 = FileSystems.newFileSystem(uri, env);

        System.out.println("zipfs1 = " + zipfs1);
        System.out.println("zipfs2 = " + zipfs2);

        // reference counting
        // close issues?
        // garbage collection?
    }

    @Test
    public void testMultiRelease10() throws IOException, URISyntaxException
    {
        MultiReleaseJarCreator creator = new MultiReleaseJarCreator(testingDir);
        Path jarPath = creator.createMultiReleaseJar10();
        URI uri = new URI("jar", jarPath.toUri().toASCIIString(), null);
        System.err.println("URI = " + uri);

        Map<String, Object> env = new HashMap<>();
        env.put("multi-release", "10");

        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            dumpZipFs(zipfs);

            if (RUNTIME_PLATFORM >= 10)
            {
                Path greetingsSourcePath = zipfs.getPath("/hello/Greetings.java");
                String source = toString(greetingsSourcePath);
                assertThat("Source", source, containsString("Hello from versions/\" + ver.get()"));

                Path detailedVerSourcePath = zipfs.getPath("/hello/DetailedVer.java");
                source = toString(detailedVerSourcePath);
                assertThat("Source", source, containsString("return 10;"));
            }

            // test readme
            if (RUNTIME_SUPPORTS_JEP238)
            {
                String source = toString(zipfs.getPath("README.txt"));
                assertThat("README Source", source, is(MultiReleaseJarCreator.README_VER9));
            }
            else
            {
                String source = toString(zipfs.getPath("README.txt"));
                assertThat("README Source", source, is(MultiReleaseJarCreator.README_ROOT));
            }
        }
    }

    @Test
    public void testMultiRelease11() throws IOException, URISyntaxException
    {
        MultiReleaseJarCreator creator = new MultiReleaseJarCreator(testingDir);
        Path jarPath = creator.createMultiReleaseJar11();
        URI uri = new URI("jar", jarPath.toUri().toASCIIString(), null);
        System.err.println("URI = " + uri);

        Map<String, Object> env = new HashMap<>();
        env.put("multi-release", "11");

        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            dumpZipFs(zipfs);

            if (RUNTIME_PLATFORM >= 11)
            {
                Path greetingsSourcePath = zipfs.getPath("/hello/Greetings.java");
                String source = toString(greetingsSourcePath);
                assertThat("Source", source, containsString("Hello from versions/\" + ver.get()"));

                source = toString(zipfs.getPath("/hello/DetailedVer.java"));
                assertThat("Source", source, containsString("return Integer.parseInt"));
            }

            // Test readme
            if (RUNTIME_SUPPORTS_JEP238)
            {
                // We are squarely in the JEP 238 supported JVM here.
                String source = toString(zipfs.getPath("README.txt"));
                assertThat("README Source", source, is(MultiReleaseJarCreator.README_VER11));
            }
            else
            {
                // We are not in a JEP 238 supported JVM.
                String source = toString(zipfs.getPath("README.txt"));
                assertThat("README Source", source, is(MultiReleaseJarCreator.README_ROOT));
            }
        }
    }

    @Test
    public void testMultiRelease11_AsRuntime() throws IOException, URISyntaxException
    {
        MultiReleaseJarCreator creator = new MultiReleaseJarCreator(testingDir);
        Path jarPath = creator.createMultiReleaseJar11();
        URI uri = new URI("jar", jarPath.toUri().toASCIIString(), null);
        System.err.println("URI = " + uri);

        Map<String, Object> env = new HashMap<>();
        env.put("multi-release", "runtime");

        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            dumpZipFs(zipfs);

            if (RUNTIME_PLATFORM >= 11)
            {
                Path greetingsSourcePath = zipfs.getPath("/hello/Greetings.java");
                String source = toString(greetingsSourcePath);
                assertThat("Source", source, containsString("Hello from versions/\" + ver.get()"));

                source = toString(zipfs.getPath("/hello/DetailedVer.java"));
                assertThat("Source", source, containsString("return Integer.parseInt"));
            }

            // Test readme
            if (RUNTIME_SUPPORTS_JEP238)
            {
                String expected = MultiReleaseJarCreator.README_ROOT;
                if (RUNTIME_PLATFORM >= 9)
                    expected = MultiReleaseJarCreator.README_VER9;
                if (RUNTIME_PLATFORM >= 11)
                    expected = MultiReleaseJarCreator.README_VER11;

                // We are squarely in the JEP 238 supported JVM here.
                String source = toString(zipfs.getPath("README.txt"));
                assertThat("README Source", source, is(expected));
            }
            else
            {
                // We are not in a JEP 238 supported JVM.
                String source = toString(zipfs.getPath("README.txt"));
                assertThat("README Source", source, is(MultiReleaseJarCreator.README_ROOT));
            }
        }
    }

    private String toString(Path path) throws IOException
    {
        try (BufferedReader reader = Files.newBufferedReader(path, UTF_8))
        {
            return IO.toString(reader);
        }
    }

    private void dumpZipFs(FileSystem zipfs)
    {
        zipfs.getRootDirectories().forEach((rootPath) ->
        {
            try
            {
                Files.walkFileTree(rootPath, new DumpTree(rootPath));
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        });
    }

    private class DumpTree implements FileVisitor<Path>
    {
        private final Path rootPath;

        public DumpTree(Path rootPath)
        {
            this.rootPath = rootPath;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
        {
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
        {
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
        {
            System.out.printf("[%s] %s (%,d bytes)%n", rootPath, file, Files.size(file));
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException
        {
            return FileVisitResult.TERMINATE;
        }
    }
}
