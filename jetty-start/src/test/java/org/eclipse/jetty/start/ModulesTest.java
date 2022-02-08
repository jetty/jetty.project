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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.start.config.CommandLineConfigSource;
import org.eclipse.jetty.start.config.ConfigSources;
import org.eclipse.jetty.start.config.JettyBaseConfigSource;
import org.eclipse.jetty.start.config.JettyHomeConfigSource;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@ExtendWith(WorkDirExtension.class)
public class ModulesTest
{
    private static final String TEST_SOURCE = "<test>";

    public WorkDir testdir;

    @Test
    public void testLoadAllModules() throws IOException
    {
        // Test Env
        File homeDir = MavenTestingUtils.getTestResourceDir("dist-home");
        File baseDir = testdir.getEmptyPathDir().toFile();
        String[] cmdLine = new String[]{"jetty.version=TEST"};

        // Configuration
        CommandLineConfigSource cmdLineSource = new CommandLineConfigSource(cmdLine);
        ConfigSources config = new ConfigSources();
        config.add(cmdLineSource);
        config.add(new JettyHomeConfigSource(homeDir.toPath()));
        config.add(new JettyBaseConfigSource(baseDir.toPath()));

        // Initialize
        BaseHome basehome = new BaseHome(config);

        StartArgs args = new StartArgs(basehome);
        args.parse(config);

        // Test Modules
        Modules modules = new Modules(basehome, args);
        modules.registerAll();

        // Check versions
        String platformProperty = args.getProperties().getString("java.version.platform");
        assertThat("java.version.platform", Integer.parseInt(platformProperty), greaterThanOrEqualTo(8));

        List<String> moduleNames = new ArrayList<>();
        for (Module mod : modules)
        {
            // skip alpn-boot in this test (as its behavior is jdk specific)
            if (mod.getName().equals("alpn-boot"))
            {
                continue;
            }
            moduleNames.add(mod.getName());
        }

        List<String> expected = new ArrayList<>();
        expected.add("base");
        expected.add("extra");
        expected.add("main");
        expected.add("optional");

        assertThat("All Modules", moduleNames, containsInAnyOrder(expected.toArray()));
    }

    /**
     * Test loading of only shallow modules, not deep references.
     * In other words. ${search-dir}/modules/*.mod should be the only
     * valid references, but ${search-dir}/alt/foo/modules/*.mod should
     * not be considered valid.
     *
     * @throws IOException on test failures
     */
    @Test
    public void testLoadShallowModulesOnly() throws IOException
    {
        // Test Env
        File homeDir = MavenTestingUtils.getTestResourceDir("jetty home with spaces");
        // intentionally setup top level resources dir (as this would have many
        // deep references)
        File baseDir = MavenTestingUtils.getTestResourcesDir();
        String[] cmdLine = new String[]{"jetty.version=TEST"};

        // Configuration
        CommandLineConfigSource cmdLineSource = new CommandLineConfigSource(cmdLine);
        ConfigSources config = new ConfigSources();
        config.add(cmdLineSource);
        config.add(new JettyHomeConfigSource(homeDir.toPath()));
        config.add(new JettyBaseConfigSource(baseDir.toPath()));

        // Initialize
        BaseHome basehome = new BaseHome(config);

        StartArgs args = new StartArgs(basehome);
        args.parse(config);

        // Test Modules
        Modules modules = new Modules(basehome, args);
        modules.registerAll();

        List<String> moduleNames = new ArrayList<>();
        for (Module mod : modules)
        {
            moduleNames.add(mod.getName());
        }

        List<String> expected = new ArrayList<>();
        expected.add("base");

        assertThat("All Modules", moduleNames, containsInAnyOrder(expected.toArray()));
    }

    @Test
    public void testResolveServerHttp() throws IOException
    {
        // Test Env
        File homeDir = MavenTestingUtils.getTestResourceDir("dist-home");
        File baseDir = testdir.getEmptyPathDir().toFile();
        String[] cmdLine = new String[]{"jetty.version=TEST"};

        // Configuration
        CommandLineConfigSource cmdLineSource = new CommandLineConfigSource(cmdLine);
        ConfigSources config = new ConfigSources();
        config.add(cmdLineSource);
        config.add(new JettyHomeConfigSource(homeDir.toPath()));
        config.add(new JettyBaseConfigSource(baseDir.toPath()));

        // Initialize
        BaseHome basehome = new BaseHome(config);

        StartArgs args = new StartArgs(basehome);
        args.parse(config);

        // Test Modules
        Modules modules = new Modules(basehome, args);
        modules.registerAll();

        // Enable 2 modules
        modules.enable("base", TEST_SOURCE);
        modules.enable("optional", TEST_SOURCE);

        // Collect active module list
        List<Module> active = modules.getEnabled();

        // Assert names are correct, and in the right order
        List<String> expectedNames = new ArrayList<>();
        expectedNames.add("optional");
        expectedNames.add("base");

        List<String> actualNames = new ArrayList<>();
        for (Module actual : active)
        {
            actualNames.add(actual.getName());
        }

        assertThat("Resolved Names: " + actualNames, actualNames, contains(expectedNames.toArray()));

        // Assert Library List
        List<String> expectedLibs = new ArrayList<>();
        expectedLibs.add("lib/optional.jar");
        expectedLibs.add("lib/base.jar");

        List<String> actualLibs = normalizeLibs(active);
        assertThat("Resolved Libs: " + actualLibs, actualLibs, contains(expectedLibs.toArray()));

        // Assert XML List
        List<String> expectedXmls = new ArrayList<>();
        expectedXmls.add("etc/optional.xml");
        expectedXmls.add("etc/base.xml");

        List<String> actualXmls = normalizeXmls(active);
        assertThat("Resolved XMLs: " + actualXmls, actualXmls, contains(expectedXmls.toArray()));
    }

    @Test
    public void testResolveNotRequiredModuleNotFound() throws IOException
    {
        // Test Env
        File homeDir = MavenTestingUtils.getTestResourceDir("non-required-deps");
        File baseDir = testdir.getEmptyPathDir().toFile();
        String[] cmdLine = new String[]{"bar.type=cannot-find-me"};

        // Configuration
        CommandLineConfigSource cmdLineSource = new CommandLineConfigSource(cmdLine);
        ConfigSources config = new ConfigSources();
        config.add(cmdLineSource);
        config.add(new JettyHomeConfigSource(homeDir.toPath()));
        config.add(new JettyBaseConfigSource(baseDir.toPath()));

        // Initialize
        BaseHome basehome = new BaseHome(config);

        StartArgs args = new StartArgs(basehome);
        args.parse(config);

        // Test Modules
        Modules modules = new Modules(basehome, args);
        modules.registerAll();

        // Enable module
        modules.enable("bar", TEST_SOURCE);

        // Collect active module list
        List<Module> active = modules.getEnabled();
        modules.checkEnabledModules();

        // Assert names are correct, and in the right order
        List<String> expectedNames = new ArrayList<>();
        expectedNames.add("foo");
        expectedNames.add("bar");

        List<String> actualNames = new ArrayList<>();
        for (Module actual : active)
        {
            actualNames.add(actual.getName());
        }

        assertThat("Resolved Names: " + actualNames, actualNames, contains(expectedNames.toArray()));

        Props props = args.getProperties();
        assertThat(props.getString("bar.name"), is(nullValue()));
    }

    @Test
    public void testResolveNotRequiredModuleFound() throws IOException
    {
        // Test Env
        File homeDir = MavenTestingUtils.getTestResourceDir("non-required-deps");
        File baseDir = testdir.getEmptyPathDir().toFile();
        String[] cmdLine = new String[]{"bar.type=dive"};

        // Configuration
        CommandLineConfigSource cmdLineSource = new CommandLineConfigSource(cmdLine);
        ConfigSources config = new ConfigSources();
        config.add(cmdLineSource);
        config.add(new JettyHomeConfigSource(homeDir.toPath()));
        config.add(new JettyBaseConfigSource(baseDir.toPath()));

        // Initialize
        BaseHome basehome = new BaseHome(config);

        StartArgs args = new StartArgs(basehome);
        args.parse(config);

        // Test Modules
        Modules modules = new Modules(basehome, args);
        modules.registerAll();

        // Enable module
        modules.enable("bar", TEST_SOURCE);

        // Collect active module list
        List<Module> active = modules.getEnabled();
        modules.checkEnabledModules();

        // Assert names are correct, and in the right order
        List<String> expectedNames = new ArrayList<>();
        expectedNames.add("foo");
        expectedNames.add("bar");
        expectedNames.add("bar-dive");

        List<String> actualNames = new ArrayList<>();
        for (Module actual : active)
        {
            actualNames.add(actual.getName());
        }

        assertThat("Resolved Names: " + actualNames, actualNames, contains(expectedNames.toArray()));

        Props props = args.getProperties();
        assertThat(props.getString("bar.name"), is("dive"));
    }

    @Test
    public void testResolveNotRequiredModuleFoundDynamic() throws IOException
    {
        // Test Env
        File homeDir = MavenTestingUtils.getTestResourceDir("non-required-deps");
        File baseDir = testdir.getEmptyPathDir().toFile();
        String[] cmdLine = new String[]{"bar.type=dynamic"};

        // Configuration
        CommandLineConfigSource cmdLineSource = new CommandLineConfigSource(cmdLine);
        ConfigSources config = new ConfigSources();
        config.add(cmdLineSource);
        config.add(new JettyHomeConfigSource(homeDir.toPath()));
        config.add(new JettyBaseConfigSource(baseDir.toPath()));

        // Initialize
        BaseHome basehome = new BaseHome(config);

        StartArgs args = new StartArgs(basehome);
        args.parse(config);

        // Test Modules
        Modules modules = new Modules(basehome, args);
        modules.registerAll();

        // Enable module
        modules.enable("bar", TEST_SOURCE);

        // Collect active module list
        List<Module> active = modules.getEnabled();
        modules.checkEnabledModules();

        // Assert names are correct, and in the right order
        List<String> expectedNames = new ArrayList<>();
        expectedNames.add("foo");
        expectedNames.add("bar");
        expectedNames.add("impls/bar-dynamic");

        List<String> actualNames = new ArrayList<>();
        for (Module actual : active)
        {
            actualNames.add(actual.getName());
        }

        assertThat("Resolved Names: " + actualNames, actualNames, contains(expectedNames.toArray()));

        Props props = args.getProperties();
        assertThat(props.getString("bar.name"), is("dynamic"));
    }

    private List<String> normalizeLibs(List<Module> active)
    {
        List<String> libs = new ArrayList<>();
        for (Module module : active)
        {
            for (String lib : module.getLibs())
            {
                if (!libs.contains(lib))
                {
                    libs.add(lib);
                }
            }
        }
        return libs;
    }

    private List<String> normalizeXmls(List<Module> active)
    {
        List<String> xmls = new ArrayList<>();
        for (Module module : active)
        {
            for (String xml : module.getXmls())
            {
                if (!xmls.contains(xml))
                {
                    xmls.add(xml);
                }
            }
        }
        return xmls;
    }
}
