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

package org.eclipse.jetty.spdy.server;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.FileArg;
import org.eclipse.jetty.start.Module;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class NPNModuleTest
{
    /** This is here to prevent pointless download attempts */
    private static final List<String> KNOWN_GOOD_NPN_URLS = new ArrayList<>();

    static
    {
        /** The main() method in this test case can be run to validate this list independantly */
        KNOWN_GOOD_NPN_URLS.add("http://central.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.7.v20140316/npn-boot-1.1.7.v20140316.jar");
        KNOWN_GOOD_NPN_URLS.add("http://central.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.6.v20130911/npn-boot-1.1.6.v20130911.jar");
        KNOWN_GOOD_NPN_URLS.add("http://central.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.5.v20130313/npn-boot-1.1.5.v20130313.jar");
        KNOWN_GOOD_NPN_URLS.add("http://central.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.4.v20130313/npn-boot-1.1.4.v20130313.jar");
        KNOWN_GOOD_NPN_URLS.add("http://central.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.3.v20130313/npn-boot-1.1.3.v20130313.jar");
        KNOWN_GOOD_NPN_URLS.add("http://central.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.2.v20130305/npn-boot-1.1.2.v20130305.jar");
        KNOWN_GOOD_NPN_URLS.add("http://central.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.1.v20121030/npn-boot-1.1.1.v20121030.jar");
        KNOWN_GOOD_NPN_URLS.add("http://central.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.0.v20120525/npn-boot-1.1.0.v20120525.jar");
    }

    @Parameters(name = "{index}: mod:{0}")
    public static List<Object[]> data()
    {
        File npnBootModDir = MavenTestingUtils.getProjectDir("../spdy-http-server/src/main/config/modules/protonego-impl");
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
        File homeDir = MavenTestingUtils.getProjectDir("../spdy-http-server/src/main/config");
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
                assertTrue("Not a known good NPN URL: " + farg.uri,KNOWN_GOOD_NPN_URLS.contains(farg.uri));
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

    public static void main(String[] args)
    {
        File outputDir = MavenTestingUtils.getTargetTestingDir(NPNModuleTest.class.getSimpleName() + "-main");
        FS.ensureEmpty(outputDir);
        for (String ref : KNOWN_GOOD_NPN_URLS)
        {
            try
            {
                URL url = new URL(ref);
                System.err.printf("Attempting: %s%n",ref);
                HttpURLConnection connection = (HttpURLConnection)url.openConnection();
                String refname = url.toURI().getPath();
                int idx = refname.lastIndexOf('/');
                File outputFile = new File(outputDir,refname.substring(idx));
                try (InputStream stream = connection.getInputStream(); FileOutputStream out = new FileOutputStream(outputFile))
                {
                    assertThat("Response Status Code",connection.getResponseCode(),is(200));
                    IO.copy(stream,out);
                    System.err.printf("Downloaded %,d bytes%n",outputFile.length());
                }
                catch (IOException e)
                {
                    e.printStackTrace(System.err);
                }
            }
            catch (MalformedURLException e)
            {
                System.err.printf("Bad Ref: %s%n",ref);
                e.printStackTrace(System.err);
            }
            catch (URISyntaxException e)
            {
                System.err.printf("Bad Ref Syntax: %s%n",ref);
                e.printStackTrace(System.err);
            }
            catch (IOException e)
            {
                System.err.printf("Bad Connection: %s%n",ref);
                e.printStackTrace(System.err);
            }
        }
    }
}
