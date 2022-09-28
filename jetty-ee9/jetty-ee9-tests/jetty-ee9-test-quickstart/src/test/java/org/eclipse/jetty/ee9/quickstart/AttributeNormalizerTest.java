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

package org.eclipse.jetty.ee9.quickstart;

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
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AttributeNormalizerTest
{
    public static Stream<Arguments> scenarios()
    {
        final List<Arguments> data = new ArrayList<>();
        final String arch = String.format("%s/%s", System.getProperty("os.name"), System.getProperty("os.arch"));

        String title;
        Path jettyHome;
        Path jettyBase;
        Path war;

        // ------
        title = "Typical Setup";
        jettyHome = asTargetPath(title, "jetty-distro");
        jettyBase = asTargetPath(title, "jetty-distro/demo.base");
        war = asTargetPath(title, "jetty-distro/demo.base/webapps/FOO");
        data.add(Arguments.of(new Scenario(arch, title, jettyHome, jettyBase, war)));

        // ------
        title = "Old Setup";
        jettyHome = asTargetPath(title, "jetty-distro");
        jettyBase = asTargetPath(title, "jetty-distro");
        war = asTargetPath(title, "jetty-distro/webapps/FOO");

        data.add(Arguments.of(new Scenario(arch, title, jettyHome, jettyBase, war)));

        // ------
        // This puts the jetty.home inside the jetty.base
        title = "Overlap Setup";
        jettyHome = asTargetPath(title, "app/dist");
        jettyBase = asTargetPath(title, "app");
        war = asTargetPath(title, "app/webapps/FOO");

        data.add(Arguments.of(new Scenario(arch, title, jettyHome, jettyBase, war)));

        // ------
        // This tests a path scenario often seen on various automatic deployments tooling
        // such as Kubernetes, CircleCI, TravisCI, and Jenkins.
        title = "Nasty Path Setup";
        jettyHome = asTargetPath(title, "app%2Fnasty/dist");
        jettyBase = asTargetPath(title, "app%2Fnasty/base");
        war = asTargetPath(title, "app%2Fnasty/base/webapps/FOO");

        data.add(Arguments.of(new Scenario(arch, title, jettyHome, jettyBase, war)));

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
        assertNormalize(scenario, scenario.war.toString(), scenario.war.toString());
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
    public void testNormalizeWarAsURI(final Scenario scenario)
    {
        // Normalize WAR as URI
        URI testWarURI = scenario.war.toUri();
        assertNormalize(scenario, testWarURI, "${WAR.uri}");
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNormalizeWarDeepAsPath(final Scenario scenario)
    {
        // Normalize WAR deep path as File
        Path testWarDeep = scenario.war.resolve("deep/ref");
        assertNormalize(scenario, testWarDeep, "${WAR.path}" + FS.separators("/deep/ref"));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNormalizeWarDeepAsString(final Scenario scenario)
    {
        // Normalize WAR deep path as String
        Path testWarDeep = scenario.war.resolve("deep/ref");
        assertNormalize(scenario, testWarDeep.toString(), testWarDeep.toString());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNormalizeWarDeepAsURI(final Scenario scenario)
    {
        // Normalize WAR deep path as URI
        Path testWarDeep = scenario.war.resolve("deep/ref");
        assertNormalize(scenario, testWarDeep.toUri(), "${WAR.uri}/deep/ref");
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testExpandWarDeep(final Scenario scenario)
    {
        // Expand WAR deep path
        Path testWarDeep = scenario.war.resolve("deep/ref");
        URI uri = URI.create("jar:" + testWarDeep.toUri().toASCIIString() + "!/other/file");
        assertExpandURI(scenario, "jar:${WAR.uri}/deep/ref!/other/file", uri);
    }

    public static class Scenario
    {
        private final Path jettyHome;
        private final Path jettyBase;
        private final Path war;
        private final String arch;
        private final String title;
        private final AttributeNormalizer normalizer;

        public Scenario(String arch, String title, Path jettyHome, Path jettyBase, Path war)
        {
            this.arch = arch;
            this.title = title;

            // Grab specific values of interest in general
            this.jettyHome = jettyHome;
            this.jettyBase = jettyBase;
            this.war = war;

            assertTrue(Files.exists(this.jettyHome));
            assertTrue(Files.exists(this.jettyBase));
            assertTrue(Files.exists(this.war));

            // Set some System Properties that AttributeNormalizer expects
            System.setProperty("jetty.home", jettyHome.toString());
            System.setProperty("jetty.base", jettyBase.toString());

            // Setup normalizer
            Resource webresource = resourceFactory.newResource(war);
            this.normalizer = new AttributeNormalizer(webresource);
        }

        @Override
        public String toString()
        {
            return String.format("%s [%s]", this.title, this.arch);
        }
    }
}

