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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.Assert;
import org.junit.Test;

public class BaseHomeTest
{
    private void assertFileList(BaseHome hb, String message, List<String> expected, List<File> files)
    {
        List<String> actual = new ArrayList<>();
        for (File file : files)
        {
            actual.add(hb.toShortForm(file));
        }
        Assert.assertThat(message + ": " + Main.join(actual,", "),actual,containsInAnyOrder(expected.toArray()));
    }

    @Test
    public void testGetFile_OnlyHome() throws IOException
    {
        File homeDir = MavenTestingUtils.getTestResourceDir("hb.1/home");
        File baseDir = null;

        BaseHome hb = new BaseHome(homeDir,baseDir);
        File startIni = hb.getFile("/start.ini");

        String ref = hb.toShortForm(startIni);
        Assert.assertThat("Reference",ref,startsWith("${jetty.home}"));

        String contents = IO.readToString(startIni);
        Assert.assertThat("Contents",contents,containsString("Home Ini"));
    }

    @Test
    public void testListFiles_OnlyHome() throws IOException
    {
        File homeDir = MavenTestingUtils.getTestResourceDir("hb.1/home");
        File baseDir = null;

        BaseHome hb = new BaseHome(homeDir,baseDir);
        List<File> files = hb.listFiles("/start.d");

        List<String> expected = new ArrayList<>();
        expected.add("${jetty.home}/start.d/jmx.ini");
        expected.add("${jetty.home}/start.d/jndi.ini");
        expected.add("${jetty.home}/start.d/jsp.ini");
        expected.add("${jetty.home}/start.d/logging.ini");
        expected.add("${jetty.home}/start.d/ssl.ini");

        assertFileList(hb,"Files found",expected,files);
    }

    @Test
    public void testListFiles_Filtered_OnlyHome() throws IOException
    {
        File homeDir = MavenTestingUtils.getTestResourceDir("hb.1/home");
        File baseDir = null;

        BaseHome hb = new BaseHome(homeDir,baseDir);
        List<File> files = hb.listFiles("/start.d",new FS.IniFilter());

        List<String> expected = new ArrayList<>();
        expected.add("${jetty.home}/start.d/jmx.ini");
        expected.add("${jetty.home}/start.d/jndi.ini");
        expected.add("${jetty.home}/start.d/jsp.ini");
        expected.add("${jetty.home}/start.d/logging.ini");
        expected.add("${jetty.home}/start.d/ssl.ini");

        assertFileList(hb,"Files found",expected,files);
    }

    @Test
    public void testListFiles_Both() throws IOException
    {
        File homeDir = MavenTestingUtils.getTestResourceDir("hb.1/home");
        File baseDir = MavenTestingUtils.getTestResourceDir("hb.1/base");

        BaseHome hb = new BaseHome(homeDir,baseDir);
        List<File> files = hb.listFiles("/start.d");

        List<String> expected = new ArrayList<>();
        expected.add("${jetty.base}/start.d/jmx.ini");
        expected.add("${jetty.home}/start.d/jndi.ini");
        expected.add("${jetty.home}/start.d/jsp.ini");
        expected.add("${jetty.base}/start.d/logging.ini");
        expected.add("${jetty.home}/start.d/ssl.ini");
        expected.add("${jetty.base}/start.d/myapp.ini");

        assertFileList(hb,"Files found",expected,files);
    }

    @Test
    public void testDefault() throws IOException
    {
        BaseHome bh = new BaseHome();
        Assert.assertThat("Home",bh.getHome(),notNullValue());
        Assert.assertThat("Base",bh.getBase(),notNullValue());
    }

    @Test
    public void testGetFile_Both() throws IOException
    {
        File homeDir = MavenTestingUtils.getTestResourceDir("hb.1/home");
        File baseDir = MavenTestingUtils.getTestResourceDir("hb.1/base");

        BaseHome hb = new BaseHome(homeDir,baseDir);
        File startIni = hb.getFile("/start.ini");

        String ref = hb.toShortForm(startIni);
        Assert.assertThat("Reference",ref,startsWith("${jetty.base}"));

        String contents = IO.readToString(startIni);
        Assert.assertThat("Contents",contents,containsString("Base Ini"));
    }
}
