//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.quickstart;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class AttributeNormalizerTest
{
    public static Stream<Arguments> scenarios() throws IOException
    {
        List<Scenario> data = new ArrayList<>();

        String arch = String.format("%s/%s", System.getProperty("os.name"), System.getProperty("os.arch"));

        String title;
        Map<String, String> env;

        // ------
        title = "Typical Setup";

        env = new HashMap<>();
        env.put("jetty.home", asTargetPath(title, "jetty-distro"));
        env.put("jetty.base", asTargetPath(title, "jetty-distro/demo.base"));
        env.put("WAR", asTargetPath(title, "jetty-distro/demo.base/webapps/FOO"));

        data.add(new Scenario(arch, title, env));

        // ------
        title = "Old Setup";

        env = new HashMap<>();
        env.put("jetty.home", asTargetPath(title, "jetty-distro"));
        env.put("jetty.base", asTargetPath(title, "jetty-distro"));
        env.put("WAR", asTargetPath(title, "jetty-distro/webapps/FOO"));

        data.add(new Scenario(arch, title, env));

        // ------
        // This puts the jetty.home inside of the jetty.base
        title = "Overlap Setup";
        env = new HashMap<>();
        env.put("jetty.home", asTargetPath(title, "app/dist"));
        env.put("jetty.base", asTargetPath(title, "app"));
        env.put("WAR", asTargetPath(title, "app/webapps/FOO"));

        data.add(new Scenario(arch, title, env));

        // ------
        // This tests a path scenario often seen on various automatic deployments tooling
        // such as Kubernetes, CircleCI, TravisCI, and Jenkins.
        title = "Nasty Path Setup";
        env = new HashMap<>();
        env.put("jetty.home", asTargetPath(title, "app%2Fnasty/dist"));
        env.put("jetty.base", asTargetPath(title, "app%2Fnasty/base"));
        env.put("WAR", asTargetPath(title, "app%2Fnasty/base/webapps/FOO"));

        data.add(new Scenario(arch, title, env));

        // ------
        title = "Root Path Setup";
        env = new HashMap<>();
        Path rootPath = MavenTestingUtils.getTargetPath().getRoot();
        env.put("jetty.home", rootPath.toString());
        env.put("jetty.base", rootPath.toString());
        env.put("WAR", rootPath.resolve("webapps/root").toString());

        data.add(new Scenario(arch, title, env));
        return data.stream().map(Arguments::of);
    }

    private static final String asTargetPath(String title, String subpath)
    {
        Path rootPath = MavenTestingUtils.getTargetTestingPath(title);
        FS.ensureDirExists(rootPath);
        Path path = rootPath.resolve(FS.separators(subpath));
        FS.ensureDirExists(path);

        return path.toString();
    }

    private static Map<String, String> originalEnv = new HashMap<>();

    @BeforeAll
    public static void rememberOriginalEnv()
    {
        System.getProperties().stringPropertyNames()
            .forEach((name) ->
            {
                originalEnv.put(name, System.getProperty(name));
            });
    }

    @AfterEach
    public void restoreOriginalEnv()
    {
        originalEnv.forEach((name, value) ->
        {
            EnvUtils.restoreSystemProperty(name, value);
        });
    }

    private void assertNormalize(final Scenario scenario, Object o, String expected)
    {
        String result = scenario.normalizer.normalize(o);
        assertThat("normalize((" + o.getClass().getSimpleName() + ") " + o.toString() + ")",
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
        assertNormalize(scenario, scenario.war, scenario.war); // only URL, File, URI are supported
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
        URI testWarURI = new File(scenario.war).toURI();
        assertNormalize(scenario, testWarURI, "${WAR.uri}");
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNormalizeWarDeepAsFile(final Scenario scenario)
    {
        // Normalize WAR deep path as File
        File testWarDeep = new File(new File(scenario.war), FS.separators("deep/ref")).getAbsoluteFile();
        assertNormalize(scenario, testWarDeep, "${WAR.path}" + FS.separators("/deep/ref"));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNormalizeWarDeepAsString(final Scenario scenario)
    {
        // Normalize WAR deep path as String
        File testWarDeep = new File(new File(scenario.war), FS.separators("deep/ref")).getAbsoluteFile();
        assertNormalize(scenario, testWarDeep.toString(), testWarDeep.toString());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNormalizeWarDeepAsURI(final Scenario scenario)
    {
        // Normalize WAR deep path as URI
        File testWarDeep = new File(new File(scenario.war), FS.separators("deep/ref")).getAbsoluteFile();
        assertNormalize(scenario, testWarDeep.toURI(), "${WAR.uri}/deep/ref");
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testExpandWarDeep(final Scenario scenario)
    {
        // Expand WAR deep path
        File testWarDeep = new File(new File(scenario.war), FS.separators("deep/ref"));
        URI uri = URI.create("jar:" + testWarDeep.toURI().toASCIIString() + "!/other/file");
        assertExpandURI(scenario, "jar:${WAR.uri}/deep/ref!/other/file", uri);
    }

    public static class Scenario
    {
        private final Path jettyHome;
        private final Path jettyBase;
        private final String war;
        private final String arch;
        private final String title;
        private final Map<String, String> env;
        private final AttributeNormalizer normalizer;

        public Scenario(String arch, String title, Map<String, String> env) throws IOException
        {
            this.arch = arch;
            this.title = title;
            this.env = env;

            // Grab specific values of interest in general
            jettyHome = new File(env.get("jetty.home")).toPath().toAbsolutePath();
            jettyBase = new File(env.get("jetty.base")).toPath().toAbsolutePath();
            war = env.get("WAR");

            // Set environment (skipping null and WAR)
            env.entrySet().stream()
                .filter((e) -> e.getValue() != null && !e.getKey().equalsIgnoreCase("WAR"))
                .forEach((entry) -> System.setProperty(entry.getKey(), entry.getValue()));

            // Setup normalizer
            Resource webresource = Resource.newResource(war);
            this.normalizer = new AttributeNormalizer(webresource);
        }

        @Override
        public String toString()
        {
            return String.format("%s [%s]", this.title, this.arch);
        }
    }
}

