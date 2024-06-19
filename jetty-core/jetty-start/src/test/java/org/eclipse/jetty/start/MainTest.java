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

package org.eclipse.jetty.start;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MainTest
{
    @BeforeEach
    public void clearSystemProperties()
    {
        System.setProperty("jetty.home", "");
        System.setProperty("jetty.base", "");
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
        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[0]));

        // assertEquals(0, args.getEnabledModules().size(), "--stop should not build module tree");
        assertEquals("10000", args.getJettyEnvironment().getProperties().getString("STOP.PORT"), "--stop missing port");
        assertEquals("foo", args.getJettyEnvironment().getProperties().getString("STOP.KEY"), "--stop missing key");
        assertEquals("300", args.getJettyEnvironment().getProperties().getString("STOP.WAIT"), "--stop missing wait");
    }

    @Test
    public void testListConfig() throws Exception
    {
        List<String> cmdLineArgs = new ArrayList<>();
        File testJettyHome = MavenTestingUtils.getTestResourceDir("dist-home");
        cmdLineArgs.add("user.dir=" + testJettyHome);
        cmdLineArgs.add("-Duser.dir=foo"); // used to test "source" display on "Java Environment"
        cmdLineArgs.add("jetty.home=" + testJettyHome);
        cmdLineArgs.add("--list-config");

        List<String> output;
        Main main = new Main();
        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[0]));
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             PrintStream out = new PrintStream(baos, true, StandardCharsets.UTF_8))
        {
            main.listConfig(out, args);
            out.flush();
            output = List.of(baos.toString(StandardCharsets.UTF_8).split(System.lineSeparator()));
        }

        // Test a System Property that comes from JVM
        String javaVersionLine = output.stream().filter((line) -> line.contains("java.version ="))
            .findFirst().orElseThrow();
        assertThat("java.version should have no indicated source", javaVersionLine, not(containsString("(null)")));

        String userDirLine = output.stream().filter((line) -> line.startsWith(" user.dir ="))
            .findFirst().orElseThrow();
        assertThat("A source of 'null' is pointless", userDirLine, not(containsString("(null)")));
        assertThat("user.dir should indicate that it was specified on the command line", userDirLine, containsString("(<command-line>)"));
    }

    @Test
    public void testUnknownDistroCommand() throws Exception
    {
        List<String> cmdLineArgs = new ArrayList<>();
        File testJettyHome = MavenTestingUtils.getTestResourceDir("dist-home");
        Path testJettyBase = MavenTestingUtils.getTargetTestingPath("base-example-unknown");
        FS.ensureDirectoryExists(testJettyBase);
        Path zedIni = testJettyBase.resolve("start.d/zed.ini");
        FS.ensureDirectoryExists(zedIni.getParent());
        Files.writeString(zedIni, "--zed-0-zed");
        cmdLineArgs.add("jetty.home=" + testJettyHome);
        cmdLineArgs.add("jetty.base=" + testJettyBase);
        cmdLineArgs.add("main.class=" + PropertyDump.class.getName());
        cmdLineArgs.add("--modules=base");
        cmdLineArgs.add("--foople");
        cmdLineArgs.add("-Dzed.key=0.value");

        List<String> output;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             PrintStream out = new PrintStream(baos, true, StandardCharsets.UTF_8))
        {
            PrintStream originalStream = StartLog.setStream(new PrintStream(out));
            try
            {
                Main main = new Main();
                StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[0]));
                main.start(args);
                out.flush();
                output = List.of(baos.toString(StandardCharsets.UTF_8).split(System.lineSeparator()));
            }
            finally
            {
                StartLog.setStream(originalStream);
            }
        }

        // Test a System Property that comes from JVM
        List<String> warnings = output.stream().filter((line) -> line.startsWith("WARN")).toList();
        // warnings.forEach(System.out::println);
        Iterator<String> warningIter = warnings.iterator();

        assertThat("Announcement", warningIter.next(), containsString("Unknown Arguments detected."));
        assertThat("System Prop on command line detail", warningIter.next(), containsString("Argument: -Dzed.key=0.value (interpreted as a System property, from <command-line>"));
        assertThat("JVM Arg in ini detail", warningIter.next(), containsString("Argument: --zed-0-zed (interpreted as a JVM argument, from " + zedIni));
        assertThat("JVM Arg on command line detail", warningIter.next(), containsString("Argument: --foople (interpreted as a JVM argument, from <command-line>"));
    }

    /**
     * A test to ensure that the usage text is still present and not accidentally deleted.
     */
    @Test
    public void testUsageHelpStillThere() throws Exception
    {
        Path usageFile = MavenPaths.findMainResourceFile("org/eclipse/jetty/start/usage.txt");
        assertTrue(Files.exists(usageFile));
        assertTrue(Files.isRegularFile(usageFile));
    }

    @Test
    public void testJvmArgExpansion() throws Exception
    {
        List<String> cmdLineArgs = new ArrayList<>();

        Path homePath = MavenTestingUtils.getTestResourcePathDir("dist-home").toRealPath();
        cmdLineArgs.add("jetty.home=" + homePath);
        cmdLineArgs.add("user.dir=" + homePath);

        // JVM args
        cmdLineArgs.add("--exec");
        cmdLineArgs.add("-Xms1g");
        cmdLineArgs.add("-Xmx4g");
        cmdLineArgs.add("-Xloggc:${jetty.base}/logs/gc-${java.version}.log");

        Main main = new Main();

        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[0]));
        BaseHome baseHome = main.getBaseHome();

        assertThat("jetty.home", baseHome.getHome(), is(homePath.toString()));
        assertThat("jetty.base", baseHome.getBase(), is(homePath.toString()));

        CommandLineBuilder commandLineBuilder = args.getMainArgs(StartArgs.ALL_PARTS);
        String commandLine = commandLineBuilder.toString();
        String expectedExpansion = String.format("-Xloggc:%s/logs/gc-%s.log",
            baseHome.getBase(), System.getProperty("java.version")
        );
        assertThat(commandLine, containsString(expectedExpansion));
    }

    @Test
    public void testModulesDeclaredTwice() throws Exception
    {
        List<String> cmdLineArgs = new ArrayList<>();

        Path homePath = MavenPaths.findTestResourceDir("dist-home");
        Path basePath = MavenPaths.findTestResourceDir("overdeclared-modules");
        cmdLineArgs.add("jetty.home=" + homePath);
        cmdLineArgs.add("user.dir=" + basePath);

        Main main = new Main();

        cmdLineArgs.add("--module=main");

        // The "main" module is enabled in both ...
        // 1) overdeclared-modules/start.d/config.ini
        // 2) command-line
        // This shouldn't result in an error
        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[0]));

        assertThat(args.getSelectedModules(), hasItem("main"));
    }
}
