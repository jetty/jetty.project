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

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.Test;

/**
 * Test bad configuration scenarios.
 */
public class TestBadUseCases
{
    private void assertBadConfig(String homeName, String baseName, String expectedErrorMessage, String... cmdLineArgs) throws Exception
    {
        File homeDir = MavenTestingUtils.getTestResourceDir("usecases/" + homeName);
        File baseDir = MavenTestingUtils.getTestResourceDir("usecases/" + baseName);

        Main main = new Main();
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add("jetty.home=" + homeDir.getAbsolutePath());
        cmdLine.add("jetty.base=" + baseDir.getAbsolutePath());
        // cmdLine.add("--debug");
        for (String arg : cmdLineArgs)
        {
            cmdLine.add(arg);
        }

        try
        {
            main.processCommandLine(cmdLine);
            fail("Expected " + UsageException.class.getName());
        }
        catch (UsageException e)
        {
            assertThat("Usage error",e.getMessage(),containsString(expectedErrorMessage));
        }
    }

    @Test
    public void testBadJspCommandLine() throws Exception
    {
        assertBadConfig("home","base.with.jsp.default",
                "Missing referenced dependency: jsp-impl/bad-jsp","jsp-impl=bad");
    }

    @Test
    public void testBadJspImplName() throws Exception
    {
        assertBadConfig("home","base.with.jsp.bad",
                "Missing referenced dependency: jsp-impl/bogus-jsp");
    }
    
    @Test
    public void testWithSpdyBadNpnVersion() throws Exception
    {
        assertBadConfig("home","base.enable.spdy.bad.npn.version",
                "Missing referenced dependency: protonego-impl/npn-1.7.0_01",
                "java.version=1.7.0_01", "protonego=npn");
    }


}
