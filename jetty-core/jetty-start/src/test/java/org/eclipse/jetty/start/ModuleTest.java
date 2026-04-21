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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.start.config.CommandLineConfigSource;
import org.eclipse.jetty.start.config.ConfigSources;
import org.eclipse.jetty.start.config.JettyBaseConfigSource;
import org.eclipse.jetty.start.config.JettyHomeConfigSource;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class ModuleTest
{
    private BaseHome newBaseHome(Path baseDir) throws IOException
    {
        // Test Env
        Path homeDir = MavenTestingUtils.getTestResourcePathDir("dist-home");
        String[] cmdLine = new String[]{"jetty.version=TEST"};

        // Configuration
        CommandLineConfigSource cmdLineSource = new CommandLineConfigSource(cmdLine);
        ConfigSources config = new ConfigSources();
        config.add(cmdLineSource);
        config.add(new JettyHomeConfigSource(homeDir));
        config.add(new JettyBaseConfigSource(baseDir));

        // Initialize
        return new BaseHome(config);
    }

    private Path writeModuleFile(Path baseDir, String moduleName, String modContents) throws IOException
    {
        Path file = baseDir.resolve("modules/" + moduleName + ".mod");
        FS.ensureDirectoryExists(file.getParent());
        Files.writeString(file, modContents, UTF_8);
        return file;
    }

    @Test
    public void testLoadMain(WorkDir workDir) throws IOException
    {
        Path baseDir = workDir.getEmptyPathDir();
        BaseHome basehome = newBaseHome(baseDir);

        Path file = MavenPaths.findTestResourceFile("dist-home/modules/main.mod");
        Module module = new Module(basehome, file);

        assertThat("Module Name", module.getName(), is("main"));
        assertThat("Module Depends Size", module.getDepends().size(), is(1));
        assertThat("Module Depends", module.getDepends(), containsInAnyOrder("base"));
        assertThat("Module Xmls Size", module.getXmls().size(), is(1));
        assertThat("Module Lib Size", module.getLibs().size(), is(2));
        assertThat("Module Lib", module.getLibs(), contains("lib/main.jar", "lib/other.jar"));
    }

    @Test
    public void testEnvironmentUnspecified(WorkDir workDir) throws IOException
    {
        Path baseDir = workDir.getEmptyPathDir();
        BaseHome basehome = newBaseHome(baseDir);

        Path modFile = writeModuleFile(baseDir, "test-env-unspecified",
            """
                [description]
                test module with no environment
                
                [ini]
                test.module=test
                """);

        Module module = new Module(basehome, modFile);
        assertThat("Module name", module.getName(), is("test-env-unspecified"));
        assertThat("Module environment inherited", module.isEnvironmentInherited(), is(false));
        assertThat("Module environment", module.getEnvironment(), is(Module.ENVIRONMENT_JETTY));
        assertThat("Module ini", module.getIniSection().getFirst(), is("test.module?=test"));
        assertThat("Module provides", module.getProvides(), is(empty()));
    }

    @Test
    public void testEnvironmentSpecified(WorkDir workDir) throws IOException
    {
        Path baseDir = workDir.getEmptyPathDir();
        BaseHome basehome = newBaseHome(baseDir);

        Path modFile = writeModuleFile(baseDir, "test-env-specified",
            """
                [description]
                test module with a specific environment
                
                [environment]
                zedzed
                
                [ini]
                test.module=zed
                """);

        Module module = new Module(basehome, modFile);
        assertThat("Module name", module.getName(), is("test-env-specified"));
        assertThat("Module environment inherited", module.isEnvironmentInherited(), is(false));
        assertThat("Module environment", module.getEnvironment(), is("zedzed"));
        assertThat("Module ini", module.getIniSection().getFirst(), is("test.module?=zed"));
    }

    @Test
    public void testEnvironmentInherited(WorkDir workDir) throws IOException
    {
        Path baseDir = workDir.getEmptyPathDir();
        BaseHome basehome = newBaseHome(baseDir);

        Path modFile = writeModuleFile(baseDir, "test-env-inherited",
            """
                [description]
                test module with an inherited environment
                
                [environment]
                <inherit>
                
                [ini]
                test.module=inh
                """);

        Module module = new Module(basehome, modFile);
        assertThat("Module name", module.getName(), is("test-env-inherited"));
        assertThat("Module environment inherited", module.isEnvironmentInherited(), is(true));
        assertThat("Module environment", module.getEnvironment(), is("<inherit>"));
        assertThat("Module ini", module.getIniSection().getFirst(), is("test.module?=inh"));
    }

    @Test
    public void testEnableFromEEX(WorkDir workDir) throws IOException
    {
        Path baseDir = workDir.getEmptyPathDir();
        BaseHome basehome = newBaseHome(baseDir);

        Path modFile = writeModuleFile(baseDir, "test-env-eeX",
            """
                [description]
                test module with an inherited environment
                
                [environment]
                # This is using a mixed case intentionally, internally the Module
                # should be converting to lowercase.
                eeX
                
                [ini]
                test.module=eex
                """);

        Module module = new Module(basehome, modFile);
        module.enable("eeX", "From test", false);
        assertThat("Module name", module.getName(), is("test-env-eeX"));
        assertThat("Module environment inherited", module.isEnvironmentInherited(), is(false));
        assertThat("Module environment", module.getEnvironment(), is("eex"));
        assertThat("Module ini", module.getIniSection().getFirst(), is("test.module?=eex"));
        assertThat("Module enabled in eeX", module.isEnabledInEnvironment("eeX"), is(true));
        assertThat("Module enabled in jetty", module.isEnabledInEnvironment("jetty"), is(false));
    }

    @Test
    public void testEnableInheritedFromMultiple(WorkDir workDir) throws IOException
    {
        Path baseDir = workDir.getEmptyPathDir();
        BaseHome basehome = newBaseHome(baseDir);

        Path modFile = writeModuleFile(baseDir, "test-env-inherited",
            """
                [description]
                test module with an inherited environment
                
                [environment]
                <inherit>
                
                [ini]
                test.module=eex
                """);

        Module module = new Module(basehome, modFile);
        assertTrue(module.enable("ee99", "From test", false), "Enable (initial) in ee99");
        assertTrue(module.enable("eeX", "From test", true), "Enable (initial) in eeX");
        assertFalse(module.enable("ee99", "From test", true), "Enable (second) in ee99");
        assertThat("Module name", module.getName(), is("test-env-inherited"));
        assertThat("Module environment inherited", module.isEnvironmentInherited(), is(true));
        assertThat("Module environment", module.getEnvironment(), is("<inherit>"));
        assertThat("Module ini", module.getIniSection().getFirst(), is("test.module?=eex"));
        assertThat("Module transitive", module.isTransitive(), is(false));
        assertThat("Module enabled in eeX", module.isEnabledInEnvironment("eeX"), is(true));
        assertThat("Module enabled in ee99", module.isEnabledInEnvironment("ee99"), is(true));
        assertThat("Module enabled in jetty", module.isEnabledInEnvironment("jetty"), is(false));

        String[] expectedEnvNames = {
            "eex",
            "ee99"
        };
        assertThat(module.getEnabledEnvironments(), containsInAnyOrder(expectedEnvNames));
    }
}
