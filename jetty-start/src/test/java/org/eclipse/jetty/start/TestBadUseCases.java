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

import static org.hamcrest.Matchers.containsString;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.start.util.RebuildTestResources;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/**
 * Test bad configuration scenarios.
 */
@RunWith(Parameterized.class)
public class TestBadUseCases
{
    @Parameters(name = "{0}")
    public static List<Object[]> getCases()
    {
        List<Object[]> ret = new ArrayList<>();

        ret.add(new Object[]{ "http2",
                "Missing referenced dependency: alpn-impl/alpn-0.0.0_0",
                new String[]{"java.version=0.0.0_0"}});

        ret.add(new Object[]{ "versioned-modules-too-new",
                "Module [http3] specifies jetty version [10.0] which is newer than this version of jetty [" + RebuildTestResources.JETTY_VERSION + "]",
                null});

        return ret;
    }

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    @Parameter(0)
    public String caseName;

    @Parameter(1)
    public String expectedErrorMessage;

    @Parameter(2)
    public String[] commandLineArgs;

    // TODO unsure how this failure should be handled
    @Test
    @Ignore
    public void testBadConfig() throws Exception
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

        expectedException.expect(UsageException.class);
        expectedException.expectMessage(containsString(expectedErrorMessage));
        main.processCommandLine(cmdLine);
    }
}
