//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ConfigTest
{
    private void assertEquals(String msg, Classpath expected, Classpath actual)
    {
        Assert.assertNotNull(msg + " : expected classpath should not be null",expected);
        Assert.assertNotNull(msg + " : actual classpath should not be null",actual);
        Assert.assertTrue(msg + " : expected should have an entry",expected.count() >= 1);
        Assert.assertTrue(msg + " : actual should have an entry",actual.count() >= 1);
        if (expected.count() != actual.count())
        {
            expected.dump(System.err);
            actual.dump(System.err);
            Assert.assertEquals(msg + " : count",expected.count(),actual.count());
        }

        List<File> actualEntries = Arrays.asList(actual.getElements());
        List<File> expectedEntries = Arrays.asList(expected.getElements());

        int len = expectedEntries.size();

        for (int i = 0; i < len; i++)
        {
            File expectedFile = expectedEntries.get(i);
            File actualFile = actualEntries.get(i);
            if (!expectedFile.equals(actualFile))
            {
                expected.dump(System.err);
                actual.dump(System.err);
                Assert.assertEquals(msg + ": entry [" + i + "]",expectedEntries.get(i),actualEntries.get(i));
            }
        }
    }

    private void assertEquals(String msg, Collection<String> expected, Collection<String> actual)
    {
        Assert.assertTrue(msg + " : expected should have an entry",expected.size() >= 1);
        Assert.assertEquals(msg + " : size",expected.size(),actual.size());
        for (String expectedVal : expected)
        {
            Assert.assertTrue(msg + " : should contain <" + expectedVal + ">",actual.contains(expectedVal));
        }
    }

    private String getJettyEtcFile(String name)
    {
        File etc = new File(getTestableJettyHome(),"etc");
        return new File(etc,name).getAbsolutePath();
    }

    private File getJettyHomeDir()
    {
        return new File(getTestResourcesDir(),"jetty.home");
    }

    private String getTestableJettyHome()
    {
        return getJettyHomeDir().getAbsolutePath();
    }

    private File getTestResourcesDir()
    {
        File src = new File(System.getProperty("user.dir"),"src");
        File test = new File(src,"test");
        return new File(test,"resources");
    }
    
    @Before
    public void reset()
    {
        Config.clearProperties();
    }

    /*
     * Test for SUBJECT "/=" for assign canonical path
     */
    @Test
    public void testSubjectAssignCanonicalPath() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("test.resources.dir/=src/test/resources\n");

        Config cfg = new Config();
        cfg.parse(buf);

        Assert.assertEquals(getTestResourcesDir().getCanonicalPath(),Config.getProperty("test.resources.dir"));
    }

    /*
     * Test for SUBJECT "~=" for assigning Start Properties
     */
    @Test
    public void testSubjectAssignStartProperty() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("test.jetty.start.text~=foo\n");
        buf.append("test.jetty.start.quote~=Eatagramovabits\n");

        Config options = new Config();
        options.parse(buf);

        Assert.assertEquals("foo",Config.getProperty("test.jetty.start.text"));
        Assert.assertEquals("Eatagramovabits",Config.getProperty("test.jetty.start.quote"));
    }

    /*
     * Test for SUBJECT "=" for assigning System Properties
     */
    @Test
    public void testSubjectAssignSystemProperty() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("test.jetty.start.text=foo\n");
        buf.append("test.jetty.start.quote=Eatagramovabits\n");

        Config options = new Config();
        options.parse(buf);

        Assert.assertEquals("foo",System.getProperty("test.jetty.start.text"));
        Assert.assertEquals("Eatagramovabits",System.getProperty("test.jetty.start.quote"));
    }

    /*
     * Test for SUBJECT ending with "/**", all jar and zip components in dir (deep, recursive)
     */
    @Test
    public void testSubjectComponentDirDeep() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("$(jetty.home)/lib/**\n");

        String jettyHome = getTestableJettyHome();

        Config options = new Config();
        options.setProperty("jetty.home",jettyHome);
        options.parse(buf);

        Classpath actual = options.getClasspath();
        Classpath expected = new Classpath();

        File lib = new File(getJettyHomeDir(),"lib");

        expected.addComponent(new File(lib,"core.jar"));
        expected.addComponent(new File(lib,"example.jar"));
        expected.addComponent(new File(lib,"http.jar"));
        expected.addComponent(new File(lib,"io.jar"));
        expected.addComponent(new File(lib,"JSR.ZIP"));
        expected.addComponent(new File(lib,"LOGGING.JAR"));
        expected.addComponent(new File(lib,"server.jar"));
        expected.addComponent(new File(lib,"spec.zip"));
        expected.addComponent(new File(lib,"util.jar"));
        expected.addComponent(new File(lib,"xml.jar"));

        File ext = new File(lib,"ext");
        expected.addComponent(new File(ext,"custom-impl.jar"));
        File foo = new File(lib,"foo");
        File bar = new File(foo,"bar");
        expected.addComponent(new File(bar,"foobar.jar"));

        assertEquals("Components (Deep)",expected,actual);
    }

    /*
     * Test for SUBJECT ending with "/*", all jar and zip components in dir (shallow, no recursion)
     */
    @Test
    public void testSubjectComponentDirShallow() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("# Example of any shallow components in /lib/\n");
        buf.append("$(jetty.home)/lib/*\n");

        String jettyHome = getTestableJettyHome();

        Config options = new Config();
        options.setProperty("jetty.home",jettyHome);
        options.parse(buf);

        Classpath actual = options.getClasspath();
        Classpath expected = new Classpath();

        File lib = new File(getJettyHomeDir(),"lib");

        expected.addComponent(new File(lib,"core.jar"));
        expected.addComponent(new File(lib,"example.jar"));
        expected.addComponent(new File(lib,"http.jar"));
        expected.addComponent(new File(lib,"io.jar"));
        expected.addComponent(new File(lib,"JSR.ZIP"));
        expected.addComponent(new File(lib,"LOGGING.JAR"));
        expected.addComponent(new File(lib,"server.jar"));
        expected.addComponent(new File(lib,"spec.zip"));
        expected.addComponent(new File(lib,"util.jar"));
        expected.addComponent(new File(lib,"xml.jar"));

        assertEquals("Components (Shallow)",expected,actual);
    }

    /*
     * Test for SUBJECT ending with ".class", a Main Class
     */
    @Test
    public void testSubjectMainClass() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("org.eclipse.jetty.xml.XmlConfiguration.class");

        Config options = new Config();
        options.parse(buf);

        Assert.assertEquals("org.eclipse.jetty.xml.XmlConfiguration",options.getMainClassname());
    }

    /*
     * Test for SUBJECT ending with ".class", a Main Class
     */
    @Test
    public void testSubjectMainClassConditionalPropertySet() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("org.eclipse.jetty.xml.XmlConfiguration.class\n");
        buf.append("${start.class}.class    property start.class");

        Config options = new Config();
        options.setProperty("start.class","net.company.server.Start");
        options.parse(buf);

        Assert.assertEquals("net.company.server.Start",options.getMainClassname());
    }

    /*
     * Test for SUBJECT ending with ".class", a Main Class
     */
    @Test
    public void testSubjectMainClassConditionalPropertyUnset() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("org.eclipse.jetty.xml.XmlConfiguration.class\n");
        buf.append("${start.class}.class    property start.class");

        Config options = new Config();
        // The "start.class" property is unset.
        options.parse(buf);

        Assert.assertEquals("org.eclipse.jetty.xml.XmlConfiguration",options.getMainClassname());
    }

    /*
     * Test for SUBJECT ending with "/", a simple Classpath Entry
     */
    @Test
    public void testSubjectSimpleComponent() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("$(jetty.home)/resources/\n");

        String jettyHome = getTestableJettyHome();

        Config options = new Config();
        options.setProperty("jetty.home",jettyHome);
        options.parse(buf);

        Classpath actual = options.getClasspath();
        Classpath expected = new Classpath();

        expected.addComponent(new File(getJettyHomeDir(),"resources"));

        assertEquals("Simple Component",expected,actual);
    }

    /*
     * Test for SUBJECT ending with "/", a simple Classpath Entry
     */
    @Test
    public void testSubjectSimpleComponentMultiple() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("$(jetty.home)/resources/\n");
        buf.append("$(jetty.home)/etc/\n");

        String jettyHome = getTestableJettyHome();

        Config options = new Config();
        options.setProperty("jetty.home",jettyHome);
        options.parse(buf);

        Classpath actual = options.getClasspath();
        Classpath expected = new Classpath();

        expected.addComponent(new File(getJettyHomeDir(),"resources"));
        expected.addComponent(new File(getJettyHomeDir(),"etc"));

        assertEquals("Simple Component",expected,actual);
    }

    /*
     * Test for SUBJECT ending with "/", a simple Classpath Entry
     */
    @Test
    public void testSubjectSimpleComponentNotExists() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("$(jetty.home)/resources/\n");
        buf.append("$(jetty.home)/foo/\n");

        String jettyHome = getTestableJettyHome();

        Config options = new Config();
        options.setProperty("jetty.home",jettyHome);
        options.parse(buf);

        Classpath actual = options.getClasspath();
        Classpath expected = new Classpath();

        expected.addComponent(new File(getJettyHomeDir(),"resources"));

        assertEquals("Simple Component",expected,actual);
    }

    /*
     * Test for SUBJECT ending with ".xml", an XML Configuration File
     */
    @Test
    public void testSubjectXmlConfigAlt() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        // Doesn't exist
        buf.append("$(jetty.home)/etc/jetty.xml        nargs == 0\n");
        // test-alt does exist.
        buf.append("./src/test/resources/test-alt.xml  nargs == 0 AND ! exists $(jetty.home)/etc/jetty.xml");

        String jettyHome = getTestableJettyHome();

        Config options = new Config();
        options.setProperty("jetty.home",jettyHome);
        options.parse(buf);

        List<String> actual = options.getXmlConfigs();
        String expected = new File("src/test/resources/test-alt.xml").getAbsolutePath();
        Assert.assertEquals("XmlConfig.size",1,actual.size());
        Assert.assertEquals(expected,actual.get(0));
    }

    /*
     * Test for SUBJECT ending with ".xml", an XML Configuration File
     */
    @Test
    public void testSubjectXmlConfigDefault() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("$(jetty.home)/etc/test-jetty.xml                   nargs == 0\n");
        buf.append("./jetty-server/src/main/config/etc/test-jetty.xml  nargs == 0 AND ! exists $(jetty.home)/etc/test-jetty.xml");

        String jettyHome = getTestableJettyHome();

        Config options = new Config();
        options.setProperty("jetty.home",jettyHome);
        options.parse(buf);

        List<String> actual = options.getXmlConfigs();
        String expected = getJettyEtcFile("test-jetty.xml");
        Assert.assertEquals("XmlConfig.size",1,actual.size());
        Assert.assertEquals(expected,actual.get(0));
    }

    /*
     * Test for SUBJECT ending with ".xml", an XML Configuration File.
     */
    @Test
    public void testSubjectXmlConfigMultiple() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("$(jetty.home)/etc/test-jetty.xml           nargs == 0\n");
        buf.append("$(jetty.home)/etc/test-jetty-ssl.xml       nargs == 0\n");
        buf.append("$(jetty.home)/etc/test-jetty-security.xml  nargs == 0\n");

        String jettyHome = getTestableJettyHome();

        Config options = new Config();
        options.setProperty("jetty.home",jettyHome);
        options.parse(buf);

        List<String> actual = options.getXmlConfigs();
        List<String> expected = new ArrayList<String>();
        expected.add(getJettyEtcFile("test-jetty.xml"));
        expected.add(getJettyEtcFile("test-jetty-ssl.xml"));
        expected.add(getJettyEtcFile("test-jetty-security.xml"));

        assertEquals("Multiple XML Configs",expected,actual);
    }

    /*
     * Test Section Handling
     */
    @Test
    public void testSectionClasspathSingle() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("[All]\n");
        buf.append("$(jetty.home)/lib/core-test.jar\n");
        buf.append("$(jetty.home)/lib/util.jar\n");

        String jettyHome = getTestableJettyHome();

        Config options = new Config();
        options.setProperty("jetty.home",jettyHome);
        options.parse(buf);

        Classpath defaultClasspath = options.getClasspath();
        Assert.assertNotNull("Default Classpath should not be null",defaultClasspath);
        Classpath foocp = options.getSectionClasspath("Foo");
        Assert.assertNull("Foo Classpath should not exist",foocp);

        Classpath allcp = options.getSectionClasspath("All");
        Assert.assertNotNull("Classpath section 'All' should exist",allcp);

        File lib = new File(getJettyHomeDir(),"lib");

        Classpath expected = new Classpath();
        expected.addComponent(new File(lib,"core-test.jar"));
        expected.addComponent(new File(lib,"util.jar"));

        assertEquals("Single Classpath Section",expected,allcp);
    }

    /*
     * Test Section Handling
     */
    @Test
    public void testSectionClasspathAvailable() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("[All]\n");
        buf.append("$(jetty.home)/lib/core.jar  ! available org.eclipse.jetty.dummy.Handler\n");
        buf.append("$(jetty.home)/lib/util.jar  ! available org.eclipse.jetty.dummy.StringUtils\n");

        String jettyHome = getTestableJettyHome();

        Config options = new Config();
        options.setProperty("jetty.home",jettyHome);
        options.parse(buf);

        Classpath defaultClasspath = options.getClasspath();
        Assert.assertNotNull("Default Classpath should not be null",defaultClasspath);
        Classpath foocp = options.getSectionClasspath("Foo");
        Assert.assertNull("Foo Classpath should not exist",foocp);

        Classpath allcp = options.getSectionClasspath("All");
        Assert.assertNotNull("Classpath section 'All' should exist",allcp);

        File lib = new File(getJettyHomeDir(),"lib");

        Classpath expected = new Classpath();
        expected.addComponent(new File(lib,"core.jar"));
        expected.addComponent(new File(lib,"util.jar"));

        assertEquals("Single Classpath Section",expected,allcp);
    }

    /*
     * Test Section Handling, with multiple defined sections.
     */
    @Test
    public void testSectionClasspathMultiples() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("# default\n");
        buf.append("$(jetty.home)/lib/spec.zip\n");
        buf.append("\n");
        buf.append("[*]\n");
        buf.append("$(jetty.home)/lib/io.jar\n");
        buf.append("$(jetty.home)/lib/util.jar\n");
        buf.append("\n");
        buf.append("[All,server,default]\n");
        buf.append("$(jetty.home)/lib/core.jar\n");
        buf.append("$(jetty.home)/lib/server.jar\n");
        buf.append("$(jetty.home)/lib/http.jar\n");
        buf.append("\n");
        buf.append("[All,xml,default]\n");
        buf.append("$(jetty.home)/lib/xml.jar\n");
        buf.append("\n");
        buf.append("[All,logging]\n");
        buf.append("$(jetty.home)/lib/LOGGING.JAR\n");

        String jettyHome = getTestableJettyHome();

        Config cfg = new Config();
        cfg.setProperty("jetty.home",jettyHome);
        cfg.parse(buf);

        Classpath defaultClasspath = cfg.getClasspath();
        Assert.assertNotNull("Default Classpath should not be null",defaultClasspath);

        Classpath foocp = cfg.getSectionClasspath("Foo");
        Assert.assertNull("Foo Classpath should not exist",foocp);

        // Test if entire section list can be fetched
        Set<String> sections = cfg.getSectionIds();

        Set<String> expected = new HashSet<String>();
        expected.add(Config.DEFAULT_SECTION);
        expected.add("*");
        expected.add("All");
        expected.add("server");
        expected.add("default");
        expected.add("xml");
        expected.add("logging");

        assertEquals("Multiple Section IDs",expected,sections);

        // Test fetch of specific section by name works
        Classpath cpAll = cfg.getSectionClasspath("All");
        Assert.assertNotNull("Classpath section 'All' should exist",cpAll);

        File lib = new File(getJettyHomeDir(),"lib");

        Classpath expectedAll = new Classpath();
        expectedAll.addComponent(new File(lib,"core.jar"));
        expectedAll.addComponent(new File(lib,"server.jar"));
        expectedAll.addComponent(new File(lib,"http.jar"));
        expectedAll.addComponent(new File(lib,"xml.jar"));
        expectedAll.addComponent(new File(lib,"LOGGING.JAR"));

        assertEquals("Classpath 'All' Section",expectedAll,cpAll);

        // Test combined classpath fetch of multiple sections works
        List<String> activated = new ArrayList<String>();
        activated.add("server");
        activated.add("logging");

        Classpath cpCombined = cfg.getCombinedClasspath(activated);

        Classpath expectedCombined = new Classpath();
        // from default
        expectedCombined.addComponent(new File(lib,"spec.zip"));
        // from 'server'
        expectedCombined.addComponent(new File(lib,"core.jar"));
        expectedCombined.addComponent(new File(lib,"server.jar"));
        expectedCombined.addComponent(new File(lib,"http.jar"));
        // from 'logging'
        expectedCombined.addComponent(new File(lib,"LOGGING.JAR"));
        // from '*'
        expectedCombined.addComponent(new File(lib,"io.jar"));
        expectedCombined.addComponent(new File(lib,"util.jar"));

        assertEquals("Classpath combined 'server,logging'",expectedCombined,cpCombined);
    }

    @Test
    public void testDynamicSection() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("[All,default,=$(jetty.home)/lib/*]\n");

        String jettyHome = getTestableJettyHome();

        Config options = new Config();
        options.setProperty("jetty.home",jettyHome);
        options.parse(buf);

        Classpath defaultClasspath = options.getClasspath();
        Assert.assertNotNull("Default Classpath should not be null",defaultClasspath);
        Classpath foocp = options.getSectionClasspath("foo");
        Assert.assertNotNull("Foo Classpath should not exist",foocp);

        Classpath allcp = options.getSectionClasspath("All");
        Assert.assertNotNull("Classpath section 'All' should exist",allcp);

        Classpath extcp = options.getSectionClasspath("ext");
        Assert.assertNotNull("Classpath section 'ext' should exist", extcp);

        Assert.assertEquals("Deep Classpath Section",0,foocp.count());

        File lib = new File(getJettyHomeDir(),"lib");
        File ext = new File(lib, "ext");
        Classpath expected = new Classpath();
        expected.addComponent(new File(ext,"custom-impl.jar"));
        assertEquals("Single Classpath Section",expected,extcp);
    }

    @Test
    public void testDeepDynamicSection() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("[All,default,=$(jetty.home)/lib/**]\n");


        String jettyHome = getTestableJettyHome();

        Config options = new Config();
        options.setProperty("jetty.home",jettyHome);
        options.parse(buf);

        Classpath defaultClasspath = options.getClasspath();
        Assert.assertNotNull("Default Classpath should not be null",defaultClasspath);
        Classpath foocp = options.getSectionClasspath("foo");
        Assert.assertNotNull("Foo Classpath should not exist",foocp);

        Classpath allcp = options.getSectionClasspath("All");
        Assert.assertNotNull("Classpath section 'All' should exist",allcp);

        Classpath extcp = options.getSectionClasspath("ext");
        Assert.assertNotNull("Classpath section 'ext' should exist", extcp);

        File lib = new File(getJettyHomeDir(),"lib");

        Classpath expected = new Classpath();
        File foo = new File(lib, "foo");
        File bar = new File(foo, "bar");
        expected.addComponent(new File(bar,"foobar.jar"));
        assertEquals("Deep Classpath Section",expected,foocp);

        File ext = new File(lib, "ext");
        expected = new Classpath();
        expected.addComponent(new File(ext,"custom-impl.jar"));
        assertEquals("Single Classpath Section",expected,extcp);
    }
}
