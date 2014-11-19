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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.toolchain.test.TestingDir;
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

    protected String assertFileExists(File basePath, String name) throws IOException
    {
        File file = new File(basePath, OS.separators(name));
        FS.exists(file.toPath());
        return IO.readToString(file);
    }

    private void execMain(List<String> cmds) throws Exception
    {
        int len = cmds.size();
        String args[] = cmds.toArray(new String[len]);

        Main main = new Main();
        StartArgs startArgs = main.processCommandLine(args);
        main.start(startArgs);
    }

    public List<String> getBaseCommandLine(File basePath)
    {
        List<String> cmds = new ArrayList<String>();
        cmds.add("-Djava.io.tmpdir=" + MavenTestingUtils.getTargetDir().getAbsolutePath());
        cmds.add("-Djetty.home=" + MavenTestingUtils.getTestResourceDir("dist-home").getAbsolutePath());
        cmds.add("-Djetty.base=" + basePath.getAbsolutePath());
        cmds.add("--testing-mode");

        return cmds;
    }

    @Test
    public void testLikeDistro_SetupHome() throws Exception
    {
        File basePath = testdir.getEmptyDir();

        List<String> cmds = getBaseCommandLine(basePath);

        cmds.add("--add-to-start=deploy,websocket,ext,resources,jsp,jstl,http");

        execMain(cmds);
    }
}
