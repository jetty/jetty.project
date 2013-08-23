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
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.Before;
import org.junit.Test;

public class MainTest
{
    @Before
    public void setUp() throws Exception
    {
        File testJettyHome = MavenTestingUtils.getTestResourceDir("usecases/home");
        System.setProperty("jetty.home",testJettyHome.getAbsolutePath());
    }

    @Test
    public void testBasicProcessing() throws Exception
    {
        Main main = new Main();
        StartArgs args = main.processCommandLine(new String[]
        { "jetty.port=9090" });
        BaseHome baseHome = main.getBaseHome();
        System.err.println(args);

        ConfigurationAssert.assertConfiguration(baseHome,args,"assert-home.txt");
    }

    @Test
    public void testWithCommandLine() throws Exception
    {
        List<String> cmdLineArgs = new ArrayList<>();

        // JVM args
        cmdLineArgs.add("--exec");
        cmdLineArgs.add("-Xms1024m");
        cmdLineArgs.add("-Xmx1024m");

        // Arbitrary XMLs
        cmdLineArgs.add("jetty.xml");
        cmdLineArgs.add("jetty-jmx.xml");
        cmdLineArgs.add("jetty-logging.xml");

        Main main = new Main();

        StartArgs args = main.processCommandLine(cmdLineArgs.toArray(new String[6]));
        BaseHome baseHome = main.getBaseHome();
        System.err.println(args);

        ConfigurationAssert.assertConfiguration(baseHome,args,"assert-home-with-jvm.txt");
    }

    public void testJettyHomeWithSpaces()
    {
        // main.addJvmArgs(jvmArgs);
        //
        // Classpath classpath = nastyWayToCreateAClasspathObject("/jetty/home with spaces/");
        // CommandLineBuilder cmd = main.buildCommandLine(classpath,xmls);
        // assertThat("CommandLineBuilder shouldn't be null",cmd,notNullValue());
        //
        // List<String> commandArgs = cmd.getArgs();
        // assertThat("commandArgs elements",commandArgs.size(),equalTo(12));
        // assertThat("args does not contain -cp",commandArgs,hasItems("-cp"));
        // assertThat("Classpath should be correctly quoted and match expected value",commandArgs,
        // hasItems("/jetty/home with spaces/somejar.jar:/jetty/home with spaces/someotherjar.jar"));
        // assertThat("args does not contain --exec",commandArgs,hasItems("--exec"));
        // assertThat("CommandLine should contain jvmArgs",commandArgs,hasItems("-Xms1024m"));
        // assertThat("CommandLine should contain jvmArgs",commandArgs,hasItems("-Xmx1024m"));
        // assertThat("CommandLine should contain xmls",commandArgs,hasItems("jetty.xml"));
        // assertThat("CommandLine should contain xmls",commandArgs,hasItems("jetty-jmx.xml"));
        // assertThat("CommandLine should contain xmls",commandArgs,hasItems("jetty-logging.xml"));
        //
        // String commandLine = cmd.toString();
        // assertThat("cmd.toString() should be properly escaped",commandLine,containsString("-cp /jetty/home\\ with\\ "
        // + "spaces/somejar.jar:/jetty/home\\ with\\ spaces/someotherjar.jar"));
        // assertThat("cmd.toString() doesn't contain xml config files",commandLine,containsString(" jetty.xml jetty-jmx.xml jetty-logging.xml"));
    }

    private Classpath nastyWayToCreateAClasspathObject(String jettyHome) throws NoSuchFieldException, IllegalAccessException
    {
        Classpath classpath = new Classpath();
        Field classpathElements = Classpath.class.getDeclaredField("_elements");
        classpathElements.setAccessible(true);
        File file = new File(jettyHome + "somejar.jar");
        File file2 = new File(jettyHome + "someotherjar.jar");
        Vector<File> elements = new Vector<File>();
        elements.add(file);
        elements.add(file2);
        classpathElements.set(classpath,elements);
        return classpath;
    }

}
