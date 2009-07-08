// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.start;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import junit.framework.TestCase;

public class ConfigTest extends TestCase
{
    private File jettyHomeDir;
    private File resourcesDir;

    private void assertEquals(String msg, Classpath expected, Classpath actual)
    {
        assertNotNull(msg + " : expected classpath should not be null",expected);
        assertNotNull(msg + " : actual classpath should not be null",actual);
        assertTrue(msg + " : expected should have an entry",expected.count() >= 1);
        assertEquals(msg + " : count",expected.count(),actual.count());

        List<File> actualEntries = Arrays.asList(actual.getElements());
        List<File> expectedEntries = Arrays.asList(expected.getElements());

        for (File expectedEntry : expectedEntries)
        {
            assertTrue(msg + " : should contain <" + expectedEntry + ">",actualEntries.contains(expectedEntry));
        }
    }

    private void assertEquals(String msg, Collection<String> expected, Collection<String> actual)
    {
        assertTrue(msg + " : expected should have an entry",expected.size() >= 1);
        assertEquals(msg + " : size",expected.size(),actual.size());
        for (String expectedVal : expected)
        {
            assertTrue(msg + " : should contain <" + expectedVal + ">",actual.contains(expectedVal));
        }
    }

    private String getJettyEtcFile(String name)
    {
        File etc = new File(getTestableJettyHome(),"etc");
        return new File(etc,name).getAbsolutePath();
    }

    private File getJettyHomeDir()
    {
        if (jettyHomeDir == null)
        {
            jettyHomeDir = new File(getTestResourcesDir(),"jetty.home");
        }

        return jettyHomeDir;
    }

    private String getTestableJettyHome()
    {
        return getJettyHomeDir().getAbsolutePath();
    }

    private File getTestResourcesDir()
    {
        if (resourcesDir == null)
        {
            File src = new File(System.getProperty("user.dir"),"src");
            File test = new File(src,"test");
            resourcesDir = new File(test,"resources");
        }

        return resourcesDir;
    }

    /**
     * Test for SUBJECT "/=" for assign canonical path
     */
    public void testSubjectAssignCanonicalPath() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("test.resources.dir/=src/test/resources\n");

        Config cfg = new Config();
        cfg.parse(buf);

        assertEquals(getTestResourcesDir().getCanonicalPath(),cfg.getProperty("test.resources.dir"));
    }

    /**
     * Test for SUBJECT "~=" for assigning Start Properties
     */
    public void testSubjectAssignStartProperty() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("test.jetty.start.text~=foo\n");
        buf.append("test.jetty.start.quote~=Eatagramovabits\n");

        Config options = new Config();
        options.parse(buf);

        assertEquals("foo",options.getProperty("test.jetty.start.text"));
        assertEquals("Eatagramovabits",options.getProperty("test.jetty.start.quote"));
    }

    /**
     * Test for SUBJECT "=" for assigning System Properties
     */
    public void testSubjectAssignSystemProperty() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("test.jetty.start.text=foo\n");
        buf.append("test.jetty.start.quote=Eatagramovabits\n");

        Config options = new Config();
        options.parse(buf);

        assertEquals("foo",System.getProperty("test.jetty.start.text"));
        assertEquals("Eatagramovabits",System.getProperty("test.jetty.start.quote"));
    }

    /**
     * Test for SUBJECT ending with "/**", all jar and zip components in dir (deep, recursive)
     */
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

        assertEquals("Components (Deep)",expected,actual);
    }

    /**
     * Test for SUBJECT ending with "/*", all jar and zip components in dir (shallow, no recursion)
     */
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

    /**
     * Test for SUBJECT ending with ".class", a Main Class
     */
    public void testSubjectMainClass() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("org.eclipse.jetty.xml.XmlConfiguration.class");

        Config options = new Config();
        options.parse(buf);

        assertEquals("org.eclipse.jetty.xml.XmlConfiguration",options.getMainClassname());
    }

    /**
     * Test for SUBJECT ending with ".class", a Main Class
     */
    public void testSubjectMainClassConditionalPropertySet() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("org.eclipse.jetty.xml.XmlConfiguration.class\n");
        buf.append("${start.class}.class    property start.class");

        Config options = new Config();
        options.setProperty("start.class","net.company.server.Start");
        options.parse(buf);

        assertEquals("net.company.server.Start",options.getMainClassname());
    }

    /**
     * Test for SUBJECT ending with ".class", a Main Class
     */
    public void testSubjectMainClassConditionalPropertyUnset() throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append("org.eclipse.jetty.xml.XmlConfiguration.class\n");
        buf.append("${start.class}.class    property start.class");

        Config options = new Config();
        // The "start.class" property is unset.
        options.parse(buf);

        assertEquals("org.eclipse.jetty.xml.XmlConfiguration",options.getMainClassname());
    }

    /**
     * Test for SUBJECT ending with "/", a simple Classpath Entry
     */
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

    /**
     * Test for SUBJECT ending with "/", a simple Classpath Entry
     */
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

    /**
     * Test for SUBJECT ending with "/", a simple Classpath Entry
     */
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

    /**
     * Test for SUBJECT ending with ".xml", an XML Configuration File
     */
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
        assertEquals("XmlConfig.size",1,actual.size());
        assertEquals(expected,actual.get(0));
    }

    /**
     * Test for SUBJECT ending with ".xml", an XML Configuration File
     */
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
        assertEquals("XmlConfig.size",1,actual.size());
        assertEquals(expected,actual.get(0));
    }

    /**
     * Test for SUBJECT ending with ".xml", an XML Configuration File.
     */
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

    /**
     * Test Section Handling
     */
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
        assertNotNull("Default Classpath should not be null",defaultClasspath);
        Classpath foocp = options.getSectionClasspath("Foo");
        assertNull("Foo Classpath should not exist",foocp);

        Classpath allcp = options.getSectionClasspath("All");
        assertNotNull("Classpath section 'All' should exist",allcp);

        File lib = new File(getJettyHomeDir(),"lib");

        Classpath expected = new Classpath();
        expected.addComponent(new File(lib,"core-test.jar"));
        expected.addComponent(new File(lib,"util.jar"));

        assertEquals("Single Classpath Section",expected,allcp);
    }
    
    /**
     * Test Section Handling
     */
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
        assertNotNull("Default Classpath should not be null",defaultClasspath);
        Classpath foocp = options.getSectionClasspath("Foo");
        assertNull("Foo Classpath should not exist",foocp);

        Classpath allcp = options.getSectionClasspath("All");
        assertNotNull("Classpath section 'All' should exist",allcp);

        File lib = new File(getJettyHomeDir(),"lib");

        Classpath expected = new Classpath();
        expected.addComponent(new File(lib,"core.jar"));
        expected.addComponent(new File(lib,"util.jar"));

        assertEquals("Single Classpath Section",expected,allcp);
    }

    /**
     * Test Section Handling
     */
    public void testSectionClasspathComplex() throws IOException
    {
        StringBuffer buf = new StringBuffer();
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

        String jettyHome = getTestableJettyHome();

        Config cfg = new Config();
        cfg.setProperty("jetty.home",jettyHome);
        cfg.parse(buf);

        Classpath defaultClasspath = cfg.getClasspath();
        assertNotNull("Default Classpath should not be null",defaultClasspath);

        Classpath foocp = cfg.getSectionClasspath("Foo");
        assertNull("Foo Classpath should not exist",foocp);

        Set<String> sections = cfg.getSectionIds();

        Set<String> expected = new HashSet<String>();
        expected.add(Config.DEFAULT_SECTION);
        expected.add("*");
        expected.add("All");
        expected.add("server");
        expected.add("default");
        expected.add("xml");

        assertEquals("Multiple Section IDs",expected,sections);

        Classpath allcp = cfg.getSectionClasspath("All");
        assertNotNull("Classpath section 'All' should exist",allcp);

        File lib = new File(getJettyHomeDir(),"lib");

        Classpath allexpected = new Classpath();
        allexpected.addComponent(new File(lib,"core.jar"));
        allexpected.addComponent(new File(lib,"server.jar"));
        allexpected.addComponent(new File(lib,"http.jar"));
        allexpected.addComponent(new File(lib,"xml.jar"));

        assertEquals("Classpath 'All' Section",allexpected,allcp);
    }
}
