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

package org.eclipse.jetty.start;

import java.io.File;
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
        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[0]));
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
