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

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.util.resource.Resource;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

@RunWith(Parameterized.class)
public class AttributeNormalizerPathTest
{
    @Parameters(name="{0} = {1}")
    public static List<String[]> data()
    {
        String[][] tests = { 
                // Can't test 'WAR' property, as its not a Path (which this testcase works with)
                // { "WAR", toSystemPath("http://localhost/resources/webapps/root") }, 
                { "jetty.home", toSystemPath("/opt/jetty-distro") },
                { "jetty.base", toSystemPath("/opt/jetty-distro/demo.base") }, 
                { "user.home", toSystemPath("/home/user") }, 
                { "user.dir", toSystemPath("/etc/init.d") }, 
        };

        return Arrays.asList(tests);
    }

    /**
     * As the declared paths in this testcase might be actual paths on the system
     * running these tests, the expected paths should be cleaned up to represent
     * the actual system paths.
     * <p>
     * Eg: on fedora /etc/init.d is a symlink to /etc/rc.d/init.d
     */
    public static String toSystemPath(String rawpath)
    {
        Path path = FileSystems.getDefault().getPath(rawpath);
        if (Files.exists(path))
        {
            // It exists, resolve it to the real path
            try
            {
                path = path.toRealPath();
            }
            catch (IOException e)
            {
                // something prevented us from resolving to real path, fallback to
                // absolute path resolution (not as accurate)
                path = path.toAbsolutePath();
                e.printStackTrace();
            }
        }
        else
        {
            // File doesn't exist, resolve to absolute path
            // We can't rely on File.toCanonicalPath() here
            path = path.toAbsolutePath();
        }
        return path.toString();
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

    public String key;
    public String path;

    private AttributeNormalizer normalizer;

    public AttributeNormalizerPathTest(String key, String path) throws MalformedURLException, IOException
    {
        this.key = key;
        this.path = AttributeNormalizer.uriSeparators(path);
        this.normalizer = new AttributeNormalizer(Resource.newResource("/opt/jetty-distro/demo.base/webapps/root"));
    }

    @Test
    public void testEqual()
    {
        assertThat(normalizer.normalize("file:" + path), is("file:${" + key + "}"));
    }

    @Test
    public void testEqualsSlash()
    {
        assertThat(normalizer.normalize("file:" + path + "/"), is("file:${" + key + "}"));
    }

    @Test
    public void testEqualsSlashFile()
    {
        assertThat(normalizer.normalize("file:" + path + "/file"), is("file:${" + key + "}/file"));
    }

    @Test
    public void testURIEquals() throws URISyntaxException
    {
        assertThat(normalizer.normalize(new URI("file:" + path)), is("file:${" + key + "}"));
    }

    @Test
    public void testURIEqualsSlash() throws URISyntaxException
    {
        assertThat(normalizer.normalize(new URI("file:" + path + "/")), is("file:${" + key + "}"));
    }

    @Test
    public void testURIEqualsSlashFile() throws URISyntaxException
    {
        assertThat(normalizer.normalize(new URI("file:" + path + "/file")), is("file:${" + key + "}/file"));
    }

    @Test
    public void testURLEquals() throws MalformedURLException
    {
        assertThat(normalizer.normalize(new URL("file:" + path)), is("file:${" + key + "}"));
    }

    @Test
    public void testURLEqualsSlash() throws MalformedURLException
    {
        assertThat(normalizer.normalize(new URL("file:" + path + "/")), is("file:${" + key + "}"));
    }

    @Test
    public void testURLEqualsSlashFile() throws MalformedURLException
    {
        assertThat(normalizer.normalize(new URL("file:" + path + "/file")), is("file:${" + key + "}/file"));
    }

    @Test
    public void testJarFileEquals_BangFile()
    {
        assertThat(normalizer.normalize("jar:file:" + path + "!/file"), is("jar:file:${" + key + "}!/file"));
    }

    @Test
    public void testJarFileEquals_SlashBangFile()
    {
        assertThat(normalizer.normalize("jar:file:" + path + "/!/file"), is("jar:file:${" + key + "}!/file"));
    }

    @Test
    public void testJarFileEquals_FileBangFile()
    {
        assertThat(normalizer.normalize("jar:file:" + path + "/file!/file"), is("jar:file:${" + key + "}/file!/file"));
    }

    @Test
    public void testJarFileEquals_URIBangFile() throws URISyntaxException
    {
        assertThat(normalizer.normalize(new URI("jar:file:" + path + "!/file")), is("jar:file:${" + key + "}!/file"));
    }

    @Test
    public void testJarFileEquals_URISlashBangFile() throws URISyntaxException
    {
        assertThat(normalizer.normalize(new URI("jar:file:" + path + "/!/file")), is("jar:file:${" + key + "}!/file"));
    }

    @Test
    public void testJarFileEquals_URIFileBangFile() throws URISyntaxException
    {
        assertThat(normalizer.normalize(new URI("jar:file:" + path + "/file!/file")), is("jar:file:${" + key + "}/file!/file"));
    }

    @Test
    public void testJarFileEquals_URLBangFile() throws MalformedURLException
    {
        assertThat(normalizer.normalize(new URL("jar:file:" + path + "!/file")), is("jar:file:${" + key + "}!/file"));
    }

    @Test
    public void testJarFileEquals_URLSlashBangFile() throws MalformedURLException
    {
        assertThat(normalizer.normalize(new URL("jar:file:" + path + "/!/file")), is("jar:file:${" + key + "}!/file"));
    }

    @Test
    public void testJarFileEquals_URLFileBangFile() throws MalformedURLException
    {
        assertThat(normalizer.normalize(new URL("jar:file:" + path + "/file!/file")), is("jar:file:${" + key + "}/file!/file"));
    }
}
