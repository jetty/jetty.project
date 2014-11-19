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

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.start.config.CommandLineConfigSource;
import org.eclipse.jetty.start.config.ConfigSources;
import org.eclipse.jetty.start.config.JettyBaseConfigSource;
import org.eclipse.jetty.start.config.JettyHomeConfigSource;
import org.eclipse.jetty.start.graph.HowSetMatcher;
import org.eclipse.jetty.start.graph.Predicate;
import org.eclipse.jetty.start.graph.RegexNamePredicate;
import org.eclipse.jetty.start.graph.Selection;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Rule;
import org.junit.Test;

public class ModulesTest
{
    private final static String TEST_SOURCE = "<test>";

    @Rule
    public TestingDir testdir = new TestingDir();

    @Test
    public void testLoadAllModules() throws IOException
    {
        // Test Env
        File homeDir = MavenTestingUtils.getTestResourceDir("usecases/home");
        File baseDir = testdir.getEmptyDir();
        String cmdLine[] = new String[] { "jetty.version=TEST" };

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
        Modules modules = new Modules(basehome,args);
        modules.registerAll();

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

        List<String> expected = new ArrayList<>();
        expected.add("jmx");
        expected.add("client");
        expected.add("stats");
        expected.add("spdy");
        expected.add("deploy");
        expected.add("debug");
        expected.add("security");
        expected.add("ext");
        expected.add("websocket");
        expected.add("rewrite");
        expected.add("ipaccess");
        expected.add("xinetd");
        expected.add("proxy");
        expected.add("webapp");
        expected.add("jndi");
        expected.add("lowresources");
        expected.add("https");
        expected.add("plus");
        expected.add("requestlog");
        expected.add("jsp");
        // (only present if enabled) expected.add("jsp-impl");
        expected.add("monitor");
        expected.add("xml");
        expected.add("ssl");
        expected.add("protonego");
        expected.add("servlet");
        expected.add("jaas");
        expected.add("http");
        expected.add("base");
        expected.add("server");
        expected.add("annotations");
        expected.add("resources");
        expected.add("logging");

        ConfigurationAssert.assertContainsUnordered("All Modules",expected,moduleNames);
    }

    /**
     * Test loading of only shallow modules, not deep references.
     * In other words. ${search-dir}/modules/*.mod should be the only
     * valid references, but ${search-dir}/alt/foo/modules/*.mod should
     * not be considered valid.
     */
    @Test
    public void testLoadShallowModulesOnly() throws IOException
    {
        // Test Env
        File homeDir = MavenTestingUtils.getTestResourceDir("jetty home with spaces");
        // intentionally setup top level resources dir (as this would have many
        // deep references)
        File baseDir = MavenTestingUtils.getTestResourcesDir();
        String cmdLine[] = new String[] { "jetty.version=TEST" };

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
        Modules modules = new Modules(basehome,args);
        modules.registerAll();

        List<String> moduleNames = new ArrayList<>();
        for (Module mod : modules)
        {
            moduleNames.add(mod.getName());
        }

        List<String> expected = new ArrayList<>();
        expected.add("base");

        ConfigurationAssert.assertContainsUnordered("All Modules",expected,moduleNames);
    }

    @Test
    public void testEnableRegexSimple() throws IOException
    {
        // Test Env
        File homeDir = MavenTestingUtils.getTestResourceDir("usecases/home");
        File baseDir = testdir.getEmptyDir();
        String cmdLine[] = new String[] { "jetty.version=TEST", "java.version=1.7.0_60" };

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
        Modules modules = new Modules(basehome,args);
        modules.registerAll();
        Predicate sjPredicate = new RegexNamePredicate("[sj]{1}.*");
        modules.selectNode(sjPredicate,new Selection(TEST_SOURCE));
        modules.buildGraph();

        List<String> expected = new ArrayList<>();
        expected.add("jmx");
        expected.add("stats");
        expected.add("spdy");
        expected.add("security");
        expected.add("jndi");
        expected.add("jsp");
        expected.add("servlet");
        expected.add("jaas");
        expected.add("server");
        // transitive
        expected.add("base");
        expected.add("ssl");
        expected.add("protonego");
        expected.add("protonego-boot");
        expected.add("protonego-impl");
        expected.add("xml");
        expected.add("jsp-impl");

        List<String> resolved = new ArrayList<>();
        for (Module module : modules.getEnabled())
        {
            resolved.add(module.getName());
        }

        ConfigurationAssert.assertContainsUnordered("Enabled Modules",expected,resolved);
    }

    @Test
    public void testResolve_ServerHttp() throws IOException
    {
        // Test Env
        File homeDir = MavenTestingUtils.getTestResourceDir("usecases/home");
        File baseDir = testdir.getEmptyDir();
        String cmdLine[] = new String[] { "jetty.version=TEST" };

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
        Modules modules = new Modules(basehome,args);
        modules.registerAll();

        // Enable 2 modules
        modules.selectNode("server",new Selection(TEST_SOURCE));
        modules.selectNode("http",new Selection(TEST_SOURCE));

        modules.buildGraph();

        // Collect active module list
        List<Module> active = modules.getEnabled();

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

        assertThat("Resolved Names: " + actualNames,actualNames,contains(expectedNames.toArray()));

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
        assertThat("Resolved Libs: " + actualLibs,actualLibs,contains(expectedLibs.toArray()));

        // Assert XML List
        List<String> expectedXmls = new ArrayList<>();
        expectedXmls.add("etc/jetty.xml");
        expectedXmls.add("etc/jetty-http.xml");

        List<String> actualXmls = modules.normalizeXmls(active);
        assertThat("Resolved XMLs: " + actualXmls,actualXmls,contains(expectedXmls.toArray()));
    }

    @Test
    public void testResolve_WebSocket() throws IOException
    {
        // Test Env
        File homeDir = MavenTestingUtils.getTestResourceDir("usecases/home");
        File baseDir = testdir.getEmptyDir();
        String cmdLine[] = new String[] { "jetty.version=TEST" };

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
        Modules modules = new Modules(basehome,args);
        modules.registerAll();

        // Enable 2 modules
        modules.selectNode("websocket",new Selection(TEST_SOURCE));
        modules.selectNode("http",new Selection(TEST_SOURCE));

        modules.buildGraph();
        // modules.dump();

        // Collect active module list
        List<Module> active = modules.getEnabled();

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

        assertThat("Resolved Names: " + actualNames,actualNames,contains(expectedNames.toArray()));

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
        assertThat("Resolved Libs: " + actualLibs,actualLibs,contains(expectedLibs.toArray()));

        // Assert XML List
        List<String> expectedXmls = new ArrayList<>();
        expectedXmls.add("etc/jetty.xml");
        expectedXmls.add("etc/jetty-http.xml");
        expectedXmls.add("etc/jetty-plus.xml");
        expectedXmls.add("etc/jetty-annotations.xml");
        expectedXmls.add("etc/jetty-websockets.xml");

        List<String> actualXmls = modules.normalizeXmls(active);
        assertThat("Resolved XMLs: " + actualXmls,actualXmls,contains(expectedXmls.toArray()));
    }

    @Test
    public void testResolve_Alt() throws IOException
    {
        // Test Env
        File homeDir = MavenTestingUtils.getTestResourceDir("usecases/home");
        File baseDir = testdir.getEmptyDir();
        String cmdLine[] = new String[] { "jetty.version=TEST" };

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
        Modules modules = new Modules(basehome,args);
        modules.registerAll();

        // Enable test modules
        modules.selectNode("http",new Selection(TEST_SOURCE));
        modules.selectNode("annotations",new Selection(TEST_SOURCE));
        modules.selectNode("deploy",new Selection(TEST_SOURCE));
        // Enable alternate modules
        String alt = "<alt>";
        modules.selectNode("websocket",new Selection(alt));
        modules.selectNode("jsp",new Selection(alt));

        modules.buildGraph();
        // modules.dump();

        // Collect active module list
        List<Module> active = modules.getEnabled();

        // Assert names are correct, and in the right order
        List<String> expectedNames = new ArrayList<>();
        expectedNames.add("base");
        expectedNames.add("jsp-impl");
        expectedNames.add("xml");
        expectedNames.add("server");
        expectedNames.add("http");
        expectedNames.add("jndi");
        expectedNames.add("security");
        expectedNames.add("servlet");
        expectedNames.add("jsp");
        expectedNames.add("plus");
        expectedNames.add("webapp");
        expectedNames.add("annotations");
        expectedNames.add("deploy");
        expectedNames.add("websocket");

        List<String> actualNames = new ArrayList<>();
        for (Module actual : active)
        {
            actualNames.add(actual.getName());
        }

        assertThat("Resolved Names: " + actualNames,actualNames,contains(expectedNames.toArray()));

        // Now work with the 'alt' selected
        List<String> expectedAlts = new ArrayList<>();
        expectedAlts.add("jsp-impl");
        expectedAlts.add("jsp");
        expectedAlts.add("websocket");

        for (String expectedAlt : expectedAlts)
        {
            Module altMod = modules.get(expectedAlt);
            assertThat("Alt.mod[" + expectedAlt + "].selected",altMod.isSelected(),is(true));
            Set<String> sources = altMod.getSelectedHowSet();
            assertThat("Alt.mod[" + expectedAlt + "].sources: [" + Utils.join(sources,", ") + "]",sources,contains(alt));
        }

        // Now collect the unique source list
        List<Module> alts = modules.getMatching(new HowSetMatcher(alt));

        // Assert names are correct, and in the right order
        actualNames = new ArrayList<>();
        for (Module actual : alts)
        {
            actualNames.add(actual.getName());
        }

        assertThat("Resolved Alt (Sources) Names: " + actualNames,actualNames,contains(expectedAlts.toArray()));
    }
}
