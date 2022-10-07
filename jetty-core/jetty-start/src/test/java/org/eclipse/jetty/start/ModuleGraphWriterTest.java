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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

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
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class ModuleGraphWriterTest
{
    public WorkDir testdir;

    @Test
    public void testGenerateNothingEnabled() throws IOException
    {
        // Test Env
        Path homeDir = MavenTestingUtils.getTestResourcePathDir("dist-home");
        Path baseDir = testdir.getEmptyPathDir();
        String[] cmdLine = new String[]{"jetty.version=TEST"};

        // Configuration
        CommandLineConfigSource cmdLineSource = new CommandLineConfigSource(cmdLine);
        ConfigSources config = new ConfigSources();
        config.add(cmdLineSource);
        config.add(new JettyHomeConfigSource(homeDir));
        config.add(new JettyBaseConfigSource(baseDir));

        // Initialize
        BaseHome basehome = new BaseHome(config);

        StartArgs args = new StartArgs(basehome);
        args.parse(config);

        Modules modules = new Modules(basehome, args);
        modules.registerAll();

        Path dotFile = basehome.getBasePath("graph.dot");

        ModuleGraphWriter writer = new ModuleGraphWriter();
        writer.write(modules, dotFile);

        assertThat("Output File Exists", FS.exists(dotFile), is(true));

        assertTimeout(Duration.ofSeconds(3), () ->
        {
            if (execDotCmd("dot", "-V"))
            {
                Path outputPng = testdir.getPath().resolve("output.png");
                assertTrue(execDotCmd("dot", "-Tpng", "-o" + outputPng, dotFile.toString()));

                assertThat("PNG File does not exist", FS.exists(outputPng));
            }
        });
    }

    private boolean execDotCmd(String... args)
    {
        try
        {
            Process p = Runtime.getRuntime().exec(args);

            try (BufferedReader bri = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                 BufferedReader bre = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = bri.readLine()) != null)
                {
                    System.out.printf("[STDIN] %s%n", line);
                }
                while ((line = bre.readLine()) != null)
                {
                    System.out.printf("[STDERR] %s%n", line);
                }
            }
            p.waitFor();
            return true;
        }
        catch (IOException | InterruptedException e)
        {
            e.printStackTrace();
            return false;
        }
    }
}
