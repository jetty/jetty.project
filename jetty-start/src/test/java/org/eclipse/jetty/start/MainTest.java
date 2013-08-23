//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.Test;

public class MainTest
{
    private void addUseCasesHome(List<String> cmdLineArgs)
    {
        File testJettyHome = MavenTestingUtils.getTestResourceDir("usecases/home");
        cmdLineArgs.add("jetty.home=" + testJettyHome);
    }

    @Test
    public void testBasicProcessing() throws Exception
    {
        List<String> cmdLineArgs = new ArrayList<>();
        addUseCasesHome(cmdLineArgs);
        cmdLineArgs.add("jetty.port=9090");

        Main main = new Main();
        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[cmdLineArgs.size()]));
        BaseHome baseHome = main.getBaseHome();
        System.err.println(args);

        ConfigurationAssert.assertConfiguration(baseHome,args,"assert-home.txt");
    }

    @Test
    public void testListConfig() throws Exception
    {
        List<String> cmdLineArgs = new ArrayList<>();
        addUseCasesHome(cmdLineArgs);
        cmdLineArgs.add("jetty.port=9090");
        cmdLineArgs.add("--list-config");

        Main main = new Main();
        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[cmdLineArgs.size()]));
        main.listConfig(args);
    }

    @Test
    public void testWithCommandLine() throws Exception
    {
        List<String> cmdLineArgs = new ArrayList<>();

        addUseCasesHome(cmdLineArgs);

        // JVM args
        cmdLineArgs.add("--exec");
        cmdLineArgs.add("-Xms1024m");
        cmdLineArgs.add("-Xmx1024m");

        // Arbitrary XMLs
        cmdLineArgs.add("jetty.xml");
        cmdLineArgs.add("jetty-jmx.xml");
        cmdLineArgs.add("jetty-logging.xml");

        Main main = new Main();

        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[cmdLineArgs.size()]));
        BaseHome baseHome = main.getBaseHome();
        System.err.println(args);

        ConfigurationAssert.assertConfiguration(baseHome,args,"assert-home-with-jvm.txt");
    }

    @Test
    public void testJettyHomeWithSpaces() throws Exception
    {
        List<String> cmdLineArgs = new ArrayList<>();

        File homePath = MavenTestingUtils.getTestResourceDir("jetty home with spaces");
        cmdLineArgs.add("jetty.home=" + homePath);

        Main main = new Main();
        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[cmdLineArgs.size()]));
        BaseHome baseHome = main.getBaseHome();
        System.err.println(args);

        ConfigurationAssert.assertConfiguration(baseHome,args,"assert-home-with-spaces.txt");
    }
}
