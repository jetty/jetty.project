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

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/* ------------------------------------------------------------ */
/**
 */
@Ignore
public class MainTest
{
    /* ------------------------------------------------------------ */
    /**
     * @throws java.lang.Exception
     */
    @Before
    public void setUp() throws Exception
    {
        File testJettyHome = MavenTestingUtils.getTestResourceDir("jetty.home");
        System.setProperty("jetty.home",testJettyHome.getAbsolutePath());
    }

    @Test
    public void testProcessCommandLine() throws Exception
    {
        Main main = new Main();
//        List<String> xmls = main.processCommandLine(new String[] {});
//        System.err.println(xmls);
//        
//        // Order is important here
//        List<String> expectedXmls = new ArrayList<String>();
//        expectedXmls.add("etc/jetty.xml"); // from start.ini
//        expectedXmls.add("etc/jetty-deploy.xml"); // from start.ini
//        expectedXmls.add("etc/jetty-webapps.xml"); // from start.ini
//        expectedXmls.add("etc/jetty-contexts.xml"); // from start.ini
//        expectedXmls.add("etc/jetty-jmx.xml"); // from start.d/10-jmx.ini
//        expectedXmls.add("etc/jetty-testrealm.xml"); // from start.d/90-testrealm.ini
//
//        assertThat("XML Resolution Order " + xmls,xmls,contains(expectedXmls.toArray()));
//
//        // Order is irrelevant here
//        Set<String> options = main.getConfig().getOptions();
//        Set<String> expectedOptions = new HashSet<>();
//        // from start.ini
//        expectedOptions.add("Server");
//        expectedOptions.add("jsp");
//        expectedOptions.add("resources");
//        expectedOptions.add("websocket");
//        expectedOptions.add("ext");
//        expectedOptions.add("newOption");
//        // from start.d/10-jmx.ini
//        expectedOptions.add("jmx");
//        // from start.d/20-websocket.ini
//        expectedOptions.add("websocket");
//        // no options from start.d/90-testrealm.ini
//
//        assertThat("Options " + options,options,containsInAnyOrder(expectedOptions.toArray()));
    }

    @Test
    public void testBuildCommandLine() throws IOException, NoSuchFieldException, IllegalAccessException
    {
        List<String> jvmArgs = new ArrayList<String>();
        jvmArgs.add("--exec");
        jvmArgs.add("-Xms1024m");
        jvmArgs.add("-Xmx1024m");

        List<String> xmls = new ArrayList<String>();
        xmls.add("jetty.xml");
        xmls.add("jetty-jmx.xml");
        xmls.add("jetty-logging.xml");

        Main main = new Main();
//        main.addJvmArgs(jvmArgs);
//
//        Classpath classpath = nastyWayToCreateAClasspathObject("/jetty/home with spaces/");
//        CommandLineBuilder cmd = main.buildCommandLine(classpath,xmls);
//        assertThat("CommandLineBuilder shouldn't be null",cmd,notNullValue());
//
//        List<String> commandArgs = cmd.getArgs();
//        assertThat("commandArgs elements",commandArgs.size(),equalTo(12));
//        assertThat("args does not contain -cp",commandArgs,hasItems("-cp"));
//        assertThat("Classpath should be correctly quoted and match expected value",commandArgs,
//                hasItems("/jetty/home with spaces/somejar.jar:/jetty/home with spaces/someotherjar.jar"));
//        assertThat("args does not contain --exec",commandArgs,hasItems("--exec"));
//        assertThat("CommandLine should contain jvmArgs",commandArgs,hasItems("-Xms1024m"));
//        assertThat("CommandLine should contain jvmArgs",commandArgs,hasItems("-Xmx1024m"));
//        assertThat("CommandLine should contain xmls",commandArgs,hasItems("jetty.xml"));
//        assertThat("CommandLine should contain xmls",commandArgs,hasItems("jetty-jmx.xml"));
//        assertThat("CommandLine should contain xmls",commandArgs,hasItems("jetty-logging.xml"));
//
//        String commandLine = cmd.toString();
//        assertThat("cmd.toString() should be properly escaped",commandLine,containsString("-cp /jetty/home\\ with\\ "
//                + "spaces/somejar.jar:/jetty/home\\ with\\ spaces/someotherjar.jar"));
//        assertThat("cmd.toString() doesn't contain xml config files",commandLine,containsString(" jetty.xml jetty-jmx.xml jetty-logging.xml"));
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
