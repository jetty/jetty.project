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

package org.eclipse.jetty.xml;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

public class XmlConfigurationTest
{
    protected String _configure="org/eclipse/jetty/xml/configure.xml";

    private static final String STRING_ARRAY_XML = "<Array type=\"String\"><Item type=\"String\">String1</Item><Item type=\"String\">String2</Item></Array>";
    private static final String INT_ARRAY_XML = "<Array type=\"int\"><Item type=\"int\">1</Item><Item type=\"int\">2</Item></Array>";

    @Test
    public void testMortBay() throws Exception
    {
        URL url = XmlConfigurationTest.class.getClassLoader().getResource("org/eclipse/jetty/xml/mortbay.xml");
        XmlConfiguration configuration = new XmlConfiguration(url);
        configuration.configure();
    }

    @Test
    public void testPassedObject() throws Exception
    {
        TestConfiguration.VALUE=77;
        Map<String,String> properties = new HashMap<>();
        properties.put("whatever", "xxx");

        URL url = XmlConfigurationTest.class.getClassLoader().getResource(_configure);
        XmlConfiguration configuration = new XmlConfiguration(url);
        TestConfiguration tc = new TestConfiguration("tc");
        configuration.getProperties().putAll(properties);
        configuration.configure(tc);

        assertEquals("Set String","SetValue",tc.testObject);
        assertEquals("Set Type",2,tc.testInt);

        assertEquals(18080, tc.propValue);

        assertEquals("Put","PutValue",tc.get("Test"));
        assertEquals("Put dft","2",tc.get("TestDft"));
        assertEquals("Put type",2,tc.get("TestInt"));

        assertEquals("Trim","PutValue",tc.get("Trim"));
        assertEquals("Null",null,tc.get("Null"));
        assertEquals("NullTrim",null,tc.get("NullTrim"));

        assertEquals("ObjectTrim",1.2345,tc.get("ObjectTrim"));
        assertEquals("Objects","-1String",tc.get("Objects"));
        assertEquals( "ObjectsTrim", "-1String",tc.get("ObjectsTrim"));
        assertEquals( "String", "\n    PutValue\n  ",tc.get("String"));
        assertEquals( "NullString", "",tc.get("NullString"));
        assertEquals( "WhiteSpace", "\n  ",tc.get("WhiteSpace"));
        assertEquals( "ObjectString", "\n    1.2345\n  ",tc.get("ObjectString"));
        assertEquals( "ObjectsString", "-1String",tc.get("ObjectsString"));
        assertEquals( "ObjectsWhiteString", "-1\n  String",tc.get("ObjectsWhiteString"));

        assertEquals( "SystemProperty", System.getProperty("user.dir")+"/stuff",tc.get("SystemProperty"));
        assertEquals( "Env", System.getenv("HOME"),tc.get("Env"));

        assertEquals( "Property", "xxx", tc.get("Property"));


        assertEquals( "Called", "Yes",tc.get("Called"));

        assertTrue(TestConfiguration.called);

        assertEquals("oa[0]","Blah",tc.oa[0]);
        assertEquals("oa[1]","1.2.3.4:5678",tc.oa[1]);
        assertEquals("oa[2]",1.2345,tc.oa[2]);
        assertEquals("oa[3]",null,tc.oa[3]);

        assertEquals("ia[0]",1,tc.ia[0]);
        assertEquals("ia[1]",2,tc.ia[1]);
        assertEquals("ia[2]",3,tc.ia[2]);
        assertEquals("ia[3]",0,tc.ia[3]);

        TestConfiguration tc2=tc.nested;
        assertTrue(tc2!=null);
        assertEquals( "Called(bool)",true,tc2.get("Arg"));

        assertEquals("nested config",null,tc.get("Arg"));
        assertEquals("nested config",true,tc2.get("Arg"));

        assertEquals("nested config","Call1",tc2.testObject);
        assertEquals("nested config",4,tc2.testInt);
        assertEquals( "nested call", "http://www.eclipse.com/",tc2.url.toString());

        assertEquals("static to field",tc.testField1,77);
        assertEquals("field to field",tc.testField2,2);
        assertEquals("literal to static",TestConfiguration.VALUE,42);
    }

    @Test
    public void testNewObject() throws Exception
    {
        TestConfiguration.VALUE=71;
        Map<String,String> properties = new HashMap<>();
        properties.put("whatever", "xxx");

        URL url = XmlConfigurationTest.class.getClassLoader().getResource(_configure);
        final AtomicInteger count = new AtomicInteger(0);
        XmlConfiguration configuration = new XmlConfiguration(url)
        {
            @Override
            public void initializeDefaults(Object object)
            {
                if (object instanceof TestConfiguration)
                {
                    count.incrementAndGet();
                    ((TestConfiguration)object).setNested(null);
                    ((TestConfiguration)object).setTestString("NEW DEFAULT");
                }
            }
        };
        configuration.getProperties().putAll(properties);
        TestConfiguration tc = (TestConfiguration)configuration.configure();

        assertEquals(3,count.get());
        
        assertEquals("NEW DEFAULT",tc.getTestString());
        assertEquals("nested",tc.getNested().getTestString());
        assertEquals("NEW DEFAULT",tc.getNested().getNested().getTestString());
        
        assertEquals("Set String","SetValue",tc.testObject);
        assertEquals("Set Type",2,tc.testInt);

        assertEquals(18080, tc.propValue);

        assertEquals("Put","PutValue",tc.get("Test"));
        assertEquals("Put dft","2",tc.get("TestDft"));
        assertEquals("Put type",2,tc.get("TestInt"));

        assertEquals("Trim","PutValue",tc.get("Trim"));
        assertEquals("Null",null,tc.get("Null"));
        assertEquals("NullTrim",null,tc.get("NullTrim"));

        assertEquals("ObjectTrim",1.2345,tc.get("ObjectTrim"));
        assertEquals("Objects","-1String",tc.get("Objects"));
        assertEquals( "ObjectsTrim", "-1String",tc.get("ObjectsTrim"));
        assertEquals( "String", "\n    PutValue\n  ",tc.get("String"));
        assertEquals( "NullString", "",tc.get("NullString"));
        assertEquals( "WhiteSpace", "\n  ",tc.get("WhiteSpace"));
        assertEquals( "ObjectString", "\n    1.2345\n  ",tc.get("ObjectString"));
        assertEquals( "ObjectsString", "-1String",tc.get("ObjectsString"));
        assertEquals( "ObjectsWhiteString", "-1\n  String",tc.get("ObjectsWhiteString"));

        assertEquals( "SystemProperty", System.getProperty("user.dir")+"/stuff",tc.get("SystemProperty"));
        assertEquals( "Property", "xxx", tc.get("Property"));


        assertEquals( "Called", "Yes",tc.get("Called"));

        assertTrue(TestConfiguration.called);

        assertEquals("oa[0]","Blah",tc.oa[0]);
        assertEquals("oa[1]","1.2.3.4:5678",tc.oa[1]);
        assertEquals("oa[2]",1.2345,tc.oa[2]);
        assertEquals("oa[3]",null,tc.oa[3]);

        assertEquals("ia[0]",1,tc.ia[0]);
        assertEquals("ia[1]",2,tc.ia[1]);
        assertEquals("ia[2]",3,tc.ia[2]);
        assertEquals("ia[3]",0,tc.ia[3]);

        TestConfiguration tc2=tc.nested;
        assertTrue(tc2!=null);
        assertEquals( "Called(bool)",true,tc2.get("Arg"));

        assertEquals("nested config",null,tc.get("Arg"));
        assertEquals("nested config",true,tc2.get("Arg"));

        assertEquals("nested config","Call1",tc2.testObject);
        assertEquals("nested config",4,tc2.testInt);
        assertEquals( "nested call", "http://www.eclipse.com/",tc2.url.toString());

        assertEquals("static to field",71,tc.testField1);
        assertEquals("field to field",2,tc.testField2);
        assertEquals("literal to static",42,TestConfiguration.VALUE);
    }


    @Test
    public void testStringConfiguration() throws Exception
    {
        XmlConfiguration configuration =
            new XmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\"><Set name=\"Test\">SetValue</Set><Set name=\"Test\" type=\"int\">2</Set></Configure>");
        TestConfiguration tc = new TestConfiguration();
        configuration.configure(tc);
        assertEquals("Set String 3","SetValue",tc.testObject);
        assertEquals("Set Type 3",2,tc.testInt);
    }

    @Test
    public void testListConstructorArg() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\">"
                + "<Set name=\"constructorArgTestClass\"><New class=\"org.eclipse.jetty.xml.ConstructorArgTestClass\"><Arg type=\"List\">"
                + STRING_ARRAY_XML + "</Arg></New></Set></Configure>");
        TestConfiguration tc = new TestConfiguration();
        assertThat("tc.getList() returns null as it's not configured yet",tc.getList(),is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getList() returns not null",tc.getList(),not(nullValue()));
        assertThat("tc.getList() has two entries as specified in the xml",tc.getList().size(),is(2));
    }

    @Test
    public void testTwoArgumentListConstructorArg() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\">"
                + "<Set name=\"constructorArgTestClass\"><New class=\"org.eclipse.jetty.xml.ConstructorArgTestClass\">"
                + "<Arg type=\"List\">" + STRING_ARRAY_XML + "</Arg>"
                + "<Arg type=\"List\">" + STRING_ARRAY_XML + "</Arg>"
                + "</New></Set></Configure>");
        TestConfiguration tc = new TestConfiguration();
        assertThat("tc.getList() returns null as it's not configured yet",tc.getList(),is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getList() returns not null",tc.getList(),not(nullValue()));
        assertThat("tc.getList() has two entries as specified in the xml",tc.getList().size(),is(2));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testListNotContainingArray() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\">"
                + "<New class=\"org.eclipse.jetty.xml.ConstructorArgTestClass\"><Arg type=\"List\">Some String</Arg></New></Configure>");
        TestConfiguration tc = new TestConfiguration();
        xmlConfiguration.configure(tc);
    }

    @Test
    public void testSetConstructorArg() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\">"
                + "<Set name=\"constructorArgTestClass\"><New class=\"org.eclipse.jetty.xml.ConstructorArgTestClass\"><Arg type=\"Set\">"
                + STRING_ARRAY_XML + "</Arg></New></Set></Configure>");
        TestConfiguration tc = new TestConfiguration();
        assertThat("tc.getList() returns null as it's not configured yet",tc.getSet(),is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getList() returns not null",tc.getSet(),not(nullValue()));
        assertThat("tc.getList() has two entries as specified in the xml",tc.getSet().size(),is(2));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetNotContainingArray() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\">"
                + "<New class=\"org.eclipse.jetty.xml.ConstructorArgTestClass\"><Arg type=\"Set\">Some String</Arg></New></Configure>");
        TestConfiguration tc = new TestConfiguration();
        xmlConfiguration.configure(tc);
    }

    @Test
    public void testListSetterWithStringArray() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\"><Set name=\"List\">"
                + STRING_ARRAY_XML + "</Set></Configure>");
        TestConfiguration tc = new TestConfiguration();
        assertThat("tc.getList() returns null as it's not configured yet",tc.getList(),is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getList() has two entries as specified in the xml",tc.getList().size(),is(2));
    }

    @Test
    public void testListSetterWithPrimitiveArray() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\"><Set name=\"List\">"
                + INT_ARRAY_XML + "</Set></Configure>");
        TestConfiguration tc = new TestConfiguration();
        assertThat("tc.getList() returns null as it's not configured yet",tc.getList(),is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getList() has two entries as specified in the xml",tc.getList().size(),is(2));
    }

    @Test(expected=NoSuchMethodException.class)
    public void testNotSupportedLinkedListSetter() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\"><Set name=\"LinkedList\">"
                + INT_ARRAY_XML + "</Set></Configure>");
        TestConfiguration tc = new TestConfiguration();
        assertThat("tc.getSet() returns null as it's not configured yet",tc.getList(),is(nullValue()));
        xmlConfiguration.configure(tc);
    }

    @Test
    public void testArrayListSetter() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\"><Set name=\"ArrayList\">"
                + INT_ARRAY_XML + "</Set></Configure>");
        TestConfiguration tc = new TestConfiguration();
        assertThat("tc.getSet() returns null as it's not configured yet",tc.getList(),is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getSet() has two entries as specified in the xml",tc.getList().size(),is(2));
    }

    @Test
    public void testSetSetter() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\"><Set name=\"Set\">"
                + STRING_ARRAY_XML + "</Set></Configure>");
        TestConfiguration tc = new TestConfiguration();
        assertThat("tc.getSet() returns null as it's not configured yet",tc.getSet(),is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getSet() has two entries as specified in the xml",tc.getSet().size(),is(2));
    }

    @Test
    public void testSetSetterWithPrimitiveArray() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\"><Set name=\"Set\">"
                + INT_ARRAY_XML + "</Set></Configure>");
        TestConfiguration tc = new TestConfiguration();
        assertThat("tc.getSet() returns null as it's not configured yet",tc.getSet(),is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getSet() has two entries as specified in the xml",tc.getSet().size(),is(2));
    }

    @Test
    public void testMap() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("" +
                "<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\">" +
                "    <Set name=\"map\">" +
                "        <Map>" +
                "            <Entry>" +
                "                <Item>key1</Item>" +
                "                <Item>value1</Item>" +
                "            </Entry>" +
                "            <Entry>" +
                "                <Item>key2</Item>" +
                "                <Item>value2</Item>" +
                "            </Entry>" +
                "        </Map>" +
                "    </Set>" +
                "</Configure>");
        TestConfiguration tc = new TestConfiguration();
        Assert.assertNull("tc.map is null as it's not configured yet", tc.map);
        xmlConfiguration.configure(tc);
        Assert.assertEquals("tc.map is has two entries as specified in the XML", 2, tc.map.size());
    }

    @Test
    public void testConstructorNamedInjection() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("" +
                "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg>arg1</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg>arg3</Arg>  " +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        Assert.assertEquals("first parameter not wired correctly","arg1", atc.getFirst());
        Assert.assertEquals("second parameter not wired correctly","arg2", atc.getSecond());
        Assert.assertEquals("third parameter not wired correctly","arg3", atc.getThird());
    }

    @Test
    public void testConstructorNamedInjectionOrdered() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("" +
                "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg name=\"second\">arg2</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        Assert.assertEquals("first parameter not wired correctly","arg1", atc.getFirst());
        Assert.assertEquals("second parameter not wired correctly","arg2", atc.getSecond());
        Assert.assertEquals("third parameter not wired correctly","arg3", atc.getThird());
    }

    @Test
    public void testConstructorNamedInjectionUnOrdered() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("" +
                "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "  <Arg name=\"second\">arg2</Arg>  " +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        Assert.assertEquals("first parameter not wired correctly","arg1", atc.getFirst());
        Assert.assertEquals("second parameter not wired correctly","arg2", atc.getSecond());
        Assert.assertEquals("third parameter not wired correctly","arg3", atc.getThird());
    }

    @Test
    public void testConstructorNamedInjectionOrderedMixed() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("" +
                "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        Assert.assertEquals("first parameter not wired correctly","arg1", atc.getFirst());
        Assert.assertEquals("second parameter not wired correctly","arg2", atc.getSecond());
        Assert.assertEquals("third parameter not wired correctly","arg3", atc.getThird());
    }

    @Test
    public void testConstructorNamedInjectionUnorderedMixed() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("" +
                "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        Assert.assertEquals("first parameter not wired correctly","arg1", atc.getFirst());
        Assert.assertEquals("second parameter not wired correctly","arg2", atc.getSecond());
        Assert.assertEquals("third parameter not wired correctly","arg3", atc.getThird());
    }

    @Test
    public void testNestedConstructorNamedInjection() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("" +
                "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg>arg1</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg>arg3</Arg>  " +
                "  <Set name=\"nested\">  " +
                "    <New class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "      <Arg>arg1</Arg>  " +
                "      <Arg>arg2</Arg>  " +
                "      <Arg>arg3</Arg>  " +
                "    </New>" +
                "  </Set>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        Assert.assertEquals("first parameter not wired correctly","arg1", atc.getFirst());
        Assert.assertEquals("second parameter not wired correctly","arg2", atc.getSecond());
        Assert.assertEquals("third parameter not wired correctly","arg3", atc.getThird());
        Assert.assertEquals("nested first parameter not wired correctly","arg1", atc.getNested().getFirst());
        Assert.assertEquals("nested second parameter not wired correctly","arg2", atc.getNested().getSecond());
        Assert.assertEquals("nested third parameter not wired correctly","arg3", atc.getNested().getThird());

    }

    @Test
    public void testNestedConstructorNamedInjectionOrdered() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("" +
                "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg name=\"second\">arg2</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "  <Set name=\"nested\">  " +
                "    <New class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "      <Arg name=\"first\">arg1</Arg>  " +
                "      <Arg name=\"second\">arg2</Arg>  " +
                "      <Arg name=\"third\">arg3</Arg>  " +
                "    </New>" +
                "  </Set>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        Assert.assertEquals("first parameter not wired correctly","arg1", atc.getFirst());
        Assert.assertEquals("second parameter not wired correctly","arg2", atc.getSecond());
        Assert.assertEquals("third parameter not wired correctly","arg3", atc.getThird());
        Assert.assertEquals("nested first parameter not wired correctly","arg1", atc.getNested().getFirst());
        Assert.assertEquals("nested second parameter not wired correctly","arg2", atc.getNested().getSecond());
        Assert.assertEquals("nested third parameter not wired correctly","arg3", atc.getNested().getThird());
    }

    @Test
    public void testNestedConstructorNamedInjectionUnOrdered() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("" +
                "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "  <Arg name=\"second\">arg2</Arg>  " +
                "  <Set name=\"nested\">  " +
                "    <New class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "      <Arg name=\"first\">arg1</Arg>  " +
                "      <Arg name=\"third\">arg3</Arg>  " +
                "      <Arg name=\"second\">arg2</Arg>  " +
                "    </New>" +
                "  </Set>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        Assert.assertEquals("first parameter not wired correctly","arg1", atc.getFirst());
        Assert.assertEquals("second parameter not wired correctly","arg2", atc.getSecond());
        Assert.assertEquals("third parameter not wired correctly","arg3", atc.getThird());
        Assert.assertEquals("nested first parameter not wired correctly","arg1", atc.getNested().getFirst());
        Assert.assertEquals("nested second parameter not wired correctly","arg2", atc.getNested().getSecond());
        Assert.assertEquals("nested third parameter not wired correctly","arg3", atc.getNested().getThird());
    }

    @Test
    public void testNestedConstructorNamedInjectionOrderedMixed() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("" +
                "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "  <Set name=\"nested\">  " +
                "    <New class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "      <Arg name=\"first\">arg1</Arg>  " +
                "      <Arg>arg2</Arg>  " +
                "      <Arg name=\"third\">arg3</Arg>  " +
                "    </New>" +
                "  </Set>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        Assert.assertEquals("first parameter not wired correctly","arg1", atc.getFirst());
        Assert.assertEquals("second parameter not wired correctly","arg2", atc.getSecond());
        Assert.assertEquals("third parameter not wired correctly","arg3", atc.getThird());
        Assert.assertEquals("nested first parameter not wired correctly","arg1", atc.getNested().getFirst());
        Assert.assertEquals("nested second parameter not wired correctly","arg2", atc.getNested().getSecond());
        Assert.assertEquals("nested third parameter not wired correctly","arg3", atc.getNested().getThird());
    }
    
    @Test
    public void testArgumentsGetIgnoredMissingDTD() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration(new ByteArrayInputStream(("" +
                "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg>arg1</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg>arg3</Arg>  " +
                "  <Set name=\"nested\">  " +
                "    <New class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">\n" + 
                "      <Arg>arg1</Arg>\n" + 
                "      <Arg>arg2</Arg>\n" + 
                "      <Arg>arg3</Arg>\n" + 
                "    </New>" +
                "  </Set>" +
                "</Configure>").getBytes(StandardCharsets.ISO_8859_1)));
//        XmlConfiguration xmlConfiguration = new XmlConfiguration(url);

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        Assert.assertEquals("first parameter not wired correctly","arg1", atc.getFirst());
        Assert.assertEquals("second parameter not wired correctly","arg2", atc.getSecond());
        Assert.assertEquals("third parameter not wired correctly","arg3", atc.getThird());
        Assert.assertEquals("nested first parameter not wired correctly","arg1", atc.getNested().getFirst());
        Assert.assertEquals("nested second parameter not wired correctly","arg2", atc.getNested().getSecond());
        Assert.assertEquals("nested third parameter not wired correctly","arg3", atc.getNested().getThird());
    }

    @Test
    public void testSetGetIgnoredMissingDTD() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration(new ByteArrayInputStream(("" +
                "<Configure class=\"org.eclipse.jetty.xml.DefaultTestConfiguration\">" +
                "  <Set name=\"first\">arg1</Set>  " +
                "  <Set name=\"second\">arg2</Set>  " +
                "  <Set name=\"third\">arg3</Set>  " +
                "  <Set name=\"nested\">  " +
                "    <New class=\"org.eclipse.jetty.xml.DefaultTestConfiguration\">\n" + 
                "      <Set name=\"first\">arg1</Set>  " +
                "      <Set name=\"second\">arg2</Set>  " +
                "      <Set name=\"third\">arg3</Set>  " +
                "    </New>" +
                "  </Set>" +
                "</Configure>").getBytes(StandardCharsets.UTF_8)));
//        XmlConfiguration xmlConfiguration = new XmlConfiguration(url);

        DefaultTestConfiguration atc = (DefaultTestConfiguration)xmlConfiguration.configure();

        Assert.assertEquals("first parameter not wired correctly","arg1", atc.getFirst());
        Assert.assertEquals("second parameter not wired correctly","arg2", atc.getSecond());
        Assert.assertEquals("third parameter not wired correctly","arg3", atc.getThird());
        Assert.assertEquals("nested first parameter not wired correctly","arg1", atc.getNested().getFirst());
        Assert.assertEquals("nested second parameter not wired correctly","arg2", atc.getNested().getSecond());
        Assert.assertEquals("nested third parameter not wired correctly","arg3", atc.getNested().getThird());
    }

    @Test
    public void testNestedConstructorNamedInjectionUnorderedMixed() throws Exception
    {
        XmlConfiguration xmlConfiguration = new XmlConfiguration("" +
                "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Set name=\"nested\">  " +
                "    <New class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "      <Arg name=\"third\">arg3</Arg>  " +
                "      <Arg>arg2</Arg>  " +
                "      <Arg name=\"first\">arg1</Arg>  " +
                "    </New>" +
                "  </Set>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        Assert.assertEquals("first parameter not wired correctly","arg1", atc.getFirst());
        Assert.assertEquals("second parameter not wired correctly","arg2", atc.getSecond());
        Assert.assertEquals("third parameter not wired correctly","arg3", atc.getThird());
        Assert.assertEquals("nested first parameter not wired correctly","arg1", atc.getNested().getFirst());
        Assert.assertEquals("nested second parameter not wired correctly","arg2", atc.getNested().getSecond());
        Assert.assertEquals("nested third parameter not wired correctly","arg3", atc.getNested().getThird());
    }
}
