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

package org.eclipse.jetty.npn.server;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.FileArg;
import org.eclipse.jetty.start.Module;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class NPNModuleTest
{
    @Parameters(name = "{index}: mod:{0}")
    public static List<Object[]> data()
    {
        File npnBootModDir = MavenTestingUtils.getProjectDir("src/main/config/modules/protonego-impl");
        List<Object[]> data = new ArrayList<>();
        for (File file : npnBootModDir.listFiles())
        {
            if (Pattern.matches("npn-.*\\.mod",file.getName()))
            {
                data.add(new Object[] { file.getName() });
            }
        }
        return data;
    }

    @Parameter(value = 0)
    public String modBootFile;

    private static BaseHome basehome;

    @BeforeClass
    public static void initBaseHome() throws IOException
    {
        File homeDir = MavenTestingUtils.getProjectDir("src/main/config");
        File baseDir = MavenTestingUtils.getTargetTestingDir(NPNModuleTest.class.getName());
        FS.ensureEmpty(baseDir);
        
        String cmdLine[] = { "jetty.home="+homeDir.getAbsolutePath(),"jetty.base="+baseDir.getAbsolutePath() };
        basehome = new BaseHome(cmdLine);
    }

    /**
     * Check the sanity of the npn-boot file module 
     */
    @Test
    public void testModuleValues() throws IOException
    {
        Path modFile = basehome.getPath("modules/protonego-impl/" + modBootFile);
        Module mod = new Module(basehome,modFile);
        assertNotNull("module",mod);
        
        // Validate logical name
        assertThat("Module name",mod.getName(),is("protonego-boot"));

        List<String> expectedBootClasspath = new ArrayList<>();

        for (String line : mod.getFiles())
        {
            FileArg farg = new FileArg(line);
            if (farg.uri != null)
            {
                assertThat("NPN URL", farg.uri, startsWith("maven://org.mortbay.jetty.npn/npn-boot/"));
                expectedBootClasspath.add("-Xbootclasspath/p:" + farg.location);
            }
        }

        for (String line : mod.getJvmArgs())
        {
            expectedBootClasspath.remove(line);
        }

        if (expectedBootClasspath.size() > 0)
        {
            StringBuilder err = new StringBuilder();
            err.append("XBootClasspath mismatch between [files] and [exec]");
            err.append("\nThe following are inferred from your [files] definition in ");
            err.append(modFile.toAbsolutePath().toString());
            err.append("\nbut are not referenced in your [exec] section");
            for (String entry : expectedBootClasspath)
            {
                err.append("\n").append(entry);
            }
            fail(err.toString());
        }
    }
}
