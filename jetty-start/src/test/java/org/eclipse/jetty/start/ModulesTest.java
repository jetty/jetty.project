//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.start.config.CommandLineConfigSource;
import org.eclipse.jetty.start.config.ConfigSources;
import org.eclipse.jetty.start.config.JettyBaseConfigSource;
import org.eclipse.jetty.start.config.JettyHomeConfigSource;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ModulesTest
{
    private final static List<String> TEST_SOURCE = Collections.singletonList("<test>");

    @Rule
    public TestingDir testdir = new TestingDir();

    @Test
    public void testLoadAllModules() throws IOException
    {
        // Test Env
        File homeDir = MavenTestingUtils.getTestResourceDir("usecases/home");
        File baseDir = testdir.getEmptyDir();
        String cmdLine[] = new String[] {"jetty.version=TEST"};
        
        // Configuration
        CommandLineConfigSource cmdLineSource = new CommandLineConfigSource(cmdLine);
        ConfigSources config = new ConfigSources();
        config.add(cmdLineSource);
        config.add(new JettyHomeConfigSource(homeDir.toPath()));
        config.add(new JettyBaseConfigSource(baseDir.toPath()));
        
        // Initialize
        BaseHome basehome = new BaseHome(config);
        
        StartArgs args = new StartArgs();
        args.parse(config);

        // Test Modules
        Modules modules = new Modules();
        modules.registerAll(basehome,args);

        List<String> moduleNames = new ArrayList<>();
        for (Module mod : modules)
        {
            // skip npn-boot in this test (as its behavior is jdk specific)
            if (mod.getName().equals("npn-boot"))
            {
                continue;
            }
            moduleNames.add(mod.getName());
        }

        String expected[] = { "jmx", "client", "stats", "spdy", "deploy", "debug", "security", "npn", "ext", "websocket", "rewrite", "ipaccess", "xinetd",
                "proxy", "webapp", "jndi", "lowresources", "https", "plus", "requestlog", "jsp", "monitor", "xml", "servlet", "jaas", "http", "base", "server",
                "annotations", "resources", "loggging" };

        Assert.assertThat("Module count: " + moduleNames,moduleNames.size(),is(expected.length));
    }

    @Test
    public void testEnableRegexSimple() throws IOException
    {
        // Test Env
        File homeDir = MavenTestingUtils.getTestResourceDir("usecases/home");
        File baseDir = testdir.getEmptyDir();
        String cmdLine[] = new String[] {"jetty.version=TEST"};
        
        // Configuration
        CommandLineConfigSource cmdLineSource = new CommandLineConfigSource(cmdLine);
        ConfigSources config = new ConfigSources();
        config.add(cmdLineSource);
        config.add(new JettyHomeConfigSource(homeDir.toPath()));
        config.add(new JettyBaseConfigSource(baseDir.toPath()));
        
        // Initialize
        BaseHome basehome = new BaseHome(config);
        
        StartArgs args = new StartArgs();
        args.parse(config);

        // Test Modules
        Modules modules = new Modules();
        modules.registerAll(basehome,args);
        modules.enable("[sj]{1}.*",TEST_SOURCE);

        String expected[] = { "jmx", "stats", "spdy", "security", "jndi", "jsp", "servlet", "jaas", "server" };

        Assert.assertThat("Enabled Module count",modules.resolveEnabled().size(),is(expected.length));
    }

    @Test
    public void testResolve_ServerHttp() throws IOException
    {
        // Test Env
        File homeDir = MavenTestingUtils.getTestResourceDir("usecases/home");
        File baseDir = testdir.getEmptyDir();
        String cmdLine[] = new String[] {"jetty.version=TEST"};
        
        // Configuration
        CommandLineConfigSource cmdLineSource = new CommandLineConfigSource(cmdLine);
        ConfigSources config = new ConfigSources();
        config.add(cmdLineSource);
        config.add(new JettyHomeConfigSource(homeDir.toPath()));
        config.add(new JettyBaseConfigSource(baseDir.toPath()));
        
        // Initialize
        BaseHome basehome = new BaseHome(config);
        
        StartArgs args = new StartArgs();
        args.parse(config);

        // Test Modules
        Modules modules = new Modules();
        modules.registerAll(basehome,args);
        modules.buildGraph();

        // Enable 2 modules
        modules.enable("server",TEST_SOURCE);
        modules.enable("http",TEST_SOURCE);

        // Collect active module list
        List<Module> active = modules.resolveEnabled();

        // Assert names are correct, and in the right order
        List<String> expectedNames = new ArrayList<>();
        expectedNames.add("base");
        expectedNames.add("xml");
        expectedNames.add("server");
        expectedNames.add("http");

        List<String> actualNames = new ArrayList<>();
        for (Module actual : active)
        {
            actualNames.add(actual.getName());
        }

        Assert.assertThat("Resolved Names: " + actualNames,actualNames,contains(expectedNames.toArray()));

        // Assert Library List
        List<String> expectedLibs = new ArrayList<>();
        expectedLibs.add("lib/jetty-util-${jetty.version}.jar");
        expectedLibs.add("lib/jetty-io-${jetty.version}.jar");
        expectedLibs.add("lib/jetty-xml-${jetty.version}.jar");
        expectedLibs.add("lib/servlet-api-3.1.jar");
        expectedLibs.add("lib/jetty-schemas-3.1.jar");
        expectedLibs.add("lib/jetty-http-${jetty.version}.jar");
        expectedLibs.add("lib/jetty-continuation-${jetty.version}.jar");
        expectedLibs.add("lib/jetty-server-${jetty.version}.jar");

        List<String> actualLibs = modules.normalizeLibs(active);
        Assert.assertThat("Resolved Libs: " + actualLibs,actualLibs,contains(expectedLibs.toArray()));

        // Assert XML List
        List<String> expectedXmls = new ArrayList<>();
        expectedXmls.add("etc/jetty.xml");
        expectedXmls.add("etc/jetty-http.xml");

        List<String> actualXmls = modules.normalizeXmls(active);
        Assert.assertThat("Resolved XMLs: " + actualXmls,actualXmls,contains(expectedXmls.toArray()));
    }

    @Test
    public void testResolve_WebSocket() throws IOException
    {
        // Test Env
        File homeDir = MavenTestingUtils.getTestResourceDir("usecases/home");
        File baseDir = testdir.getEmptyDir();
        String cmdLine[] = new String[] {"jetty.version=TEST"};
        
        // Configuration
        CommandLineConfigSource cmdLineSource = new CommandLineConfigSource(cmdLine);
        ConfigSources config = new ConfigSources();
        config.add(cmdLineSource);
        config.add(new JettyHomeConfigSource(homeDir.toPath()));
        config.add(new JettyBaseConfigSource(baseDir.toPath()));
        
        // Initialize
        BaseHome basehome = new BaseHome(config);
        
        StartArgs args = new StartArgs();
        args.parse(config);

        // Test Modules
        Modules modules = new Modules();
        modules.registerAll(basehome,args);
        modules.buildGraph();
        // modules.dump();

        // Enable 2 modules
        modules.enable("websocket",TEST_SOURCE);
        modules.enable("http",TEST_SOURCE);

        // Collect active module list
        List<Module> active = modules.resolveEnabled();

        // Assert names are correct, and in the right order
        List<String> expectedNames = new ArrayList<>();
        expectedNames.add("base");
        expectedNames.add("xml");
        expectedNames.add("server");
        expectedNames.add("http");
        expectedNames.add("jndi");
        expectedNames.add("security");
        expectedNames.add("plus");
        expectedNames.add("annotations");
        expectedNames.add("websocket");

        List<String> actualNames = new ArrayList<>();
        for (Module actual : active)
        {
            actualNames.add(actual.getName());
        }

        Assert.assertThat("Resolved Names: " + actualNames,actualNames,contains(expectedNames.toArray()));

        // Assert Library List
        List<String> expectedLibs = new ArrayList<>();
        expectedLibs.add("lib/jetty-util-${jetty.version}.jar");
        expectedLibs.add("lib/jetty-io-${jetty.version}.jar");
        expectedLibs.add("lib/jetty-xml-${jetty.version}.jar");
        expectedLibs.add("lib/servlet-api-3.1.jar");
        expectedLibs.add("lib/jetty-schemas-3.1.jar");
        expectedLibs.add("lib/jetty-http-${jetty.version}.jar");
        expectedLibs.add("lib/jetty-continuation-${jetty.version}.jar");
        expectedLibs.add("lib/jetty-server-${jetty.version}.jar");
        expectedLibs.add("lib/jetty-jndi-${jetty.version}.jar");
        expectedLibs.add("lib/jndi/*.jar");
        expectedLibs.add("lib/jetty-security-${jetty.version}.jar");
        expectedLibs.add("lib/jetty-plus-${jetty.version}.jar");
        expectedLibs.add("lib/jetty-annotations-${jetty.version}.jar");
        expectedLibs.add("lib/annotations/*.jar");
        expectedLibs.add("lib/websocket/*.jar");

        List<String> actualLibs = modules.normalizeLibs(active);
        Assert.assertThat("Resolved Libs: " + actualLibs,actualLibs,contains(expectedLibs.toArray()));

        // Assert XML List
        List<String> expectedXmls = new ArrayList<>();
        expectedXmls.add("etc/jetty.xml");
        expectedXmls.add("etc/jetty-http.xml");
        expectedXmls.add("etc/jetty-plus.xml");
        expectedXmls.add("etc/jetty-annotations.xml");
        expectedXmls.add("etc/jetty-websockets.xml");

        List<String> actualXmls = modules.normalizeXmls(active);
        Assert.assertThat("Resolved XMLs: " + actualXmls,actualXmls,contains(expectedXmls.toArray()));
    }
}
