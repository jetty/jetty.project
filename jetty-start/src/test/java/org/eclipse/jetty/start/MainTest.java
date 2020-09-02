//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.start;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MainTest
{
    @BeforeEach
    public void clearSystemProperties()
    {
        System.setProperty("jetty.home", "");
        System.setProperty("jetty.base", "");
    }

    @Test
    public void testBasicProcessing() throws Exception
    {
        List<String> cmdLineArgs = new ArrayList<>();
        Path testJettyHome = MavenTestingUtils.getTestResourceDir("dist-home").toPath().toRealPath();
        cmdLineArgs.add("user.dir=" + testJettyHome);
        cmdLineArgs.add("jetty.home=" + testJettyHome);
        // cmdLineArgs.add("jetty.http.port=9090");

        Main main = new Main();
        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[cmdLineArgs.size()]));
        BaseHome baseHome = main.getBaseHome();
        // System.err.println(args);

        ConfigurationAssert.assertConfiguration(baseHome, args, "assert-home.txt");

        // System.err.println("StartArgs.props:");
        // args.getProperties().forEach(p->System.err.println(p));
        // System.err.println("BaseHome.props:");
        // baseHome.getConfigSources().getProps().forEach(p->System.err.println(p));

        Props props = args.getProperties();

        assertThat("Props(jetty.home)", props.getString("jetty.home"), is(baseHome.getHome()));
        assertThat("Props(jetty.home)", props.getString("jetty.home"), is(not(startsWith("file:"))));
        assertThat("Props(jetty.home.uri)", props.getString("jetty.home.uri") + "/", is(baseHome.getHomePath().toUri().toString()));
        assertThat("Props(jetty.base)", props.getString("jetty.base"), is(baseHome.getBase()));
        assertThat("Props(jetty.base)", props.getString("jetty.base"), is(not(startsWith("file:"))));
        assertThat("Props(jetty.base.uri)", props.getString("jetty.base.uri") + "/", is(baseHome.getBasePath().toUri().toString()));

        assertThat("System.getProperty(jetty.home)", System.getProperty("jetty.home"), is(baseHome.getHome()));
        assertThat("System.getProperty(jetty.home)", System.getProperty("jetty.home"), is(not(startsWith("file:"))));
        assertThat("System.getProperty(jetty.base)", System.getProperty("jetty.base"), is(baseHome.getBase()));
        assertThat("System.getProperty(jetty.base)", System.getProperty("jetty.base"), is(not(startsWith("file:"))));
    }

    @Test
    public void testStopProcessing() throws Exception
    {
        List<String> cmdLineArgs = new ArrayList<>();
        cmdLineArgs.add("--stop");
        cmdLineArgs.add("STOP.PORT=10000");
        cmdLineArgs.add("STOP.KEY=foo");
        cmdLineArgs.add("STOP.WAIT=300");

        Main main = new Main();
        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[cmdLineArgs.size()]));
        // System.err.println(args);

        // assertEquals(0, args.getEnabledModules().size(), "--stop should not build module tree");
        assertEquals("10000", args.getProperties().getString("STOP.PORT"), "--stop missing port");
        assertEquals("foo", args.getProperties().getString("STOP.KEY"), "--stop missing key");
        assertEquals("300", args.getProperties().getString("STOP.WAIT"), "--stop missing wait");
    }

    @Test
    @Disabled("Too noisy for general testing")
    public void testListConfig() throws Exception
    {
        List<String> cmdLineArgs = new ArrayList<>();
        File testJettyHome = MavenTestingUtils.getTestResourceDir("dist-home");
        cmdLineArgs.add("user.dir=" + testJettyHome);
        cmdLineArgs.add("jetty.home=" + testJettyHome);
        cmdLineArgs.add("--list-config");
        // cmdLineArgs.add("--debug");

        Main main = new Main();
        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[cmdLineArgs.size()]));
        main.listConfig(args);
    }

    @Test
    @Disabled("Just a bit noisy for general testing")
    public void testHelp() throws Exception
    {
        Main main = new Main();
        main.usage(false);
    }

    @Test
    public void testWithCommandLine() throws Exception
    {
        List<String> cmdLineArgs = new ArrayList<>();

        Path homePath = MavenTestingUtils.getTestResourceDir("dist-home").toPath().toRealPath();
        cmdLineArgs.add("jetty.home=" + homePath.toString());
        cmdLineArgs.add("user.dir=" + homePath.toString());

        // JVM args
        cmdLineArgs.add("--exec");
        cmdLineArgs.add("-Xms1024m");
        cmdLineArgs.add("-Xmx1024m");

        // Arbitrary Libs
        Path extraJar = MavenTestingUtils.getTestResourceFile("extra-libs/example.jar").toPath().toRealPath();
        Path extraDir = MavenTestingUtils.getTestResourceDir("extra-resources").toPath().toRealPath();

        assertThat("Extra Jar exists: " + extraJar, Files.exists(extraJar), is(true));
        assertThat("Extra Dir exists: " + extraDir, Files.exists(extraDir), is(true));

        StringBuilder lib = new StringBuilder();
        lib.append("--lib=");
        lib.append(extraJar.toString());
        lib.append(File.pathSeparator);
        lib.append(extraDir.toString());

        cmdLineArgs.add(lib.toString());

        // Arbitrary XMLs
        cmdLineArgs.add("config.xml");
        cmdLineArgs.add("config-foo.xml");
        cmdLineArgs.add("config-bar.xml");

        Main main = new Main();

        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[cmdLineArgs.size()]));
        BaseHome baseHome = main.getBaseHome();

        assertThat("jetty.home", baseHome.getHome(), is(homePath.toString()));
        assertThat("jetty.base", baseHome.getBase(), is(homePath.toString()));

        ConfigurationAssert.assertConfiguration(baseHome, args, "assert-home-with-jvm.txt");
    }

    @Test
    public void testJvmArgExpansion() throws Exception
    {
        List<String> cmdLineArgs = new ArrayList<>();

        Path homePath = MavenTestingUtils.getTestResourceDir("dist-home").toPath().toRealPath();
        cmdLineArgs.add("jetty.home=" + homePath.toString());
        cmdLineArgs.add("user.dir=" + homePath.toString());

        // JVM args
        cmdLineArgs.add("--exec");
        cmdLineArgs.add("-Xms1g");
        cmdLineArgs.add("-Xmx4g");
        cmdLineArgs.add("-Xloggc:${jetty.base}/logs/gc-${java.version}.log");

        Main main = new Main();

        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[cmdLineArgs.size()]));
        BaseHome baseHome = main.getBaseHome();

        assertThat("jetty.home", baseHome.getHome(), is(homePath.toString()));
        assertThat("jetty.base", baseHome.getBase(), is(homePath.toString()));

        CommandLineBuilder commandLineBuilder = args.getMainArgs(StartArgs.ALL_PARTS);
        String commandLine = commandLineBuilder.toString("\n");
        String expectedExpansion = String.format("-Xloggc:%s/logs/gc-%s.log",
            baseHome.getBase(), System.getProperty("java.version")
        );
        assertThat(commandLine, containsString(expectedExpansion));
    }

    @Test
    public void testWithModules() throws Exception
    {
        List<String> cmdLineArgs = new ArrayList<>();

        Path homePath = MavenTestingUtils.getTestResourceDir("dist-home").toPath().toRealPath();
        cmdLineArgs.add("jetty.home=" + homePath);
        cmdLineArgs.add("user.dir=" + homePath);
        cmdLineArgs.add("java.version=1.8.0_31");

        // Modules
        cmdLineArgs.add("--module=optional,extra");

        Main main = new Main();

        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[cmdLineArgs.size()]));
        BaseHome baseHome = main.getBaseHome();

        assertThat("jetty.home", baseHome.getHome(), is(homePath.toString()));
        assertThat("jetty.base", baseHome.getBase(), is(homePath.toString()));

        ConfigurationAssert.assertConfiguration(baseHome, args, "assert-home-with-module.txt");
    }

    @Test
    public void testJettyHomeWithSpaces() throws Exception
    {
        Path distPath = MavenTestingUtils.getTestResourceDir("dist-home").toPath().toRealPath();
        Path homePath = MavenTestingUtils.getTargetTestingPath().resolve("dist home with spaces");
        IO.copy(distPath.toFile(), homePath.toFile());
        homePath.resolve("lib/a library.jar").toFile().createNewFile();

        List<String> cmdLineArgs = new ArrayList<>();
        cmdLineArgs.add("user.dir=" + homePath);
        cmdLineArgs.add("jetty.home=" + homePath);
        cmdLineArgs.add("--lib=lib/a library.jar");

        Main main = new Main();
        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[cmdLineArgs.size()]));
        BaseHome baseHome = main.getBaseHome();

        assertThat("jetty.home", baseHome.getHome(), is(homePath.toString()));
        assertThat("jetty.base", baseHome.getBase(), is(homePath.toString()));

        ConfigurationAssert.assertConfiguration(baseHome, args, "assert-home-with-spaces.txt");
    }

    @Test
    public void testProvidersUsingDefault() throws Exception
    {
        Path homePath = MavenTestingUtils.getTestResourceDir("providers-home").toPath().toRealPath();

        List<String> cmdLineArgs = new ArrayList<>();
        cmdLineArgs.add("user.dir=" + homePath);
        cmdLineArgs.add("jetty.home=" + homePath);
        cmdLineArgs.add("--module=server");

        Main main = new Main();
        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[cmdLineArgs.size()]));
        BaseHome baseHome = main.getBaseHome();

        assertThat("jetty.home", baseHome.getHome(), is(homePath.toString()));
        assertThat("jetty.base", baseHome.getBase(), is(homePath.toString()));

        ConfigurationAssert.assertConfiguration(baseHome, args, "assert-providers-default.txt");
    }

    @Test
    public void testProvidersUsingSpecific() throws Exception
    {
        Path homePath = MavenTestingUtils.getTestResourceDir("providers-home").toPath().toRealPath();

        List<String> cmdLineArgs = new ArrayList<>();
        cmdLineArgs.add("user.dir=" + homePath);
        cmdLineArgs.add("jetty.home=" + homePath);
        cmdLineArgs.add("--module=server");
        cmdLineArgs.add("--module=logging-b");

        Main main = new Main();
        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[cmdLineArgs.size()]));
        BaseHome baseHome = main.getBaseHome();

        assertThat("jetty.home", baseHome.getHome(), is(homePath.toString()));
        assertThat("jetty.base", baseHome.getBase(), is(homePath.toString()));

        ConfigurationAssert.assertConfiguration(baseHome, args, "assert-providers-specific.txt");
    }
}
