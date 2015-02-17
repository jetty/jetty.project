//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class MainTest
{
    @Rule
    public TestTracker ttracker = new TestTracker();
    
    @Before
    public void clearSystemProperties()
    {
        System.setProperty("jetty.home","");
        System.setProperty("jetty.base","");
    }

    @Test
    public void testBasicProcessing() throws Exception
    {
        List<String> cmdLineArgs = new ArrayList<>();
        File testJettyHome = MavenTestingUtils.getTestResourceDir("dist-home").getAbsoluteFile();
        cmdLineArgs.add("user.dir=" + testJettyHome);
        cmdLineArgs.add("jetty.home=" + testJettyHome);
        cmdLineArgs.add("jetty.port=9090");

        Main main = new Main();
        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[cmdLineArgs.size()]));
        BaseHome baseHome = main.getBaseHome();
        System.err.println(args);

        ConfigurationAssert.assertConfiguration(baseHome,args,"assert-home.txt");
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
        System.err.println(args);

        // Assert.assertEquals("--stop should not build module tree", 0, args.getEnabledModules().size());
        assertEquals("--stop missing port","10000",args.getProperties().getString("STOP.PORT"));
        assertEquals("--stop missing key","foo",args.getProperties().getString("STOP.KEY"));
        assertEquals("--stop missing wait","300",args.getProperties().getString("STOP.WAIT"));
    }

    @Test
    @Ignore("Just a bit noisy for general testing")
    public void testListConfig() throws Exception
    {
        List<String> cmdLineArgs = new ArrayList<>();
        File testJettyHome = MavenTestingUtils.getTestResourceDir("dist-home");
        cmdLineArgs.add("jetty.home=" + testJettyHome);
        cmdLineArgs.add("jetty.port=9090");
        cmdLineArgs.add("--list-config");
        // cmdLineArgs.add("--debug");

        Main main = new Main();
        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[cmdLineArgs.size()]));
        main.listConfig(args);
    }

    @Test
    @Ignore("Just a bit noisy for general testing")
    public void testHelp() throws Exception
    {
        Main main = new Main();
        main.usage(false);
    }

    @Test
    public void testWithCommandLine() throws Exception
    {
        List<String> cmdLineArgs = new ArrayList<>();

        File homePath = MavenTestingUtils.getTestResourceDir("dist-home").getAbsoluteFile();
        cmdLineArgs.add("jetty.home=" + homePath);
        cmdLineArgs.add("user.dir=" + homePath);

        // JVM args
        cmdLineArgs.add("--exec");
        cmdLineArgs.add("-Xms1024m");
        cmdLineArgs.add("-Xmx1024m");

        // Arbitrary Libs
        Path extraJar = MavenTestingUtils.getTestResourceFile("extra-libs/example.jar").toPath().normalize();
        Path extraDir = MavenTestingUtils.getTestResourceDir("extra-resources").toPath().normalize();
        
        extraJar = extraJar.toAbsolutePath();
        extraDir = extraDir.toAbsolutePath();
        
        assertThat("Extra Jar exists: " + extraJar,Files.exists(extraJar),is(true));
        assertThat("Extra Dir exists: " + extraDir,Files.exists(extraDir),is(true));
        
        StringBuilder lib = new StringBuilder();
        lib.append("--lib=");
        lib.append(extraJar.toString());
        lib.append(File.pathSeparator);
        lib.append(extraDir.toString());
        
        cmdLineArgs.add(lib.toString());

        // Arbitrary XMLs
        cmdLineArgs.add("jetty.xml");
        cmdLineArgs.add("jetty-jmx.xml");
        cmdLineArgs.add("jetty-logging.xml");

        Main main = new Main();

        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[cmdLineArgs.size()]));
        BaseHome baseHome = main.getBaseHome();

        assertThat("jetty.home",baseHome.getHome(),is(homePath.getAbsolutePath()));
        assertThat("jetty.base",baseHome.getBase(),is(homePath.getAbsolutePath()));

        ConfigurationAssert.assertConfiguration(baseHome,args,"assert-home-with-jvm.txt");
    }
    
    @Test
    public void testWithHttp2() throws Exception
    {
        List<String> cmdLineArgs = new ArrayList<>();

        File homePath = MavenTestingUtils.getTestResourceDir("dist-home").getAbsoluteFile();
        cmdLineArgs.add("jetty.home=" + homePath);
        cmdLineArgs.add("user.dir=" + homePath);
        cmdLineArgs.add("java.version=1.7.0_60");

        // Modules
        cmdLineArgs.add("--module=deploy");
        cmdLineArgs.add("--module=http2");

        Main main = new Main();

        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[cmdLineArgs.size()]));
        BaseHome baseHome = main.getBaseHome();

        assertThat("jetty.home",baseHome.getHome(),is(homePath.getAbsolutePath()));
        assertThat("jetty.base",baseHome.getBase(),is(homePath.getAbsolutePath()));

        ConfigurationAssert.assertConfiguration(baseHome,args,"assert-home-with-http2.txt");
    }

    @Test
    public void testJettyHomeWithSpaces() throws Exception
    {
        List<String> cmdLineArgs = new ArrayList<>();

        File homePath = MavenTestingUtils.getTestResourceDir("jetty home with spaces").getAbsoluteFile();
        cmdLineArgs.add("user.dir=" + homePath);
        cmdLineArgs.add("jetty.home=" + homePath);

        Main main = new Main();
        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[cmdLineArgs.size()]));
        BaseHome baseHome = main.getBaseHome();

        assertThat("jetty.home",baseHome.getHome(),is(homePath.getAbsolutePath()));
        assertThat("jetty.base",baseHome.getBase(),is(homePath.getAbsolutePath()));

        ConfigurationAssert.assertConfiguration(baseHome,args,"assert-home-with-spaces.txt");
    }
}
