//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/**
 * Various Home + Base use cases
 */
@RunWith(Parameterized.class)
public class TestUseCases
{
    @Parameters(name = "{0}")
    public static List<Object[]> getCases()
    {
        List<Object[]> ret = new ArrayList<>();

        ret.add(new String[] {"barebones", null});
        ret.add(new String[] {"include-jetty-dir-logging", null});
        ret.add(new String[] {"jmx", null});
        ret.add(new String[] {"logging", null});
        ret.add(new String[] {"jsp", null});
        ret.add(new String[] {"database", null});
        ret.add(new String[] {"deep-ext", null});
        
        // Ones with command lines
        ret.add(new Object[] {"http2", new String[]{"java.version=1.7.0_60"}});
        ret.add(new Object[] {"basic-properties", new String[]{"port=9090"}});
        ret.add(new Object[] {"agent-properties", new String[]{"java.vm.specification.version=1.6"}});
        
        return ret;
    }

    @Parameter(0)
    public String caseName;

    @Parameter(1)
    public String[] commandLineArgs;

    @Test
    public void testUseCase() throws Exception
    {
        File homeDir = MavenTestingUtils.getTestResourceDir("dist-home");
        File baseDir = MavenTestingUtils.getTestResourceDir("usecases/" + caseName);

        Main main = new Main();
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add("jetty.home=" + homeDir.getAbsolutePath());
        cmdLine.add("jetty.base=" + baseDir.getAbsolutePath());
        // cmdLine.add("--debug");

        if (commandLineArgs != null)
        {
            for (String arg : commandLineArgs)
            {
                cmdLine.add(arg);
            }
        }

        StartArgs args = main.processCommandLine(cmdLine);
        BaseHome baseHome = main.getBaseHome();
        ConfigurationAssert.assertConfiguration(baseHome,args,"usecases/" + caseName + ".assert.txt");
    }
}
