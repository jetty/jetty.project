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

package org.eclipse.jetty.start.usecases;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.start.Props;
import org.eclipse.jetty.start.UsageException;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.PathAssert;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BasicTest extends AbstractUseCase
{
    private void setupDistHome() throws IOException
    {
        setupStandardHomeDir();

        Files.write(homeDir.resolve("start.ini"),
            Collections.singletonList(
                "--module=main"
            ),
            StandardCharsets.UTF_8);
    }

    @Test
    public void testBasicProcessing() throws Exception
    {
        setupDistHome();

        Files.write(baseDir.resolve("start.ini"),
            Collections.singletonList(
                "--module=main"
            ),
            StandardCharsets.UTF_8);

        // === Execute Main
        List<String> runArgs = new ArrayList<>();
        runArgs.add("--create-files");
        ExecResults results = exec(runArgs, true);

        // === Validate Resulting XMLs
        List<String> expectedXmls = Arrays.asList(
            "${jetty.home}/etc/base.xml",
            "${jetty.home}/etc/main.xml"
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));

        // === Validate Resulting LIBs
        List<String> expectedLibs = Arrays.asList(
            "${jetty.home}/lib/base.jar",
            "${jetty.home}/lib/main.jar",
            "${jetty.home}/lib/other.jar"
        );
        List<String> actualLibs = results.getLibs();
        assertThat("Libs", actualLibs, containsInAnyOrder(expectedLibs.toArray()));

        // === Validate Resulting Properties
        Set<String> expectedProperties = new HashSet<>();
        expectedProperties.add("main.prop=value0");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));

        // === Validate Specific Jetty Base Files/Dirs Exist
        PathAssert.assertDirExists("Required Directory: maindir/", results.baseHome.getPath("maindir/"));

        // === Validate home/base property uri values
        Props props = results.startArgs.getCoreEnvironment().getProperties();

        assertThat("Props(jetty.home)", props.getString("jetty.home"), is(results.baseHome.getHome()));
        assertThat("Props(jetty.home)", props.getString("jetty.home"), is(not(startsWith("file:"))));
        assertThat("Props(jetty.home.uri)", props.getString("jetty.home.uri") + "/", is(results.baseHome.getHomePath().toUri().toString()));
        assertThat("Props(jetty.base)", props.getString("jetty.base"), is(results.baseHome.getBase()));
        assertThat("Props(jetty.base)", props.getString("jetty.base"), is(not(startsWith("file:"))));
        assertThat("Props(jetty.base.uri)", props.getString("jetty.base.uri") + "/", is(results.baseHome.getBasePath().toUri().toString()));

        assertThat("System.getProperty(jetty.home)", System.getProperty("jetty.home"), is(results.baseHome.getHome()));
        assertThat("System.getProperty(jetty.home)", System.getProperty("jetty.home"), is(not(startsWith("file:"))));
        assertThat("System.getProperty(jetty.base)", System.getProperty("jetty.base"), is(results.baseHome.getBase()));
        assertThat("System.getProperty(jetty.base)", System.getProperty("jetty.base"), is(not(startsWith("file:"))));
    }

    @Test
    public void testAddModuleDoesNotExist() throws Exception
    {
        setupDistHome();

        Files.write(baseDir.resolve("start.ini"),
            List.of(
                "--module=main",
                "--module=does-not-exist"
            ),
            StandardCharsets.UTF_8);

        // === Execute Main
        List<String> runArgs = new ArrayList<>();
        runArgs.add("--create-files");
        UsageException usage = assertThrows(UsageException.class, () ->
        {
            ExecResults results = exec(runArgs, true);
            if (results.exception != null)
            {
                throw results.exception;
            }
        });
        assertThat(usage.getMessage(), containsString("Unknown module=[does-not-exist]"));
    }

    @Test
    public void testAddModuleDoesNotExistMultiple() throws Exception
    {
        setupDistHome();

        Files.write(baseDir.resolve("start.ini"),
            List.of(
                "--module=main",
                "--module=does-not-exist",
                "--module=also-not-present"
            ),
            StandardCharsets.UTF_8);

        // === Execute Main
        List<String> runArgs = new ArrayList<>();
        runArgs.add("--create-files");
        UsageException usage = assertThrows(UsageException.class, () ->
        {
            ExecResults results = exec(runArgs, true);
            if (results.exception != null)
            {
                throw results.exception;
            }
        });
        assertThat(usage.getMessage(), containsString("Unknown modules=[does-not-exist, also-not-present]"));
    }

    @Test
    public void testProvidersUsingDefault() throws Exception
    {
        Path homePath = MavenTestingUtils.getTestResourcePathDir("providers-home");

        Files.write(baseDir.resolve("start.ini"),
            Collections.singletonList(
                "--module=server"
            ),
            StandardCharsets.UTF_8);

        // === Execute Main
        List<String> runArgs = new ArrayList<>();
        runArgs.add("jetty.home=" + homePath);
        runArgs.add("--create-files");
        ExecResults results = exec(runArgs, true);

        // === Validate Resulting XMLs
        List<String> expectedXmls = Arrays.asList(
            "${jetty.home}/etc/logging-a.xml"
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));

        // === Validate Resulting LIBs
        List<String> actualLibs = results.getLibs();
        assertThat("Libs", actualLibs, empty());

        // === Validate Resulting Properties
        Set<String> expectedProperties = new HashSet<>();
        expectedProperties.add("logging.prop=a");
        expectedProperties.add("logging.a=true");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));
    }

    @Test
    public void testProvidersUsingSpecific() throws Exception
    {
        Path homePath = MavenTestingUtils.getTestResourcePathDir("providers-home");

        Files.write(baseDir.resolve("start.ini"),
            Collections.singletonList(
                "--module=server"
            ),
            StandardCharsets.UTF_8);

        // === Execute Main
        List<String> runArgs = new ArrayList<>();
        runArgs.add("jetty.home=" + homePath);
        runArgs.add("--create-files");
        runArgs.add("--module=logging-b");
        ExecResults results = exec(runArgs, true);

        // === Validate Resulting XMLs
        List<String> expectedXmls = Arrays.asList(
            "${jetty.home}/etc/logging-b.xml"
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));

        // === Validate Resulting LIBs
        List<String> actualLibs = results.getLibs();
        assertThat("Libs", actualLibs, empty());

        // === Validate Resulting Properties
        Set<String> expectedProperties = new HashSet<>();
        expectedProperties.add("logging.prop=b");
        expectedProperties.add("logging.b=true");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));
    }

    @Test
    public void testWithCommandLine() throws Exception
    {
        setupDistHome();

        Files.write(baseDir.resolve("start.ini"),
            Collections.singletonList(
                "--module=main"
            ),
            StandardCharsets.UTF_8);

        // === Execute Main
        List<String> runArgs = new ArrayList<>();
        runArgs.add("--create-files");

        // JVM args
        runArgs.add("--exec");
        runArgs.add("-Xms1g");
        runArgs.add("-Xmx1g");

        // Arbitrary Libs
        Path extraJar = MavenTestingUtils.getTestResourcePathFile("extra-libs/example.jar").toRealPath();
        Path extraDir = MavenTestingUtils.getTestResourcePathDir("extra-resources").toRealPath();

        assertThat("Extra Jar exists: " + extraJar, Files.exists(extraJar), is(true));
        assertThat("Extra Dir exists: " + extraDir, Files.exists(extraDir), is(true));

        String lib = "--lib=" + extraJar + File.pathSeparator + extraDir;
        runArgs.add(lib);

        // Arbitrary XMLs
        runArgs.add("config.xml");
        runArgs.add("config-foo.xml");
        runArgs.add("config-bar.xml");

        ExecResults results = exec(runArgs, true);

        // === Validate Resulting XMLs
        List<String> expectedXmls = Arrays.asList(
            "${jetty.home}/etc/base.xml",
            "${jetty.home}/etc/main.xml",
            "${jetty.home}/etc/config.xml",
            "${jetty.home}/etc/config-foo.xml",
            "${jetty.home}/etc/config-bar.xml"
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));

        // === Validate Resulting LIBs
        List<String> expectedLibs = Arrays.asList(
            extraJar.toString(),
            extraDir.toString(),
            "${jetty.home}/lib/base.jar",
            "${jetty.home}/lib/main.jar",
            "${jetty.home}/lib/other.jar"
        );
        List<String> actualLibs = results.getLibs();
        assertThat("Libs", actualLibs, containsInAnyOrder(expectedLibs.toArray()));

        // === Validate Resulting Properties
        Set<String> expectedProperties = new HashSet<>();
        expectedProperties.add("main.prop=value0");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));

        // === Validate Specific Jetty Base Files/Dirs Exist
        PathAssert.assertDirExists("Required Directory: maindir/", results.baseHome.getPath("maindir/"));

        // === Validate JVM args
        List<String> expectedJvmArgs = Arrays.asList(
            "-Xms1g",
            "-Xmx1g"
        );
        List<String> actualJvmArgs = results.startArgs.getJvmArgs();
        assertThat("JVM Args", actualJvmArgs, contains(expectedJvmArgs.toArray()));
    }

    @Test
    public void testWithModulesFromCommandLine() throws Exception
    {
        setupDistHome();

        Files.write(baseDir.resolve("start.ini"),
            Collections.singletonList(
                "--module=main"
            ),
            StandardCharsets.UTF_8);

        // === Execute Main
        List<String> runArgs = new ArrayList<>();
        runArgs.add("--create-files");
        runArgs.add("java.version=1.8.0_31");

        // Modules
        runArgs.add("--module=optional,extra");

        ExecResults results = exec(runArgs, true);

        // === Validate Resulting XMLs
        List<String> expectedXmls = Arrays.asList(
            "${jetty.home}/etc/optional.xml",
            "${jetty.home}/etc/base.xml",
            "${jetty.home}/etc/main.xml",
            "${jetty.home}/etc/extra.xml"
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));

        // === Validate Resulting LIBs
        List<String> expectedLibs = Arrays.asList(
            "${jetty.home}/lib/optional.jar",
            "${jetty.home}/lib/base.jar",
            "${jetty.home}/lib/main.jar",
            "${jetty.home}/lib/other.jar",
            "${jetty.home}/lib/extra/extra0.jar",
            "${jetty.home}/lib/extra/extra1.jar"
        );
        List<String> actualLibs = results.getLibs();
        assertThat("Libs", actualLibs, containsInAnyOrder(expectedLibs.toArray()));

        // === Validate Resulting Properties
        Set<String> expectedProperties = new HashSet<>();
        expectedProperties.add("extra.prop=value0");
        expectedProperties.add("main.prop=value0");
        expectedProperties.add("optional.prop=value0");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));

        // === Validate Specific Jetty Base Files/Dirs Exist
        PathAssert.assertDirExists("Required Directory: maindir/", results.baseHome.getPath("maindir/"));
    }

    @Test
    public void testHomeWithSpaces() throws Exception
    {
        homeDir = workDir.getPath().resolve("jetty home with spaces");
        FS.ensureDirExists(homeDir);

        setupDistHome();

        // === Execute Main
        List<String> runArgs = new ArrayList<>();
        runArgs.add("--module=main");
        ExecResults results = exec(runArgs, true);

        // === Validate Resulting XMLs
        List<String> expectedXmls = Arrays.asList(
            "${jetty.home}/etc/base.xml",
            "${jetty.home}/etc/main.xml"
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));

        // === Validate Resulting LIBs
        List<String> expectedLibs = Arrays.asList(
            "${jetty.home}/lib/base.jar",
            "${jetty.home}/lib/main.jar",
            "${jetty.home}/lib/other.jar"
        );
        List<String> actualLibs = results.getLibs();
        assertThat("Libs", actualLibs, containsInAnyOrder(expectedLibs.toArray()));

        // === Validate Resulting Properties
        Set<String> expectedProperties = new HashSet<>();
        expectedProperties.add("main.prop=value0");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));

        // === Validate home/base property uri values
        Props props = results.startArgs.getCoreEnvironment().getProperties();

        assertThat("Props(jetty.home)", props.getString("jetty.home"), is(results.baseHome.getHome()));
        assertThat("Props(jetty.home)", props.getString("jetty.home"), is(not(startsWith("file:"))));
        assertThat("Props(jetty.home.uri)", props.getString("jetty.home.uri") + "/", is(results.baseHome.getHomePath().toUri().toString()));
        assertThat("Props(jetty.base)", props.getString("jetty.base"), is(results.baseHome.getBase()));
        assertThat("Props(jetty.base)", props.getString("jetty.base"), is(not(startsWith("file:"))));
        assertThat("Props(jetty.base.uri)", props.getString("jetty.base.uri") + "/", is(results.baseHome.getBasePath().toUri().toString()));

        assertThat("System.getProperty(jetty.home)", System.getProperty("jetty.home"), is(results.baseHome.getHome()));
        assertThat("System.getProperty(jetty.home)", System.getProperty("jetty.home"), is(not(startsWith("file:"))));
        assertThat("System.getProperty(jetty.base)", System.getProperty("jetty.base"), is(results.baseHome.getBase()));
        assertThat("System.getProperty(jetty.base)", System.getProperty("jetty.base"), is(not(startsWith("file:"))));
    }
}
