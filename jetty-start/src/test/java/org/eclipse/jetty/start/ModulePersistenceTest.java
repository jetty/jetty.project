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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.toolchain.test.FS;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ModulePersistenceTest
{
    @Rule
    public TestingDir testdir = new TestingDir();

    @Test
    public void testLoadNoFile() throws IOException
    {
        File baseDir = testdir.getEmptyDir();

        File mfile = new File(baseDir,"modules.p");
        ModulePersistence persistence = new ModulePersistence(mfile);
        Assert.assertThat("persistence.enabled.size",persistence.getEnabled().size(),is(0));
    }

    @Test
    public void testOneLine() throws IOException
    {
        File baseDir = testdir.getEmptyDir();
        File mfile = new File(baseDir,"modules.p");

        writeFile(mfile,"hello");

        ModulePersistence persistence = new ModulePersistence(mfile);
        Assert.assertThat("persistence.enabled",persistence.getEnabled(),containsInAnyOrder("hello"));
    }

    @Test
    public void testDuplicateLines() throws IOException
    {
        File baseDir = testdir.getEmptyDir();
        File mfile = new File(baseDir,"modules.p");

        writeFile(mfile,"hello","there","earthling","hello");

        ModulePersistence persistence = new ModulePersistence(mfile);
        Assert.assertThat("persistence.enabled",persistence.getEnabled(),containsInAnyOrder("hello","there","earthling"));
    }

    @Test
    public void testDisableHttp() throws Exception
    {
        File homeDir = MavenTestingUtils.getTestResourceDir("usecases/home");
        File baseDir = testdir.getEmptyDir();
        File modulesDir = testdir.getFile("modules");
        FS.ensureEmpty(modulesDir);
        File mfile = new File(modulesDir,"enabled");

        writeFile(mfile,"http","websocket");

        ModulePersistence persistence = new ModulePersistence(mfile);
        Assert.assertThat("persistence.enabled",persistence.getEnabled(),containsInAnyOrder("http","websocket"));

        Main main = new Main();
        List<String> cmds = new ArrayList<>();
        cmds.add("jetty.home=" + homeDir.getAbsolutePath());
        cmds.add("jetty.base=" + baseDir.getAbsolutePath());
        cmds.add("--disable-module=http");
        StartArgs args = main.processCommandLine(cmds);
        
        Assert.assertThat("isRun", args.isRun(), is(false));
        
        main.start(args);

        // Load persistence file again
        persistence = new ModulePersistence(mfile);
        Assert.assertThat("persistence.enabled",persistence.getEnabled(),containsInAnyOrder("server","websocket"));
    }
    
    @Test
    public void testDisableAnnotations() throws Exception
    {
        File homeDir = MavenTestingUtils.getTestResourceDir("usecases/home");
        File baseDir = testdir.getEmptyDir();
        File modulesDir = testdir.getFile("modules");
        FS.ensureEmpty(modulesDir);
        File mfile = new File(modulesDir,"enabled");

        writeFile(mfile,"http","websocket");

        ModulePersistence persistence = new ModulePersistence(mfile);
        Assert.assertThat("persistence.enabled",persistence.getEnabled(),containsInAnyOrder("http","websocket"));

        Main main = new Main();
        List<String> cmds = new ArrayList<>();
        cmds.add("jetty.home=" + homeDir.getAbsolutePath());
        cmds.add("jetty.base=" + baseDir.getAbsolutePath());
        cmds.add("--disable-module=annotations");
        StartArgs args = main.processCommandLine(cmds);
        
        Assert.assertThat("isRun", args.isRun(), is(false));
        
        main.start(args);

        // Load persistence file again
        persistence = new ModulePersistence(mfile);
        Assert.assertThat("persistence.enabled",persistence.getEnabled(),containsInAnyOrder("http"));
    }
    
    @Test
    public void testEnableWebSocket() throws Exception
    {
        File homeDir = MavenTestingUtils.getTestResourceDir("usecases/home");
        File baseDir = testdir.getEmptyDir();
        File modulesDir = testdir.getFile("modules");
        FS.ensureEmpty(modulesDir);
        File mfile = new File(modulesDir,"enabled");

        writeFile(mfile,"http");

        ModulePersistence persistence = new ModulePersistence(mfile);
        Assert.assertThat("persistence.enabled",persistence.getEnabled(),containsInAnyOrder("http"));

        Main main = new Main();
        List<String> cmds = new ArrayList<>();
        cmds.add("jetty.home=" + homeDir.getAbsolutePath());
        cmds.add("jetty.base=" + baseDir.getAbsolutePath());
        cmds.add("--enable-module=websocket");
        StartArgs args = main.processCommandLine(cmds);
        
        Assert.assertThat("isRun", args.isRun(), is(false));
        
        main.start(args);

        // Load persistence file again
        persistence = new ModulePersistence(mfile);
        Assert.assertThat("persistence.enabled",persistence.getEnabled(),containsInAnyOrder("http","websocket"));
    }

    private void writeFile(File mfile, String... lines) throws IOException
    {
        final String LN = System.getProperty("line.separator");

        try (FileWriter writer = new FileWriter(mfile,false))
        {
            for (String line : lines)
            {
                writer.append(line).append(LN);
            }
        }
    }
}
