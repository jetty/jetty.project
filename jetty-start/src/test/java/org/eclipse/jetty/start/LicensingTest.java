//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

/**
 * Test various license handling.
 */
public class LicensingTest
{
    @Rule
    public TestingDir testdir = new TestingDir();

    @Rule
    public SystemExitAsException exitrule = new SystemExitAsException();

    private String assertFileExists(Path basePath, String name) throws IOException
    {
        Path file = basePath.resolve(OS.separators(name));
        FS.exists(file);
        return IO.readToString(file.toFile());
    }

    private void execMain(List<String> cmds) throws Exception
    {
        int len = cmds.size();
        String args[] = cmds.toArray(new String[len]);

        System.err.printf("%n## Exec: %s%n", Utils.join(cmds,", "));
        Main main = new Main();
        StartArgs startArgs = main.processCommandLine(args);
        main.start(startArgs);
    }

    public List<String> getBaseCommandLine(Path basePath)
    {
        List<String> cmds = new ArrayList<String>();
        cmds.add("-Djava.io.tmpdir=" + MavenTestingUtils.getTargetDir().getAbsolutePath());
        cmds.add("-Djetty.home=" + MavenTestingUtils.getTestResourceDir("dist-home").getAbsolutePath());
        cmds.add("-Djetty.base=" + basePath.toString());
        cmds.add("--testing-mode");

        return cmds;
    }

    @Test
    public void testAdd_NoLicensed() throws Exception
    {
        Path basePath = testdir.getEmptyPathDir();

        List<String> cmds = getBaseCommandLine(basePath);

        cmds.add("--add-to-start=http,deploy");

        execMain(cmds);
    }

    @Test
    public void testAdd_CDI_Licensed() throws Exception
    {
        Path basePath = testdir.getEmptyPathDir();

        List<String> cmds = getBaseCommandLine(basePath);

        cmds.add("-Dorg.eclipse.jetty.start.ack.licenses=true");
        cmds.add("--add-to-start=cdi");

        execMain(cmds);
    }
    
    @Test
    public void testAdd_HTTP2_Licensed() throws Exception
    {
        assumeJavaVersionSupportsALPN();

        Path basePath = testdir.getEmptyPathDir();

        List<String> cmds = getBaseCommandLine(basePath);

        cmds.add("-Dorg.eclipse.jetty.start.ack.licenses=true");
        cmds.add("--add-to-start=http2");

        execMain(cmds);
        
        String contents = assertFileExists(basePath, "start.ini");
        assertThat("Contents",contents,containsString("--module=http2"+System.lineSeparator()));
    }
    
    @Test
    public void testAdd_Http_Http2_Then_Deploy() throws Exception
    {
        assumeJavaVersionSupportsALPN();

        Path basePath = testdir.getEmptyPathDir();

        List<String> cmds = getBaseCommandLine(basePath);

        cmds.add("-Dorg.eclipse.jetty.start.ack.license.protonego-impl=true");
        cmds.add("--add-to-start=http,http2");

        execMain(cmds);
        
        String contents = assertFileExists(basePath, "start.ini");
        assertThat("Contents",contents,containsString("--module=http"+System.lineSeparator()));
        assertThat("Contents",contents,containsString("--module=http2"+System.lineSeparator()));
        
        // now request deploy (no license check should occur)
        List<String> cmds2 = getBaseCommandLine(basePath);
        cmds2.add("--add-to-start=deploy");
        execMain(cmds2);

        contents = assertFileExists(basePath, "start.ini");
        assertThat("Contents",contents,containsString("--module=deploy"+System.lineSeparator()));
    }
    
    @Test
    public void testCreate_HTTP2_Licensed() throws Exception
    {
        assumeJavaVersionSupportsALPN();

        Path basePath = testdir.getEmptyPathDir();

        List<String> cmds = getBaseCommandLine(basePath);

        cmds.add("-Dorg.eclipse.jetty.start.ack.licenses=true");
        cmds.add("--dry-run");
        
        StringReader startIni = new StringReader("--module=http2\n");
        try (BufferedWriter writer = Files.newBufferedWriter(basePath.resolve("start.ini")))
        {
            IO.copy(startIni,writer);
        }

        execMain(cmds);
    }

    @Test
    public void testCreate_CDI_Licensed() throws Exception
    {
        Path basePath = testdir.getEmptyPathDir();

        List<String> cmds = getBaseCommandLine(basePath);

        cmds.add("-Dorg.eclipse.jetty.start.ack.licenses=true");
        cmds.add("--create-files");

        StringReader startIni = new StringReader("--module=cdi\n");
        try (BufferedWriter writer = Files.newBufferedWriter(basePath.resolve("start.ini")))
        {
            IO.copy(startIni,writer);
        }

        execMain(cmds);
    }

    protected void assumeJavaVersionSupportsALPN()
    {
        boolean isALPNSupported = false;

        if (JavaVersion.VERSION.getPlatform() >= 9)
        {
            // Java 9+ is always supported with the native java ALPN support libs
            isALPNSupported = true;
        }
        else
        {
            // Java 8 updates around update 252 are not supported in Jetty 9.3 (it requires a new ALPN support library that exists only in Java 9.4+)
            try
            {
                // JDK 8u252 has the JDK 9 ALPN API backported.
                SSLParameters.class.getMethod("setApplicationProtocols", String[].class);
                SSLEngine.class.getMethod("getApplicationProtocol");
                // This means we have a new version of Java 8 that has ALPN backported, which Jetty 9.3 does not support.
                // Use Jetty 9.4 for proper support.
                isALPNSupported = false;
            }
            catch (NoSuchMethodException x)
            {
                // this means we have an old version of Java 8 that needs the XBootclasspath support libs
                isALPNSupported = true;
            }
        }

        Assume.assumeTrue("ALPN support exists", isALPNSupported);
    }
}
