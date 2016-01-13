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
package org.eclipse.jetty.quickstart;

import static org.junit.Assert.assertEquals;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.util.resource.Resource;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class AttributeNormalizerTest
{
    @Parameters(name="{0} = {1}")
    public static List<String[]> data()
    {
        String[][] tests = { 
                { "WAR", "/opt/jetty-distro/demo.base/webapps/root" }, 
                { "jetty.home", "/opt/jetty-distro" },
                { "jetty.base", "/opt/jetty-distro/demo.base" }, 
                { "user.home", "/home/user" }, 
                { "user.dir", "/etc/init.d" }, 
        };

        return Arrays.asList(tests);
    }

    private static String origJettyBase;
    private static String origJettyHome;
    private static String origUserHome;
    private static String origUserDir;

    @BeforeClass
    public static void initProperties()
    {
        origJettyBase = System.getProperty("jetty.base");
        origJettyHome = System.getProperty("jetty.home");
        origUserHome = System.getProperty("user.home");
        origUserDir = System.getProperty("user.dir");

        System.setProperty("jetty.home","/opt/jetty-distro");
        System.setProperty("jetty.base","/opt/jetty-distro/demo.base");
        System.setProperty("user.home","/home/user");
        System.setProperty("user.dir","/etc/init.d");
    }

    @AfterClass
    public static void restoreProperties()
    {
        if(origJettyBase != null) System.setProperty("jetty.base",origJettyBase);
        if(origJettyHome != null) System.setProperty("jetty.home",origJettyHome);
        if(origUserHome != null) System.setProperty("user.home",origUserHome);
        if(origUserDir != null) System.setProperty("user.dir",origUserDir);
    }

    @Parameter(0)
    public String key;

    @Parameter(1)
    public String path;

    private AttributeNormalizer normalizer;

    public AttributeNormalizerTest() throws MalformedURLException
    {
        normalizer = new AttributeNormalizer(Resource.newResource("/opt/jetty-distro/demo.base/webapps/root"));
    }

    @Test
    public void testEqual()
    {
        assertEquals("file:${" + key + "}",normalizer.normalize("file:" + path));
    }

    @Test
    public void testEqualsSlash()
    {
        assertEquals("file:${" + key + "}",normalizer.normalize("file:" + path + "/"));
    }

    @Test
    public void testEqualsSlashFile()
    {
        assertEquals("file:${" + key + "}/file",normalizer.normalize("file:" + path + "/file"));
    }

    @Test
    public void testURIEquals() throws URISyntaxException
    {
        assertEquals("file:${" + key + "}",normalizer.normalize(new URI("file:" + path)));
    }

    @Test
    public void testURIEqualsSlash() throws URISyntaxException
    {
        assertEquals("file:${" + key + "}",normalizer.normalize(new URI("file:" + path + "/")));
    }

    @Test
    public void testURIEqualsSlashFile() throws URISyntaxException
    {
        assertEquals("file:${" + key + "}/file",normalizer.normalize(new URI("file:" + path + "/file")));
    }

    @Test
    public void testURLEquals() throws MalformedURLException
    {
        assertEquals("file:${" + key + "}",normalizer.normalize(new URL("file:" + path)));
    }

    @Test
    public void testURLEqualsSlash() throws MalformedURLException
    {
        assertEquals("file:${" + key + "}",normalizer.normalize(new URL("file:" + path + "/")));
    }

    @Test
    public void testURLEqualsSlashFile() throws MalformedURLException
    {
        assertEquals("file:${" + key + "}/file",normalizer.normalize(new URL("file:" + path + "/file")));
    }

    @Test
    public void testJarFileEquals_BangFile()
    {
        assertEquals("jar:file:${" + key + "}!/file",normalizer.normalize("jar:file:" + path + "!/file"));
    }

    @Test
    public void testJarFileEquals_SlashBangFile()
    {
        assertEquals("jar:file:${" + key + "}!/file",normalizer.normalize("jar:file:" + path + "/!/file"));
    }

    @Test
    public void testJarFileEquals_FileBangFile()
    {
        assertEquals("jar:file:${" + key + "}/file!/file",normalizer.normalize("jar:file:" + path + "/file!/file"));
    }

    @Test
    public void testJarFileEquals_URIBangFile() throws URISyntaxException
    {
        assertEquals("jar:file:${" + key + "}!/file",normalizer.normalize(new URI("jar:file:" + path + "!/file")));
    }

    @Test
    public void testJarFileEquals_URISlashBangFile() throws URISyntaxException
    {
        assertEquals("jar:file:${" + key + "}!/file",normalizer.normalize(new URI("jar:file:" + path + "/!/file")));
    }

    @Test
    public void testJarFileEquals_URIFileBangFile() throws URISyntaxException
    {
        assertEquals("jar:file:${" + key + "}/file!/file",normalizer.normalize(new URI("jar:file:" + path + "/file!/file")));
    }

    @Test
    public void testJarFileEquals_URLBangFile() throws MalformedURLException
    {
        assertEquals("jar:file:${" + key + "}!/file",normalizer.normalize(new URL("jar:file:" + path + "!/file")));
    }

    @Test
    public void testJarFileEquals_URLSlashBangFile() throws MalformedURLException
    {
        assertEquals("jar:file:${" + key + "}!/file",normalizer.normalize(new URL("jar:file:" + path + "/!/file")));
    }

    @Test
    public void testJarFileEquals_URLFileBangFile() throws MalformedURLException
    {
        assertEquals("jar:file:${" + key + "}/file!/file",normalizer.normalize(new URL("jar:file:" + path + "/file!/file")));
    }
}
