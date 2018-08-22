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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.IO;
import org.junit.Rule;
import org.junit.Test;

public class ZipFsResourceTest
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
    public void testBasicJar() throws IOException
    {
        MultiReleaseJarCreator creator = new MultiReleaseJarCreator(testingDir);
        Path jarPath = creator.createBasicJar();

        try (ZipFsResource resource = new ZipFsResource(jarPath))
        {
            dumpZipFs(resource);

            Resource greetingsSourcePath = resource.addPath("/hello/Greetings.java");
            String source = toString(greetingsSourcePath);

            assertThat("Source", source, containsString("Hello from zipfs base"));

            source = toString(resource.addPath("README.txt"));
            assertThat("README Source", source, is(MultiReleaseJarCreator.README_ROOT));
        }
    }

    @Test
    public void testMultiRelease9() throws IOException, URISyntaxException
    {
        MultiReleaseJarCreator creator = new MultiReleaseJarCreator(testingDir);
        Path jarPath = creator.createMultiReleaseJar9();

        try (ZipFsResource resource = new ZipFsResource(jarPath.toUri(), "9"))
        {
            dumpZipFs(resource);

            if (RUNTIME_PLATFORM >= 9)
            {
                Resource greetingsSourcePath = resource.addPath("/hello/Greetings.java");
                String source = toString(greetingsSourcePath);
                assertThat("Source", source, containsString("Hello from versions/9"));
            }

            // test readme
            if (RUNTIME_SUPPORTS_JEP238)
            {
                String source = toString(resource.addPath("README.txt"));
                assertThat("README Source", source, is(MultiReleaseJarCreator.README_VER9));
            }
            else
            {
                String source = toString(resource.addPath("README.txt"));
                assertThat("README Source", source, is(MultiReleaseJarCreator.README_ROOT));
            }
        }
    }

    @Test
    public void testMultiOpen() throws Exception
    {
        MultiReleaseJarCreator creator = new MultiReleaseJarCreator(testingDir);
        Path jarPath = creator.createMultiReleaseJar9();

        try(ZipFsResource resource1 = new ZipFsResource(jarPath.toUri(), "9"))
        {
            System.out.println("resource1 = " + resource1);
            try(ZipFsResource resource2 = new ZipFsResource(jarPath.toUri(), "9"))
            {
                System.out.println("resource2 = " + resource2);
            }
        }

        // reference counting
        // close issues?
        // garbage collection?
    }

    @Test
    public void testMultiRelease10() throws IOException, URISyntaxException
    {
        MultiReleaseJarCreator creator = new MultiReleaseJarCreator(testingDir);
        Path jarPath = creator.createMultiReleaseJar10();

        try (ZipFsResource resource = new ZipFsResource(jarPath.toUri(), "10"))
        {
            dumpZipFs(resource);

            if (RUNTIME_PLATFORM >= 10)
            {
                Resource greetingsSourcePath = resource.addPath("/hello/Greetings.java");
                String source = toString(greetingsSourcePath);
                assertThat("Source", source, containsString("Hello from versions/\" + ver.get()"));

                Resource detailedVerSourcePath = resource.addPath("/hello/DetailedVer.java");
                source = toString(detailedVerSourcePath);
                assertThat("Source", source, containsString("return 10;"));
            }

            // test readme
            if (RUNTIME_SUPPORTS_JEP238)
            {
                String source = toString(resource.addPath("README.txt"));
                assertThat("README Source", source, is(MultiReleaseJarCreator.README_VER9));
            }
            else
            {
                String source = toString(resource.addPath("README.txt"));
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

        try (ZipFsResource resource = new ZipFsResource(jarPath.toUri(), "11"))
        {
            dumpZipFs(resource);

            if (RUNTIME_PLATFORM >= 11)
            {
                Resource greetingsSourcePath = resource.addPath("/hello/Greetings.java");
                String source = toString(greetingsSourcePath);
                assertThat("Source", source, containsString("Hello from versions/\" + ver.get()"));

                source = toString(resource.addPath("/hello/DetailedVer.java"));
                assertThat("Source", source, containsString("return Integer.parseInt"));
            }

            // Test readme
            if (RUNTIME_SUPPORTS_JEP238)
            {
                // We are squarely in the JEP 238 supported JVM here.
                String source = toString(resource.addPath("README.txt"));
                assertThat("README Source", source, is(MultiReleaseJarCreator.README_VER11));
            }
            else
            {
                // We are not in a JEP 238 supported JVM.
                String source = toString(resource.addPath("README.txt"));
                assertThat("README Source", source, is(MultiReleaseJarCreator.README_ROOT));
            }
        }
    }

    @Test
    public void testMultiRelease11_AsRuntime() throws IOException, URISyntaxException
    {
        MultiReleaseJarCreator creator = new MultiReleaseJarCreator(testingDir);
        Path jarPath = creator.createMultiReleaseJar11();

        try (ZipFsResource resource = new ZipFsResource(jarPath))
        {
            dumpZipFs(resource);

            if (RUNTIME_PLATFORM >= 11)
            {
                Resource greetingsSourcePath = resource.addPath("/hello/Greetings.java");
                String source = toString(greetingsSourcePath);
                assertThat("Source", source, containsString("Hello from versions/\" + ver.get()"));

                source = toString(resource.addPath("/hello/DetailedVer.java"));
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
                String source = toString(resource.addPath("README.txt"));
                assertThat("README Source", source, is(expected));
            }
            else
            {
                // We are not in a JEP 238 supported JVM.
                String source = toString(resource.addPath("README.txt"));
                assertThat("README Source", source, is(MultiReleaseJarCreator.README_ROOT));
            }
        }
    }

    private String toString(Resource resource) throws IOException
    {
        try (InputStream stream = resource.getInputStream();
             Reader reader = new InputStreamReader(stream, UTF_8))
        {
            return IO.toString(reader);
        }
    }

    private void dumpZipFs(ZipFsResource resource)
    {
        try
        {
            recurseResourcesDir(resource.getFile().getName(), "", resource);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    private void recurseResourcesDir(String name, String prefix, Resource resource)
    {
        for (String entry : resource.list())
        {
            try
            {
                String filename = entry;
                // hack to work around bug
                while(filename.endsWith("/"))
                    filename = filename.substring(0, filename.length()-1);

                Resource subEntry = resource.addPath(filename);
                System.out.printf("[%s] %s%s (%s - %,d bytes)%n",
                        name,
                        prefix,
                        entry,
                        subEntry.isDirectory() ? "DIR" : "FILE",
                        subEntry.length());

                if (subEntry.isDirectory())
                    recurseResourcesDir(name, prefix + entry, subEntry);
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }
}
