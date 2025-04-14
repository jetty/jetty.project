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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
public class AttributeNormalizerTest
{
    public static Stream<Arguments> scenarios() throws IOException
    {
        final List<Arguments> data = new ArrayList<>();
        final String arch = String.format("%s/%s", System.getProperty("os.name"), System.getProperty("os.arch"));

        String title;
        Path jettyHome;
        Path jettyBase;
        Path war;

        // ------
        title = "Typical Setup";
        jettyHome = asTargetPath(title, "jetty-typical");
        jettyBase = asTargetPath(title, "jetty-typical/demo.base");
        war = asTargetPath(title, "jetty-typical/demo.base/webapps/FOO");
        data.add(Arguments.of(new Scenario(arch, title, jettyHome, jettyBase, resourceFactory.newResource(war))));

        // ------
        title = "Old Setup";
        jettyHome = asTargetPath(title, "jetty-old");
        jettyBase = asTargetPath(title, "jetty-old");
        war = asTargetPath(title, "jetty-old/webapps/FOO");
        if (!Files.exists(war.resolve("index.html")))
        {
            Files.createFile(war.resolve("index.html"));
            Files.createFile(war.resolve("favicon.ico"));
        }
        data.add(Arguments.of(new Scenario(arch, title, jettyHome, jettyBase, resourceFactory.newResource(war))));

        // ------
        // This puts the jetty.home inside the jetty.base
        title = "Overlap Setup";
        jettyHome = asTargetPath(title, "app/dist");
        jettyBase = asTargetPath(title, "app");
        war = asTargetPath(title, "app/webapps/FOO");
        data.add(Arguments.of(new Scenario(arch, title, jettyHome, jettyBase, resourceFactory.newResource(war))));

        // ------
        // This tests a path scenario often seen on various automatic deployments tooling
        // such as Kubernetes, CircleCI, TravisCI, and Jenkins.
        title = "Nasty Path Setup";
        jettyHome = asTargetPath(title, "app%2Fnasty/dist");
        jettyBase = asTargetPath(title, "app%2Fnasty/base");
        war = asTargetPath(title, "app%2Fnasty/base/webapps/FOO");
        data.add(Arguments.of(new Scenario(arch, title, jettyHome, jettyBase, resourceFactory.newResource(war))));

        return data.stream();
    }

    private static Path asTargetPath(String title, String subpath)
    {
        Path rootPath = MavenTestingUtils.getTargetTestingPath(title);
        FS.ensureDirExists(rootPath);
        Path path = rootPath.resolve(FS.separators(subpath));
        FS.ensureDirExists(path);

        return path;
    }

    private static Resource asTargetResource(String title, String subpath)
    {
        return resourceFactory.newResource(asTargetPath(title, subpath));
    }

    private static final Map<String, String> originalEnv = new HashMap<>();
    private static ResourceFactory.Closeable resourceFactory;

    @BeforeAll
    public static void rememberOriginalEnv()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
        resourceFactory = ResourceFactory.closeable();
        System.getProperties().stringPropertyNames()
            .forEach((name) -> originalEnv.put(name, System.getProperty(name)));
    }

    @AfterAll
    public static void afterAll()
    {
        IO.close(resourceFactory);
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @AfterEach
    public void restoreOriginalEnv()
    {
        originalEnv.forEach(AttributeNormalizerTest::restoreSystemProperty);
    }

    private static void restoreSystemProperty(String key, String value)
    {
        if (value == null)
        {
            System.clearProperty(key);
        }
        else
        {
            System.setProperty(key, value);
        }
    }

    private void assertNormalize(final Scenario scenario, Object o, String expected)
    {
        String result = scenario.normalizer.normalize(o);
        assertThat("normalize((" + o.getClass().getSimpleName() + ") " + Objects.toString(o, "<null>") + ")",
            result, is(expected));
    }

    private void assertExpandPath(final Scenario scenario, String line, String expected)
    {
        String result = scenario.normalizer.expand(line);

        // Treat output as strings
        assertThat("expand('" + line + "')", result, is(expected));
    }

    private void assertExpandURI(final Scenario scenario, String line, URI expected)
    {
        String result = scenario.normalizer.expand(line);

        URI resultURI = URI.create(result);
        assertThat("expand('" + line + "')", resultURI.getScheme(), is(expected.getScheme()));
        assertThat("expand('" + line + "')", resultURI.getPath(), is(expected.getPath()));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNormalizeWarAsString(final Scenario scenario)
    {
        // Normalize WAR as String path
        Path path = scenario.war.getPath();
        Assumptions.assumeTrue(path != null);
        assertNormalize(scenario, path.toString(), path.toString());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNormalizeJettyBaseAsFile(final Scenario scenario)
    {
        // Normalize jetty.base as File path
        assertNormalize(scenario, scenario.jettyBase.toFile(), "${jetty.base}");
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNormalizeJettyHomeAsFile(final Scenario scenario)
    {
        // Normalize jetty.home as File path
        String expected = scenario.jettyBase.equals(scenario.jettyHome) ? "${jetty.base}" : "${jetty.home}";
        assertNormalize(scenario, scenario.jettyHome.toFile(), expected);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNormalizeJettyBaseAsPath(final Scenario scenario)
    {
        // Normalize jetty.base as File path
        assertNormalize(scenario, scenario.jettyBase, "${jetty.base}");
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNormalizeJettyHomeAsPath(final Scenario scenario)
    {
        // Normalize jetty.home as File path
        String expected = scenario.jettyBase.equals(scenario.jettyHome) ? "${jetty.base}" : "${jetty.home}";
        assertNormalize(scenario, scenario.jettyHome, expected);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNormalizeJettyBaseAsURIWithAuthority(final Scenario scenario)
    {
        // Normalize jetty.base as URI path
        // Path.toUri() typically includes an URI authority
        assertNormalize(scenario, scenario.jettyBase.toUri(), "${jetty.base.uri}");
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNormalizeJettyBaseAsURIWithoutAuthority(final Scenario scenario)
    {
        // Normalize jetty.base as URI path
        // File.toURI() typically DOES NOT include an URI authority
        assertNormalize(scenario, scenario.jettyBase.toFile().toURI(), "${jetty.base.uri}");
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNormalizeJettyHomeAsURIWithAuthority(final Scenario scenario)
    {
        // Normalize jetty.home as URI path
        String expected = scenario.jettyBase.equals(scenario.jettyHome) ? "${jetty.base.uri}" : "${jetty.home.uri}";

        // Path.toUri() typically includes an URI authority
        assertNormalize(scenario, scenario.jettyHome.toUri(), expected);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNormalizeJettyHomeAsURIWithoutAuthority(final Scenario scenario)
    {
        // Normalize jetty.home as URI path
        String expected = scenario.jettyBase.equals(scenario.jettyHome) ? "${jetty.base.uri}" : "${jetty.home.uri}";

        // File.toURI() typically DOES NOT include an URI authority
        assertNormalize(scenario, scenario.jettyHome.toFile().toURI(), expected);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testExpandJettyBase(final Scenario scenario)
    {
        // Expand jetty.base
        assertExpandPath(scenario, "${jetty.base}", scenario.jettyBase.toString());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testExpandJettyHome(final Scenario scenario)
    {
        // Expand jetty.home
        assertExpandPath(scenario, "${jetty.home}", scenario.jettyHome.toString());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testExpandWebInfAsURI(final Scenario scenario)
    {
        // Expand
        assertExpandURI(scenario, "${WAR.uri}/WEB-INF/web.xml", scenario.webXml.toUri());
        assertExpandURI(scenario, "${WAR.uri}/WEB-INF/test.tld", scenario.testTld.toUri());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNormalizeWarAsURI(final Scenario scenario)
    {
        // Normalize WAR as URI
        URI testWarURI = scenario.war.getURI();
        Assumptions.assumeTrue(testWarURI != null);
        assertNormalize(scenario, testWarURI, "${WAR.uri}");
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNormalizeWarDeepAsPath(final Scenario scenario)
    {
        // Normalize WAR deep path as File
        Path testWarDeep = scenario.war.resolve("deep/ref").getPath();
        assertNormalize(scenario, testWarDeep, "${WAR.path}" + FS.separators("/deep/ref"));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNormalizeWarDeepAsString(final Scenario scenario)
    {
        // Normalize WAR deep path as String
        Path testWarDeep = scenario.war.resolve("deep/ref").getPath();
        assertNormalize(scenario, testWarDeep.toString(), testWarDeep.toString());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNormalizeWarDeepAsURI(final Scenario scenario)
    {
        // Normalize WAR deep path as URI
        Path testWarDeep = scenario.war.resolve("deep/ref").getPath();
        assertNormalize(scenario, testWarDeep.toUri(), "${WAR.uri}/deep/ref");
    }

    @Test
    public void testCombinedResource() throws Exception
    {
        String title = "CombinedResource Setup";
        Resource r1 = asTargetResource(title, "dir1");
        Resource r2 = asTargetResource(title, "dir2");
        Resource r3 = asTargetResource(title, "dir3");

        // Create files in each of these directories.
        Files.createFile(r1.getPath().resolve("file1")).toFile().deleteOnExit();
        Files.createFile(r2.getPath().resolve("file2")).toFile().deleteOnExit();
        Files.createFile(r3.getPath().resolve("file3")).toFile().deleteOnExit();

        Resource combined = CombinedResource.combine(List.of(r1, r2, r3));
        AttributeNormalizer normalizer = new AttributeNormalizer(combined);

        // Uses the appropriate resource if the target exists.
        assertThat(normalizer.expand("${WAR.uri}/file1"), containsString("/dir1/file1"));
        assertThat(normalizer.expand("${WAR.uri}/file2"), containsString("/dir2/file2"));
        assertThat(normalizer.expand("${WAR.uri}/file3"), containsString("/dir3/file3"));
        assertThat(normalizer.expand("${WAR.path}/file1"), containsString(FS.separators("/dir1/file1")));
        assertThat(normalizer.expand("${WAR.path}/file2"), containsString(FS.separators("/dir2/file2")));
        assertThat(normalizer.expand("${WAR.path}/file3"), containsString(FS.separators("/dir3/file3")));

        // If file cannot be found it just uses the first resource.
        assertThat(normalizer.expand("${WAR.uri}/file4"), containsString("/dir1/file4"));
        assertThat(normalizer.expand("${WAR.path}/file4"), containsString(File.separator + "dir1" + File.separator +  "file4"));
    }

    public static class Scenario
    {
        private final Path jettyHome;
        private final Path jettyBase;
        private final Resource war;
        private Path webXml;
        private Path testTld;
        private final String arch;
        private final String title;
        private final AttributeNormalizer normalizer;

        public Scenario(String arch, String title, Path jettyHome, Path jettyBase, Resource war)
        {
            this.arch = arch;
            this.title = title;

            // Grab specific values of interest in general
            this.jettyHome = jettyHome;
            this.jettyBase = jettyBase;
            this.war = war;

            assertTrue(Files.exists(this.jettyHome));
            assertTrue(Files.exists(this.jettyBase));
            assertTrue(war.exists());

            // Set some System Properties that AttributeNormalizer expects
            System.setProperty("jetty.home", jettyHome.toString());
            System.setProperty("jetty.base", jettyBase.toString());

            for (Resource w : war)
            {
                try
                {
                    Path webinf = w.getPath().resolve("WEB-INF");
                    if (!Files.exists(webinf))
                        Files.createDirectory(webinf);
                    Path deep = w.getPath().resolve("deep");
                    if (!Files.exists(deep))
                        Files.createDirectory(deep);
                    Path ref = deep.resolve("ref");
                    if (!Files.exists(ref))
                        Files.createFile(ref);

                    if (w.getFileName().equals("FOO") || w.getFileName().equals("WarA"))
                    {
                        webXml = webinf.resolve("web.xml");
                        if (!Files.exists(webXml))
                            Files.createFile(webXml);
                    }

                    if (w.getFileName().equals("FOO") || w.getFileName().equals("WarB"))
                    {
                        testTld = webinf.resolve("test.tld");
                        if (!Files.exists(testTld))
                            Files.createFile(testTld);
                    }
                }
                catch (IOException e)
                {
                    throw new RuntimeException(e);
                }
            }

            // Setup normalizer
            this.normalizer = new AttributeNormalizer(war);
        }

        @Override
        public String toString()
        {
            return String.format("%s [%s]", this.title, this.arch);
        }
    }
}

