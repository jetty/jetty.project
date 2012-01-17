// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.xml;

import static junit.framework.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class XmlConfigurationTest
{
    protected String _configure="org/eclipse/jetty/xml/configure.xml";

    @Test
    public void testMortBay() throws Exception
    {
        URL url = XmlConfigurationTest.class.getClassLoader().getResource("org/eclipse/jetty/xml/mortbay.xml");
        XmlConfiguration configuration = new XmlConfiguration(url);
        Object o=configuration.configure();
    }
    
    @Test
    public void testPassedObject() throws Exception
    {
        TestConfiguration.VALUE=77;
        Map<String,String> properties = new HashMap<String,String>();
        properties.put("whatever", "xxx");

        URL url = XmlConfigurationTest.class.getClassLoader().getResource(_configure);
        XmlConfiguration configuration = new XmlConfiguration(url);
        TestConfiguration tc = new TestConfiguration();
        configuration.getProperties().putAll(properties);
        configuration.configure(tc);

        assertEquals("Set String","SetValue",tc.testObject);
        assertEquals("Set Type",2,tc.testInt);
        
        assertEquals(18080, tc.propValue);

        assertEquals("Put","PutValue",tc.get("Test"));
        assertEquals("Put dft","2",tc.get("TestDft"));
        assertEquals("Put type",new Integer(2),tc.get("TestInt"));

        assertEquals("Trim","PutValue",tc.get("Trim"));
        assertEquals("Null",null,tc.get("Null"));
        assertEquals("NullTrim",null,tc.get("NullTrim"));

        assertEquals("ObjectTrim",new Double(1.2345),tc.get("ObjectTrim"));
        assertEquals("Objects","-1String",tc.get("Objects"));
        assertEquals( "ObjectsTrim", "-1String",tc.get("ObjectsTrim"));
        assertEquals( "String", "\n    PutValue\n  ",tc.get("String"));
        assertEquals( "NullString", "",tc.get("NullString"));
        assertEquals( "WhateSpace", "\n  ",tc.get("WhiteSpace"));
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
        assertEquals("oa[2]",new Double(1.2345),tc.oa[2]);
        assertEquals("oa[3]",null,tc.oa[3]);

        assertEquals("ia[0]",1,tc.ia[0]);
        assertEquals("ia[1]",2,tc.ia[1]);
        assertEquals("ia[2]",3,tc.ia[2]);
        assertEquals("ia[3]",0,tc.ia[3]);

        TestConfiguration tc2=tc.nested;
        assertTrue(tc2!=null);
        assertEquals( "Called(bool)", new Boolean(true),tc2.get("Arg"));

        assertEquals("nested config",null,tc.get("Arg"));
        assertEquals("nested config",new Boolean(true),tc2.get("Arg"));

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
        Map<String,String> properties = new HashMap<String,String>();
        properties.put("whatever", "xxx");

        URL url = XmlConfigurationTest.class.getClassLoader().getResource(_configure);
        XmlConfiguration configuration = new XmlConfiguration(url);
        configuration.getProperties().putAll(properties);
        TestConfiguration tc = (TestConfiguration)configuration.configure();

        assertEquals("Set String","SetValue",tc.testObject);
        assertEquals("Set Type",2,tc.testInt);
        
        assertEquals(18080, tc.propValue);

        assertEquals("Put","PutValue",tc.get("Test"));
        assertEquals("Put dft","2",tc.get("TestDft"));
        assertEquals("Put type",new Integer(2),tc.get("TestInt"));

        assertEquals("Trim","PutValue",tc.get("Trim"));
        assertEquals("Null",null,tc.get("Null"));
        assertEquals("NullTrim",null,tc.get("NullTrim"));

        assertEquals("ObjectTrim",new Double(1.2345),tc.get("ObjectTrim"));
        assertEquals("Objects","-1String",tc.get("Objects"));
        assertEquals( "ObjectsTrim", "-1String",tc.get("ObjectsTrim"));
        assertEquals( "String", "\n    PutValue\n  ",tc.get("String"));
        assertEquals( "NullString", "",tc.get("NullString"));
        assertEquals( "WhateSpace", "\n  ",tc.get("WhiteSpace"));
        assertEquals( "ObjectString", "\n    1.2345\n  ",tc.get("ObjectString"));
        assertEquals( "ObjectsString", "-1String",tc.get("ObjectsString"));
        assertEquals( "ObjectsWhiteString", "-1\n  String",tc.get("ObjectsWhiteString"));

        assertEquals( "SystemProperty", System.getProperty("user.dir")+"/stuff",tc.get("SystemProperty"));
        assertEquals( "Property", "xxx", tc.get("Property"));


        assertEquals( "Called", "Yes",tc.get("Called"));

        assertTrue(TestConfiguration.called);

        assertEquals("oa[0]","Blah",tc.oa[0]);
        assertEquals("oa[1]","1.2.3.4:5678",tc.oa[1]);
        assertEquals("oa[2]",new Double(1.2345),tc.oa[2]);
        assertEquals("oa[3]",null,tc.oa[3]);

        assertEquals("ia[0]",1,tc.ia[0]);
        assertEquals("ia[1]",2,tc.ia[1]);
        assertEquals("ia[2]",3,tc.ia[2]);
        assertEquals("ia[3]",0,tc.ia[3]);

        TestConfiguration tc2=tc.nested;
        assertTrue(tc2!=null);
        assertEquals( "Called(bool)", new Boolean(true),tc2.get("Arg"));

        assertEquals("nested config",null,tc.get("Arg"));
        assertEquals("nested config",new Boolean(true),tc2.get("Arg"));

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
}
