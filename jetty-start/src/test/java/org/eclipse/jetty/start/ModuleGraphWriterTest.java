//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.is;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import org.eclipse.jetty.start.config.CommandLineConfigSource;
import org.eclipse.jetty.start.config.ConfigSources;
import org.eclipse.jetty.start.config.JettyBaseConfigSource;
import org.eclipse.jetty.start.config.JettyHomeConfigSource;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ModuleGraphWriterTest
{
    @Rule
    public TestingDir testdir = new TestingDir();

    @Test
    public void testGenerate_NothingEnabled() throws IOException
    {
        // Test Env
        File homeDir = MavenTestingUtils.getTestResourceDir("dist-home");
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
        
        StartArgs args = new StartArgs(basehome);
        args.parse(config);

        Modules modules = new Modules(basehome, args);
        modules.registerAll();

        Path outputFile = basehome.getBasePath("graph.dot");

        ModuleGraphWriter writer = new ModuleGraphWriter();
        writer.write(modules,outputFile);

        Assert.assertThat("Output File Exists",FS.exists(outputFile),is(true));
    }
}
