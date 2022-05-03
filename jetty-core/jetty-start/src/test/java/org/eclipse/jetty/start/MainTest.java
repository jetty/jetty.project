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

package org.eclipse.jetty.start;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
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
        assertEquals("10000", args.getCoreEnvironment().getProperties().getString("STOP.PORT"), "--stop missing port");
        assertEquals("foo", args.getCoreEnvironment().getProperties().getString("STOP.KEY"), "--stop missing key");
        assertEquals("300", args.getCoreEnvironment().getProperties().getString("STOP.WAIT"), "--stop missing wait");
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
    @Disabled("Just a bit noisy for general testing")
    public void testHelp() throws Exception
    {
        Main main = new Main();
        main.usage(false);
    }

    @Test
    public void testJvmArgExpansion() throws Exception
    {
        List<String> cmdLineArgs = new ArrayList<>();

        Path homePath = MavenTestingUtils.getTestResourceDir("dist-home").toPath().toRealPath();
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
        String commandLine = commandLineBuilder.toString("\n");
        String expectedExpansion = String.format("-Xloggc:%s/logs/gc-%s.log",
            baseHome.getBase(), System.getProperty("java.version")
        );
        assertThat(commandLine, containsString(expectedExpansion));
    }
}
