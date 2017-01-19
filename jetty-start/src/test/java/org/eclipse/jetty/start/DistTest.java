//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import static org.eclipse.jetty.start.StartMatchers.fileExists;
import static org.eclipse.jetty.start.StartMatchers.notPathExists;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

/**
 * Test various things with a semi-valid src/test/resources/dist-home/
 */
public class DistTest
{
    @Rule
    public TestingDir testdir = new TestingDir();

    @Rule
    public SystemExitAsException exitrule = new SystemExitAsException();

    private void execMain(List<String> cmds) throws Exception
    {
        int len = cmds.size();
        String args[] = cmds.toArray(new String[len]);

        Main main = new Main();
        StartArgs startArgs = main.processCommandLine(args);
        main.start(startArgs);
    }

    public List<String> getBaseCommandLine(Path basePath)
    {
        List<String> cmds = new ArrayList<String>();
        cmds.add("-Djava.io.tmpdir=" + MavenTestingUtils.getTargetDir().getAbsolutePath());
        cmds.add("-Djetty.home=" + MavenTestingUtils.getTestResourceDir("dist-home").getAbsolutePath());
        cmds.add("-Djetty.base=" + basePath.normalize().toAbsolutePath().toString());
        cmds.add("--testing-mode");

        return cmds;
    }

    @Test
    public void testLikeDistro_SetupHome() throws Exception
    {
        Path basePath = testdir.getEmptyDir().toPath();

        List<String> cmds = getBaseCommandLine(basePath);

        cmds.add("--add-to-start=deploy,websocket,ext,resources,jsp,jstl,http");

        execMain(cmds);
    }
    
    @Test
    public void testAddJstl() throws Exception
    {
        Path basePath = testdir.getEmptyDir().toPath();

        List<String> cmds = getBaseCommandLine(basePath);
        cmds.add("--add-to-start=jstl");
        execMain(cmds);
        
        Path startIni = basePath.resolve("start.ini");
        assertThat("start.ini", startIni, fileExists());

        List<String> startIniLines = new TextFile(startIni).getLines();
        // Modules that should be present
        assertThat("start.ini", startIniLines, hasItem("--module=jstl"));
        assertThat("start.ini", startIniLines, hasItem("--module=server"));
        
        // Test for modules that should not be present.
        // Namely modules that are transitive and without ini-template.
        assertThat("start.ini", startIniLines, not(hasItem("--module=servlet")));
        assertThat("start.ini", startIniLines, not(hasItem("--module=apache-jsp")));
        assertThat("start.ini", startIniLines, not(hasItem("--module=apache-jstl")));
        assertThat("start.ini", startIniLines, not(hasItem("--module=jndi")));
        assertThat("start.ini", startIniLines, not(hasItem("--module=security")));
        assertThat("start.ini", startIniLines, not(hasItem("--module=webapp")));
        assertThat("start.ini", startIniLines, not(hasItem("--module=plus")));
        assertThat("start.ini", startIniLines, not(hasItem("--module=annotations")));
        assertThat("start.ini", startIniLines, not(hasItem("--module=jsp")));
        
    }
    
    /**
     * Test for https://bugs.eclipse.org/452329
     * @throws Exception on test failure
     */
    @Test
    public void testReAddServerModule() throws Exception
    {
        Path basePath = testdir.getEmptyDir().toPath();

        List<String> cmds = getBaseCommandLine(basePath);
        cmds.add("--add-to-startd=http");
        execMain(cmds);
        
        Path httpIni = basePath.resolve("start.d/http.ini");
        Path serverIni = basePath.resolve("start.d/server.ini");
        
        assertThat("start.d/http.ini", httpIni, fileExists());
        assertThat("start.d/server.ini", serverIni, fileExists());
        
        // Delete server.ini
        Files.deleteIfExists(serverIni);
        
        // Attempt to re-add via 'server' module reference
        cmds = getBaseCommandLine(basePath);
        cmds.add("--add-to-startd=server");
        execMain(cmds);
        
        assertThat("start.d/server.ini", serverIni, fileExists());
    }
    
    /**
     * Test for https://bugs.eclipse.org/452329
     * @throws Exception on test failure
     */
    @Test
    public void testReAddServerViaHttpModule() throws Exception
    {
        Path basePath = testdir.getEmptyDir().toPath();

        List<String> cmds = getBaseCommandLine(basePath);
        cmds.add("--add-to-startd=http");
        execMain(cmds);
        
        Path httpIni = basePath.resolve("start.d/http.ini");
        Path serverIni = basePath.resolve("start.d/server.ini");
        
        assertThat("start.d/http.ini", httpIni, fileExists());
        assertThat("start.d/server.ini", serverIni, fileExists());
        
        // Delete server.ini
        Files.deleteIfExists(serverIni);
        
        // Attempt to re-add via 'http' module reference
        cmds = getBaseCommandLine(basePath);
        cmds.add("--add-to-startd=http");
        execMain(cmds);
        
        assertThat("start.d/server.ini", serverIni, fileExists());
    }
    
    /**
     * Test for https://bugs.eclipse.org/452329
     * @throws Exception on test failure
     */
    @Test
    public void testReAddHttpThenDeployViaStartD() throws Exception
    {
        Path basePath = testdir.getEmptyDir().toPath();

        List<String> cmds = getBaseCommandLine(basePath);
        cmds.add("--add-to-start=http");
        execMain(cmds);
        
        Path startIni = basePath.resolve("start.ini");
        assertThat("start.ini", startIni, fileExists());

        // Now add 'deploy' module.
        cmds = getBaseCommandLine(basePath);
        cmds.add("--add-to-startd=deploy");
        execMain(cmds);
        
        // The following files should not exist (as its already defined in /start.ini)
        Path serverIni = basePath.resolve("start.d/server.ini");
        assertThat("start.d/server.ini", serverIni, notPathExists());
    }
    
    @Test
    @Ignore("See https://bugs.eclipse.org/451973")
    public void testLikeDistro_SetupDemoBase() throws Exception
    {
        Path basePath = testdir.getEmptyDir().toPath();

        List<String> cmds = getBaseCommandLine(basePath);

        cmds.add("--add-to-start=continuation,deploy,websocket,ext,resources,client,annotations,jndi,servlets");
        cmds.add("--add-to-startd=jsp,jstl,http,https");

        execMain(cmds);
    }
}
