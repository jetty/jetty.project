//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StdErrLog;
import org.eclipse.jetty.util.resource.PathResource;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.xml.sax.SAXException;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class XmlConfigurationTest
{
    public WorkDir workDir;

    public static class ScenarioProvider implements ArgumentsProvider
    {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context)
        {
            return Stream.of(
                "org/eclipse/jetty/xml/configureWithAttr.xml",
                "org/eclipse/jetty/xml/configureWithElements.xml"
            ).map(Arguments::of);
        }
    }

    private static final String STRING_ARRAY_XML = "<Array type=\"String\"><Item type=\"String\">String1</Item><Item type=\"String\">String2</Item></Array>";
    private static final String INT_ARRAY_XML = "<Array type=\"int\"><Item type=\"int\">1</Item><Item type=\"int\">2</Item></Array>";

    @Test
    public void testMortBay() throws Exception
    {
        URL url = XmlConfigurationTest.class.getClassLoader().getResource("org/eclipse/jetty/xml/mortbay.xml");
        XmlConfiguration configuration = new XmlConfiguration(url);
        configuration.configure();
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testPassedObject(String configure) throws Exception
    {
        Map<String, String> properties = new HashMap<>();
        properties.put("whatever", "xxx");
        TestConfiguration.VALUE = 77;
        URL url = XmlConfigurationTest.class.getClassLoader().getResource(configure);
        XmlConfiguration configuration = new XmlConfiguration(url);
        TestConfiguration tc = new TestConfiguration("tc");
        configuration.getProperties().putAll(properties);
        configuration.configure(tc);

        assertEquals("SetValue", tc.testObject, "Set String");
        assertEquals(2, tc.testInt, "Set Type");

        assertEquals(18080, tc.propValue);

        assertEquals("PutValue", tc.get("Test"), "Put");
        assertEquals("2", tc.get("TestDft"), "Put dft");
        assertEquals(2, tc.get("TestInt"), "Put type");

        assertEquals("PutValue", tc.get("Trim"), "Trim");
        assertNull(tc.get("Null"), "Null");
        assertNull(tc.get("NullTrim"), "NullTrim");

        assertEquals(1.2345, tc.get("ObjectTrim"), "ObjectTrim");
        assertEquals("-1String", tc.get("Objects"), "Objects");
        assertEquals("-1String", tc.get("ObjectsTrim"), "ObjectsTrim");
        assertEquals("\n    PutValue\n  ", tc.get("String"), "String");
        assertEquals("", tc.get("NullString"), "NullString");
        assertEquals("\n  ", tc.get("WhiteSpace"), "WhiteSpace");
        assertEquals("\n    1.2345\n  ", tc.get("ObjectString"), "ObjectString");
        assertEquals("-1String", tc.get("ObjectsString"), "ObjectsString");
        assertEquals("-1\n  String", tc.get("ObjectsWhiteString"), "ObjectsWhiteString");

        assertEquals(System.getProperty("user.dir") + "/stuff", tc.get("SystemProperty"), "SystemProperty");
        assertEquals(System.getenv("HOME"), tc.get("Env"), "Env");

        assertEquals("xxx", tc.get("Property"), "Property");

        assertEquals("Yes", tc.get("Called"), "Called");

        assertTrue(TestConfiguration.called);

        assertEquals("Blah", tc.oa[0], "oa[0]");
        assertEquals("1.2.3.4:5678", tc.oa[1], "oa[1]");
        assertEquals(1.2345, tc.oa[2], "oa[2]");
        assertNull(tc.oa[3], "oa[3]");

        assertEquals(1, tc.ia[0], "ia[0]");
        assertEquals(2, tc.ia[1], "ia[1]");
        assertEquals(3, tc.ia[2], "ia[2]");
        assertEquals(0, tc.ia[3], "ia[3]");

        TestConfiguration tc2 = tc.nested;
        assertNotNull(tc2);
        assertEquals(true, tc2.get("Arg"), "Called(bool)");

        assertNull(tc.get("Arg"), "nested config");
        assertEquals(true, tc2.get("Arg"), "nested config");

        assertEquals("Call1", tc2.testObject, "nested config");
        assertEquals(4, tc2.testInt, "nested config");
        assertEquals("http://www.eclipse.com/", tc2.url.toString(), "nested call");

        assertEquals(tc.testField1, 77, "static to field");
        assertEquals(tc.testField2, 2, "field to field");
        assertEquals(TestConfiguration.VALUE, 42, "literal to static");

        assertEquals(((Map<String, String>)configuration.getIdMap().get("map")).get("key0"), "value0");
        assertEquals(((Map<String, String>)configuration.getIdMap().get("map")).get("key1"), "value1");
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testNewObject(String configure) throws Exception
    {
        TestConfiguration.VALUE = 71;
        Map<String, String> properties = new HashMap<>();
        properties.put("whatever", "xxx");

        URL url = XmlConfigurationTest.class.getClassLoader().getResource(configure);
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

        assertEquals(3, count.get());

        assertEquals("NEW DEFAULT", tc.getTestString());
        assertEquals("nested", tc.getNested().getTestString());
        assertEquals("NEW DEFAULT", tc.getNested().getNested().getTestString());

        assertEquals("SetValue", tc.testObject, "Set String");
        assertEquals(2, tc.testInt, "Set Type");

        assertEquals(18080, tc.propValue);

        assertEquals("PutValue", tc.get("Test"), "Put");
        assertEquals("2", tc.get("TestDft"), "Put dft");
        assertEquals(2, tc.get("TestInt"), "Put type");

        assertEquals("PutValue", tc.get("Trim"), "Trim");
        assertNull(tc.get("Null"), "Null");
        assertNull(tc.get("NullTrim"), "NullTrim");

        assertEquals(1.2345, tc.get("ObjectTrim"), "ObjectTrim");
        assertEquals("-1String", tc.get("Objects"), "Objects");
        assertEquals("-1String", tc.get("ObjectsTrim"), "ObjectsTrim");
        assertEquals("\n    PutValue\n  ", tc.get("String"), "String");
        assertEquals("", tc.get("NullString"), "NullString");
        assertEquals("\n  ", tc.get("WhiteSpace"), "WhiteSpace");
        assertEquals("\n    1.2345\n  ", tc.get("ObjectString"), "ObjectString");
        assertEquals("-1String", tc.get("ObjectsString"), "ObjectsString");
        assertEquals("-1\n  String", tc.get("ObjectsWhiteString"), "ObjectsWhiteString");

        assertEquals(System.getProperty("user.dir") + "/stuff", tc.get("SystemProperty"), "SystemProperty");
        assertEquals("xxx", tc.get("Property"), "Property");

        assertEquals("Yes", tc.get("Called"), "Called");

        assertTrue(TestConfiguration.called);

        assertEquals("Blah", tc.oa[0], "oa[0]");
        assertEquals("1.2.3.4:5678", tc.oa[1], "oa[1]");
        assertEquals(1.2345, tc.oa[2], "oa[2]");
        assertNull(tc.oa[3], "oa[3]");

        assertEquals(1, tc.ia[0], "ia[0]");
        assertEquals(2, tc.ia[1], "ia[1]");
        assertEquals(3, tc.ia[2], "ia[2]");
        assertEquals(0, tc.ia[3], "ia[3]");

        TestConfiguration tc2 = tc.nested;
        assertNotNull(tc2);
        assertEquals(true, tc2.get("Arg"), "Called(bool)");

        assertNull(tc.get("Arg"), "nested config");
        assertEquals(true, tc2.get("Arg"), "nested config");

        assertEquals("Call1", tc2.testObject, "nested config");
        assertEquals(4, tc2.testInt, "nested config");
        assertEquals("http://www.eclipse.com/", tc2.url.toString(), "nested call");

        assertEquals(71, tc.testField1, "static to field");
        assertEquals(2, tc.testField2, "field to field");
        assertEquals(42, TestConfiguration.VALUE, "literal to static");
    }

    public XmlConfiguration asXmlConfiguration(String rawXml) throws IOException, SAXException
    {
        return asXmlConfiguration("raw.xml", rawXml);
    }

    public XmlConfiguration asXmlConfiguration(String filename, String rawXml) throws IOException, SAXException
    {
        Path testFile = workDir.getEmptyPathDir().resolve(filename);
        try (BufferedWriter writer = Files.newBufferedWriter(testFile, UTF_8))
        {
            writer.write(rawXml);
        }
        return new XmlConfiguration(new PathResource(testFile));
    }

    @Test
    public void testGetClass() throws Exception
    {
        XmlConfiguration configuration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\"><Set name=\"Test\"><Get name=\"class\"/></Set></Configure>");
        TestConfiguration tc = new TestConfiguration();
        configuration.configure(tc);
        assertEquals(TestConfiguration.class, tc.testObject);

        configuration =
            asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\"><Set name=\"Test\"><Get class=\"java.lang.String\" name=\"class\"><Get id=\"simple\" name=\"simpleName\"/></Get></Set></Configure>");
        configuration.configure(tc);
        assertEquals(String.class, tc.testObject);
        assertEquals("String", configuration.getIdMap().get("simple"));
    }

    @Test
    public void testStringConfiguration() throws Exception
    {
        XmlConfiguration configuration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\"><Set name=\"Test\">SetValue</Set><Set name=\"Test\" type=\"int\">2</Set></Configure>");
        TestConfiguration tc = new TestConfiguration();
        configuration.configure(tc);
        assertEquals("SetValue", tc.testObject, "Set String 3");
        assertEquals(2, tc.testInt, "Set Type 3");
    }

    @Test
    public void testMeaningfullSetException() throws Exception
    {
        XmlConfiguration configuration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\"><Set name=\"PropertyTest\"><Property name=\"null\"/></Set></Configure>");
        TestConfiguration tc = new TestConfiguration();

        NoSuchMethodException e = assertThrows(NoSuchMethodException.class, () ->
        {
            configuration.configure(tc);
        });

        assertThat(e.getMessage(), containsString("Found setters for int"));
    }

    @Test
    public void testListConstructorArg() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\">" +
            "<Set name=\"constructorArgTestClass\"><New class=\"org.eclipse.jetty.xml.ConstructorArgTestClass\"><Arg type=\"List\">" +
            STRING_ARRAY_XML + "</Arg></New></Set></Configure>");
        TestConfiguration tc = new TestConfiguration();
        assertThat("tc.getList() returns null as it's not configured yet", tc.getList(), is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getList() returns not null", tc.getList(), not(nullValue()));
        assertThat("tc.getList() has two entries as specified in the xml", tc.getList().size(), is(2));
    }

    @Test
    public void testTwoArgumentListConstructorArg() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\">" +
            "<Set name=\"constructorArgTestClass\"><New class=\"org.eclipse.jetty.xml.ConstructorArgTestClass\">" +
            "<Arg type=\"List\">" + STRING_ARRAY_XML + "</Arg>" +
            "<Arg type=\"List\">" + STRING_ARRAY_XML + "</Arg>" +
            "</New></Set></Configure>");
        TestConfiguration tc = new TestConfiguration();
        assertThat("tc.getList() returns null as it's not configured yet", tc.getList(), is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getList() returns not null", tc.getList(), not(nullValue()));
        assertThat("tc.getList() has two entries as specified in the xml", tc.getList().size(), is(2));
    }

    @Test
    public void testListNotContainingArray() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\">" +
            "<New class=\"org.eclipse.jetty.xml.ConstructorArgTestClass\"><Arg type=\"List\">Some String</Arg></New></Configure>");
        TestConfiguration tc = new TestConfiguration();

        assertThrows(IllegalArgumentException.class, () ->
        {
            xmlConfiguration.configure(tc);
        });
    }

    @Test
    public void testSetConstructorArg() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\">" +
            "<Set name=\"constructorArgTestClass\"><New class=\"org.eclipse.jetty.xml.ConstructorArgTestClass\"><Arg type=\"Set\">" +
            STRING_ARRAY_XML + "</Arg></New></Set></Configure>");
        TestConfiguration tc = new TestConfiguration();
        assertThat("tc.getList() returns null as it's not configured yet", tc.getSet(), is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getList() returns not null", tc.getSet(), not(nullValue()));
        assertThat("tc.getList() has two entries as specified in the xml", tc.getSet().size(), is(2));
    }

    @Test
    public void testSetNotContainingArray() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\">" +
            "<New class=\"org.eclipse.jetty.xml.ConstructorArgTestClass\"><Arg type=\"Set\">Some String</Arg></New></Configure>");
        TestConfiguration tc = new TestConfiguration();
        assertThrows(IllegalArgumentException.class, () ->
        {
            xmlConfiguration.configure(tc);
        });
    }

    @Test
    public void testListSetterWithStringArray() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\"><Set name=\"List\">" +
            STRING_ARRAY_XML + "</Set></Configure>");
        TestConfiguration tc = new TestConfiguration();
        assertThat("tc.getList() returns null as it's not configured yet", tc.getList(), is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getList() has two entries as specified in the xml", tc.getList().size(), is(2));
    }

    @Test
    public void testListSetterWithPrimitiveArray() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\"><Set name=\"List\">" +
            INT_ARRAY_XML + "</Set></Configure>");
        TestConfiguration tc = new TestConfiguration();
        assertThat("tc.getList() returns null as it's not configured yet", tc.getList(), is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getList() has two entries as specified in the xml", tc.getList().size(), is(2));
    }

    @Test
    public void testNotSupportedLinkedListSetter() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\"><Set name=\"LinkedList\">" +
            INT_ARRAY_XML + "</Set></Configure>");
        TestConfiguration tc = new TestConfiguration();
        assertThat("tc.getSet() returns null as it's not configured yet", tc.getList(), is(nullValue()));
        assertThrows(NoSuchMethodException.class, () ->
        {
            xmlConfiguration.configure(tc);
        });
    }

    @Test
    public void testArrayListSetter() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\"><Set name=\"ArrayList\">" +
            INT_ARRAY_XML + "</Set></Configure>");
        TestConfiguration tc = new TestConfiguration();
        assertThat("tc.getSet() returns null as it's not configured yet", tc.getList(), is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getSet() has two entries as specified in the xml", tc.getList().size(), is(2));
    }

    @Test
    public void testSetSetter() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\"><Set name=\"Set\">" +
            STRING_ARRAY_XML + "</Set></Configure>");
        TestConfiguration tc = new TestConfiguration();
        assertThat("tc.getSet() returns null as it's not configured yet", tc.getSet(), is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getSet() has two entries as specified in the xml", tc.getSet().size(), is(2));
    }

    @Test
    public void testSetSetterWithPrimitiveArray() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\"><Set name=\"Set\">" +
            INT_ARRAY_XML + "</Set></Configure>");
        TestConfiguration tc = new TestConfiguration();
        assertThat("tc.getSet() returns null as it's not configured yet", tc.getSet(), is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getSet() has two entries as specified in the xml", tc.getSet().size(), is(2));
    }

    @Test
    public void testMap() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
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
        assertNull(tc.map, "tc.map is null as it's not configured yet");
        xmlConfiguration.configure(tc);
        assertEquals(2, tc.map.size(), "tc.map is has two entries as specified in the XML");
    }

    @Test
    public void testConstructorNamedInjection() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg>arg1</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg>arg3</Arg>  " +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testConstructorNamedInjectionOrdered() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg name=\"second\">arg2</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testConstructorNamedInjectionUnOrdered() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "  <Arg name=\"second\">arg2</Arg>  " +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testConstructorNamedInjectionOrderedMixed() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testConstructorNamedInjectionUnorderedMixed() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testNestedConstructorNamedInjection() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
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

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
        assertEquals("arg1", atc.getNested().getFirst(), "nested first parameter not wired correctly");
        assertEquals("arg2", atc.getNested().getSecond(), "nested second parameter not wired correctly");
        assertEquals("arg3", atc.getNested().getThird(), "nested third parameter not wired correctly");
    }

    @Test
    public void testNestedConstructorNamedInjectionOrdered() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
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

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
        assertEquals("arg1", atc.getNested().getFirst(), "nested first parameter not wired correctly");
        assertEquals("arg2", atc.getNested().getSecond(), "nested second parameter not wired correctly");
        assertEquals("arg3", atc.getNested().getThird(), "nested third parameter not wired correctly");
    }

    private static class TestOrder
    {
        public void call()
        {
        }

        public void call(int o)
        {
        }

        public void call(Object o)
        {
        }

        public void call(String s)
        {
        }

        public void call(String... ss)
        {
        }

        public void call(String s, String... ss)
        {
        }
    }

    @RepeatedTest(10)
    public void testMethodOrdering() throws Exception
    {
        List<Method> methods = Arrays.stream(TestOrder.class.getMethods()).filter(m -> "call".equals(m.getName())).collect(Collectors.toList());
        Collections.shuffle(methods);
        Collections.sort(methods, XmlConfiguration.EXECUTABLE_COMPARATOR);
        assertThat(methods, Matchers.contains(
            TestOrder.class.getMethod("call"),
            TestOrder.class.getMethod("call", int.class),
            TestOrder.class.getMethod("call", String.class),
            TestOrder.class.getMethod("call", Object.class),
            TestOrder.class.getMethod("call", String[].class),
            TestOrder.class.getMethod("call", String.class, String[].class)
        ));
    }

    @Test
    public void testOverloadedCall() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Call name=\"call\">" +
                "    <Arg type=\"int\">1</Arg>" +
                "  </Call>" +
                "  <Call name=\"call\">" +
                "    <Arg>2</Arg>" +
                "  </Call>" +
                "  <Call name=\"call\">" +
                "    <Arg type=\"Long\">3</Arg>" +
                "  </Call>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("1", atc.getFirst());
        assertEquals("2", atc.getSecond());
        assertEquals("3", atc.getThird());
    }

    @Test
    public void testNestedConstructorNamedInjectionUnOrdered() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
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

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
        assertEquals("arg1", atc.getNested().getFirst(), "nested first parameter not wired correctly");
        assertEquals("arg2", atc.getNested().getSecond(), "nested second parameter not wired correctly");
        assertEquals("arg3", atc.getNested().getThird(), "nested third parameter not wired correctly");
    }

    @Test
    public void testNestedConstructorNamedInjectionOrderedMixed() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
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

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
        assertEquals("arg1", atc.getNested().getFirst(), "nested first parameter not wired correctly");
        assertEquals("arg2", atc.getNested().getSecond(), "nested second parameter not wired correctly");
        assertEquals("arg3", atc.getNested().getThird(), "nested third parameter not wired correctly");
    }

    @Test
    public void testCallNamedInjection() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                " <Call name=\"setAll\">" +
                "  <Arg>arg1</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg>arg3</Arg>  " +
                " </Call>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testCallNamedInjectionOrdered() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                " <Call name=\"setAll\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg name=\"second\">arg2</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                " </Call>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testCallNamedInjectionUnOrdered() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                " <Call name=\"setAll\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "  <Arg name=\"second\">arg2</Arg>  " +
                " </Call>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testCallNamedInjectionOrderedMixed() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                " <Call name=\"setAll\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                " </Call>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testCallNamedInjectionUnorderedMixed() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                " <Call name=\"setAll\">" +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg name=\"first\">arg1</Arg>  " +
                " </Call>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testCallVarArgs() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                " <Call name=\"setVarArgs\">" +
                "  <Arg>one</Arg>  " +
                "  <Arg><Array type=\"String\"><Item type=\"String\">two</Item><Item type=\"String\">three</Item></Array></Arg>  " +
                " </Call>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("one", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("two", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("three", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testCallMissingVarArgs() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg name=\"second\">arg2</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                " <Call name=\"setVarArgs\">" +
                "  <Arg>one</Arg>" +
                " </Call>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("one", atc.getFirst(), "first parameter not wired correctly");
        assertNull(atc.getSecond());
        assertNull(atc.getThird());
    }

    public static List<String> typeTestData()
    {
        return Arrays.asList(
            "byte",
            "int",
            "short",
            "long",
            "float",
            "double",
            "Byte",
            "Integer",
            "Short",
            "Long",
            "Float",
            "Double");
    }

    @ParameterizedTest
    @MethodSource("typeTestData")
    public void testCallNumberConversion(String type) throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\">" +
                " <Call name=\"setNumber\">" +
                "  <Arg type=\"" + type + "\">42</Arg>" +
                " </Call>" +
                "</Configure>");

        TestConfiguration tc = (TestConfiguration)xmlConfiguration.configure();
        assertEquals(42.0D, tc.number);
    }

    @Test
    public void testArgumentsGetIgnoredMissingDTD() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
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
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
        assertEquals("arg1", atc.getNested().getFirst(), "nested first parameter not wired correctly");
        assertEquals("arg2", atc.getNested().getSecond(), "nested second parameter not wired correctly");
        assertEquals("arg3", atc.getNested().getThird(), "nested third parameter not wired correctly");
    }

    @Test
    public void testSetGetIgnoredMissingDTD() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
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
                "</Configure>");

        DefaultTestConfiguration atc = (DefaultTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
        assertEquals("arg1", atc.getNested().getFirst(), "nested first parameter not wired correctly");
        assertEquals("arg2", atc.getNested().getSecond(), "nested second parameter not wired correctly");
        assertEquals("arg3", atc.getNested().getThird(), "nested third parameter not wired correctly");
    }

    @Test
    public void testNestedConstructorNamedInjectionUnorderedMixed() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
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

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
        assertEquals("arg1", atc.getNested().getFirst(), "nested first parameter not wired correctly");
        assertEquals("arg2", atc.getNested().getSecond(), "nested second parameter not wired correctly");
        assertEquals("arg3", atc.getNested().getThird(), "nested third parameter not wired correctly");
    }

    public static class NativeHolder
    {
        public boolean _boolean;
        public int _integer;
        public float _float;

        public boolean getBoolean()
        {
            return _boolean;
        }

        public void setBoolean(boolean value)
        {
            this._boolean = value;
        }

        public int getInteger()
        {
            return _integer;
        }

        public void setInteger(int integer)
        {
            _integer = integer;
        }

        public float getFloat()
        {
            return _float;
        }

        public void setFloat(float f)
        {
            _float = f;
        }
    }

    @Test
    public void testSetBooleanTrue() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.XmlConfigurationTest$NativeHolder\">" +
                "  <Set name=\"boolean\">true</Set>" +
                "</Configure>");

        NativeHolder bh = (NativeHolder)xmlConfiguration.configure();
        assertTrue(bh.getBoolean());
    }

    @Test
    public void testSetBooleanFalse() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.XmlConfigurationTest$NativeHolder\">" +
                "  <Set name=\"boolean\">false</Set>" +
                "</Configure>");

        NativeHolder bh = (NativeHolder)xmlConfiguration.configure();
        assertFalse(bh.getBoolean());
    }

    @Test
    public void testSetBadBoolean() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.XmlConfigurationTest$NativeHolder\">" +
                "  <Set name=\"boolean\">tru</Set>" +
                "</Configure>");

        //Any string other than "true" (case insensitive) will be false
        //according to Boolean constructor.
        NativeHolder bh = (NativeHolder)xmlConfiguration.configure();
        assertFalse(bh.getBoolean(), "boolean['tru']");
    }

    @Test
    public void testSetBadInteger() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.XmlConfigurationTest$NativeHolder\">" +
                "  <Set name=\"integer\">bad</Set>" +
                "</Configure>");

        assertThrows(InvocationTargetException.class, () -> xmlConfiguration.configure());
    }

    @Test
    public void testSetBadExtraInteger() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.XmlConfigurationTest$NativeHolder\">" +
                "  <Set name=\"integer\">100 bas</Set>" +
                "</Configure>");

        assertThrows(InvocationTargetException.class, () -> xmlConfiguration.configure());
    }

    @Test
    public void testSetTrimmedSetterInt() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.XmlConfigurationTest$NativeHolder\">" +
                "  <Set name=\"integer\">" +
                "    42  " +
                "  </Set>" +
                "</Configure>");

        NativeHolder holder = (NativeHolder)xmlConfiguration.configure();
        assertThat(holder._integer, is(42));
    }

    @Test
    public void testSetTrimmedIntSetterWithType() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.XmlConfigurationTest$NativeHolder\">" +
                "  <Set name=\"integer\" type=\"int\">" +
                "    42  " +
                "  </Set>" +
                "</Configure>");

        NativeHolder holder = (NativeHolder)xmlConfiguration.configure();
        assertThat(holder._integer, is(42));
    }

    @Test
    public void testSetTrimmedFieldInt() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.XmlConfigurationTest$NativeHolder\">" +
                "  <Set name=\"_integer\">" +
                "    42  " +
                "  </Set>" +
                "</Configure>");

        NativeHolder holder = (NativeHolder)xmlConfiguration.configure();
        assertThat(holder._integer, is(42));
    }

    @Test
    public void testSetTrimmedIntFieldWithType() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.XmlConfigurationTest$NativeHolder\">" +
                "  <Set name=\"integer\" type=\"int\">" +
                "    42  " +
                "  </Set>" +
                "</Configure>");

        NativeHolder holder = (NativeHolder)xmlConfiguration.configure();
        assertThat(holder._integer, is(42));
    }

    @Test
    public void testSetBadFloatInteger() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.XmlConfigurationTest$NativeHolder\">" +
                "  <Set name=\"integer\">1.5</Set>" +
                "</Configure>");

        assertThrows(InvocationTargetException.class, () -> xmlConfiguration.configure());
    }

    @Test
    public void testWithMultiplePropertyNamesWithNoPropertyThenDefaultIsChosen() throws Exception
    {
        // No properties
        String defolt = "baz";
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.DefaultTestConfiguration\">" +
                "  <Set name=\"first\"><Property name=\"wibble\" deprecated=\"foo,bar\" default=\"" + defolt + "\"/></Set>  " +
                "</Configure>");
        DefaultTestConfiguration config = (DefaultTestConfiguration)xmlConfiguration.configure();
        assertEquals(defolt, config.getFirst());
    }

    @Test
    public void testWithMultiplePropertyNamesWithFirstPropertyThenFirstIsChosen() throws Exception
    {
        String name = "foo";
        String value = "foo";
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.DefaultTestConfiguration\">" +
                "  <Set name=\"first\"><Property name=\"" + name + "\" deprecated=\"other,bar\" default=\"baz\"/></Set>  " +
                "</Configure>");
        xmlConfiguration.getProperties().put(name, value);
        DefaultTestConfiguration config = (DefaultTestConfiguration)xmlConfiguration.configure();
        assertEquals(value, config.getFirst());
    }

    @Test
    public void testWithMultiplePropertyNamesWithSecondPropertyThenSecondIsChosen() throws Exception
    {
        String name = "bar";
        String value = "bar";
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.DefaultTestConfiguration\">" +
                "  <Set name=\"first\"><Property name=\"foo\" deprecated=\"" + name + "\" default=\"baz\"/></Set>  " +
                "</Configure>");
        xmlConfiguration.getProperties().put(name, value);
        DefaultTestConfiguration config = (DefaultTestConfiguration)xmlConfiguration.configure();
        assertEquals(value, config.getFirst());
    }

    @Test
    public void testWithMultiplePropertyNamesWithDeprecatedThenThirdIsChosen() throws Exception
    {
        String name = "bar";
        String value = "bar";
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.DefaultTestConfiguration\">" +
                "  <Set name=\"first\"><Property name=\"foo\" deprecated=\"other," + name + "\" default=\"baz\"/></Set>  " +
                "</Configure>");
        xmlConfiguration.getProperties().put(name, value);
        DefaultTestConfiguration config = (DefaultTestConfiguration)xmlConfiguration.configure();
        assertEquals(value, config.getFirst());
    }

    @Test
    public void testWithMultiplePropertyNameElementsWithDeprecatedThenThirdIsChosen() throws Exception
    {
        String name = "bar";
        String value = "bar";
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.DefaultTestConfiguration\">" +
                "  <Set name=\"first\">" +
                "  <Property>  " +
                "    <Name>foo</Name>" +
                "    <Deprecated>foo</Deprecated>" +
                "    <Deprecated>" + name + "</Deprecated>" +
                "    <Default>baz</Default>" +
                "  </Property>  " +
                "  </Set>  " +
                "</Configure>");
        xmlConfiguration.getProperties().put(name, value);
        DefaultTestConfiguration config = (DefaultTestConfiguration)xmlConfiguration.configure();
        assertEquals(value, config.getFirst());
    }

    @Test
    public void testPropertyNotFoundWithPropertyInDefaultValue() throws Exception
    {
        String name = "bar";
        String value = "bar";
        String defaultValue = "_<Property name=\"bar\"/>_<Property name=\"bar\"/>_";
        String expectedValue = "_" + value + "_" + value + "_";
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.DefaultTestConfiguration\">" +
                "  <Set name=\"first\">" +
                "    <Property>" +
                "      <Name>not_found</Name>" +
                "      <Default>" + defaultValue + "</Default>" +
                "    </Property>" +
                "  </Set>  " +
                "</Configure>");
        xmlConfiguration.getProperties().put(name, value);
        DefaultTestConfiguration config = (DefaultTestConfiguration)xmlConfiguration.configure();
        assertEquals(expectedValue, config.getFirst());
    }

    @Test
    public void testPropertyNotFoundWithPropertyInDefaultValueNotFoundWithDefault() throws Exception
    {
        String value = "bar";
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.DefaultTestConfiguration\">" +
                "  <Set name=\"first\">" +
                "    <Property name=\"not_found\">" +
                "      <Default><Property name=\"also_not_found\" default=\"" + value + "\"/></Default>" +
                "    </Property>" +
                "  </Set>  " +
                "</Configure>");
        DefaultTestConfiguration config = (DefaultTestConfiguration)xmlConfiguration.configure();
        assertEquals(value, config.getFirst());
    }

    @Test
    public void testJettyStandardIdsAndPropertiesAndJettyHomeAndJettyBase() throws Exception
    {
        String[] propNames = new String[]
            {
                "jetty.base",
                "jetty.home"
            };

        for (String propName : propNames)
        {
            XmlConfiguration configuration =
                asXmlConfiguration(
                    "<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\">" +
                        "  <Set name=\"TestString\">" +
                        "    <Property name=\"" + propName + "\"/>" +
                        "  </Set>" +
                        "</Configure>");

            configuration.setJettyStandardIdsAndProperties(null, null);

            TestConfiguration tc = new TestConfiguration();
            configuration.configure(tc);

            assertThat(propName, tc.getTestString(), is(notNullValue()));
            assertThat(propName, tc.getTestString(), not(startsWith("file:")));
        }
    }

    @Test
    public void testJettyStandardIdsAndPropertiesAndJettyHomeUriAndJettyBaseUri() throws Exception
    {
        String[] propNames = new String[]
            {
                "jetty.base.uri",
                "jetty.home.uri"
            };

        for (String propName : propNames)
        {
            XmlConfiguration configuration =
                asXmlConfiguration(
                    "<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\">" +
                        "  <Set name=\"TestString\">" +
                        "    <Property name=\"" + propName + "\"/>" +
                        "  </Set>" +
                        "</Configure>");

            configuration.setJettyStandardIdsAndProperties(null, null);

            TestConfiguration tc = new TestConfiguration();
            configuration.configure(tc);

            assertThat(propName, tc.getTestString(), is(notNullValue()));
            assertThat(propName, tc.getTestString(), startsWith("file:"));
        }
    }

    public static class BarNamed
    {
        private String foo;
        private List<String> zeds;

        public BarNamed(@Name("foo") String foo)
        {
            this.foo = foo;
        }

        public void addZed(String zed)
        {
            if (zeds == null)
                zeds = new ArrayList<>();
            zeds.add(zed);
        }

        public List<String> getZeds()
        {
            return zeds;
        }

        public String getFoo()
        {
            return foo;
        }
    }

    @Test
    public void testConfiguredWithNamedArg() throws Exception
    {
        XmlConfiguration xmlFoo = asXmlConfiguration("foo.xml",
            "<Configure>\n" +
                "  <New id=\"foo\" class=\"java.lang.String\">\n" +
                "    <Arg>foozball</Arg>\n" +
                "  </New>\n" +
                "</Configure>");
        XmlConfiguration xmlBar = asXmlConfiguration("bar.xml",
            "<Configure id=\"bar\" class=\"" + BarNamed.class.getName() + "\">\n" +
                "  <Arg name=\"foo\"><Ref refid=\"foo\"/></Arg>\n" +
                "</Configure>");

        try (StdErrCapture logCapture = new StdErrCapture(XmlConfiguration.class))
        {
            Map<String, Object> idMap = mimicXmlConfigurationMain(xmlFoo, xmlBar);
            Object obj = idMap.get("bar");
            assertThat("BarNamed instance created", obj, instanceOf(BarNamed.class));
            BarNamed bar = (BarNamed)obj;
            assertThat("BarNamed has foo", bar.getFoo(), is("foozball"));

            List<String> warnLogs = logCapture.getLines()
                .stream().filter(line -> line.contains(":WARN:"))
                .collect(Collectors.toList());

            assertThat("WARN logs size", warnLogs.size(), is(0));
        }
    }

    @Test
    public void testConfiguredWithArgNotUsingName() throws Exception
    {
        XmlConfiguration xmlFoo = asXmlConfiguration("foo.xml",
            "<Configure>\n" +
                "  <New id=\"foo\" class=\"java.lang.String\">\n" +
                "    <Arg>foozball</Arg>\n" +
                "  </New>\n" +
                "</Configure>");
        XmlConfiguration xmlBar = asXmlConfiguration("bar.xml",
            "<Configure id=\"bar\" class=\"" + BarNamed.class.getName() + "\">\n" +
                "  <Arg><Ref refid=\"foo\"/></Arg>\n" + // no name specified
                "</Configure>");

        try (StdErrCapture logCapture = new StdErrCapture(XmlConfiguration.class))
        {
            Map<String, Object> idMap = mimicXmlConfigurationMain(xmlFoo, xmlBar);
            Object obj = idMap.get("bar");
            assertThat("BarNamed instance created", obj, instanceOf(BarNamed.class));
            BarNamed bar = (BarNamed)obj;
            assertThat("BarNamed has foo", bar.getFoo(), is("foozball"));

            List<String> warnLogs = logCapture.getLines()
                .stream().filter(line -> line.contains(":WARN:"))
                .collect(Collectors.toList());

            assertThat("WARN logs size", warnLogs.size(), is(0));
        }
    }

    @Test
    public void testConfiguredWithBadNamedArg() throws Exception
    {
        XmlConfiguration xmlBar = asXmlConfiguration("bar.xml",
            "<Configure id=\"bar\" class=\"" + BarNamed.class.getName() + "\">\n" +
                "  <Arg name=\"foozball\">foozball</Arg>\n" + // wrong name specified
                "</Configure>");

        IllegalStateException cause = assertThrows(IllegalStateException.class, () ->
            mimicXmlConfigurationMain(xmlBar));

        assertThat("Cause message", cause.getMessage(), containsString("No matching constructor"));
    }

    @Test
    public void testConfiguredWithTooManyNamedArgs() throws Exception
    {
        XmlConfiguration xmlBar = asXmlConfiguration("bar.xml",
            "<Configure id=\"bar\" class=\"" + BarNamed.class.getName() + "\">\n" +
                "  <Arg name=\"foo\">foozball</Arg>\n" +
                "  <Arg name=\"foo\">soccer</Arg>\n" + // neither should win
                "</Configure>");

        IllegalStateException cause = assertThrows(IllegalStateException.class, () ->
            mimicXmlConfigurationMain(xmlBar));

        assertThat("Cause message", cause.getMessage(), containsString("No matching constructor"));
    }

    @Test
    public void testConfiguredSameWithNamedArgTwice() throws Exception
    {
        XmlConfiguration xmlFoo = asXmlConfiguration("foo.xml",
            "<Configure>\n" +
                "  <New id=\"foo\" class=\"java.lang.String\">\n" +
                "    <Arg>foozball</Arg>\n" +
                "  </New>\n" +
                "</Configure>");
        XmlConfiguration xmlBar = asXmlConfiguration("bar.xml",
            "<Configure id=\"bar\" class=\"" + BarNamed.class.getName() + "\">\n" +
                "  <Arg name=\"foo\"><Ref refid=\"foo\"/></Arg>\n" +
                "</Configure>");
        XmlConfiguration xmlAddZed = asXmlConfiguration("zed.xml",
            "<Configure id=\"bar\" class=\"" + BarNamed.class.getName() + "\">\n" +
                "  <Arg name=\"foo\">baz</Arg>\n" + // the invalid line
                "  <Call name=\"addZed\">\n" +
                "    <Arg>plain-zero</Arg>\n" +
                "  </Call>\n" +
                "</Configure>");

        try (StdErrCapture logCapture = new StdErrCapture(XmlConfiguration.class))
        {
            Map<String, Object> idMap = mimicXmlConfigurationMain(xmlFoo, xmlBar, xmlAddZed);
            Object obj = idMap.get("bar");
            assertThat("BarNamed instance created", obj, instanceOf(BarNamed.class));
            BarNamed bar = (BarNamed)obj;
            assertThat("BarNamed has foo", bar.getFoo(), is("foozball"));
            List<String> zeds = bar.getZeds();
            assertThat("BarNamed has zeds", zeds, not(empty()));
            assertThat("Zeds[0]", zeds.get(0), is("plain-zero"));

            List<String> warnLogs = logCapture.getLines()
                .stream().filter(line -> line.contains(":WARN:"))
                .collect(Collectors.toList());

            assertThat("WARN logs count", warnLogs.size(), is(1));

            String actualWarn = warnLogs.get(0);
            assertThat("WARN logs", actualWarn,
                allOf(containsString("Ignored arg <Arg name="),
                    containsString("zed.xml")
                ));
        }
    }

    /**
     * This mimics the XML load behavior in XmlConfiguration.main(String ... args)
     */
    private Map<String, Object> mimicXmlConfigurationMain(XmlConfiguration... configurations) throws Exception
    {
        XmlConfiguration last = null;
        for (XmlConfiguration configuration : configurations)
        {
            if (last != null)
                configuration.getIdMap().putAll(last.getIdMap());
            configuration.configure();
            last = configuration;
        }
        return last.getIdMap();
    }

    @Test
    public void testDeprecatedMany() throws Exception
    {
        Class<?> testClass = AnnotatedTestConfiguration.class;
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"" + testClass.getName() + "\">" +
                "  <Set name=\"deprecated\">foo</Set>" +
                "  <Set name=\"timeout\"><Property name=\"test.timeout\" default=\"-1\"/></Set>" +
                "  <Set name=\"obsolete\">" +
                "    <Call name=\"setDeprecated\"><Arg><Get name=\"deprecated\" /></Arg></Call>" +
                "  </Set>" +
                "  <Get name=\"obsolete\" />" +
                "</Configure>");

        List<String> logLines;
        try (StdErrCapture logCapture = new StdErrCapture(XmlConfiguration.class))
        {
            xmlConfiguration.getProperties().put("test.timeout", "-1");
            xmlConfiguration.configure();
            logLines = logCapture.getLines();
        }

        List<String> warnings = logLines.stream()
            .filter(line -> line.contains(":WARN:"))
            .filter(line -> line.contains(testClass.getSimpleName()))
            .collect(Collectors.toList());
        // 1. Deprecated constructor
        // 2. Deprecated <Set> method
        // 3. Deprecated <Get> method
        // 4. Deprecated <Call> method
        // 5. Deprecated <Set> field
        // 6. Deprecated <Get> field
        assertEquals(6, warnings.size());
    }

    @Test
    public void testDeprecatedPropertyUnSet() throws Exception
    {
        Class<?> testClass = AnnotatedTestConfiguration.class;
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"" + testClass.getName() + "\">" +
                "  <Set name=\"timeout\"><Property name=\"test.timeout\" default=\"-1\"/></Set>" +
                "</Configure>");
        assertDeprecatedPropertyUnSet(testClass, xmlConfiguration);
    }

    @Test
    public void testDeprecatedPropertyUnSetWhiteSpace() throws Exception
    {
        Class<?> testClass = AnnotatedTestConfiguration.class;
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"" + testClass.getName() + "\">" +
                "  <Set name=\"timeout\">" +
                "    <Property name=\"test.timeout\" default=\"-1\"/>" +
                "  </Set>" +
                "</Configure>");
        assertDeprecatedPropertyUnSet(testClass, xmlConfiguration);
    }

    private void assertDeprecatedPropertyUnSet(Class<?> testClass, XmlConfiguration xmlConfiguration) throws Exception
    {
        List<String> logLines;
        try (StdErrCapture logCapture = new StdErrCapture(XmlConfiguration.class))
        {
            // Leave this line alone, as this tests what happens if property is unset,
            // so that it relies on the <Property default=""> value
            // xmlConfiguration.getProperties().put("test.timeout", "-1");
            xmlConfiguration.configure();
            logLines = logCapture.getLines();
        }

        List<String> warnings = logLines.stream()
            .filter(LogPredicates.deprecatedWarnings(testClass))
            .collect(Collectors.toList());
        String[] expected = {
            "Deprecated constructor public org.eclipse.jetty.xml.AnnotatedTestConfiguration"
        };

        assertHasExpectedLines("Warnings", warnings, expected);
    }

    @Test
    public void testDeprecatedPropertySetToDefaultValue() throws Exception
    {
        Class<?> testClass = AnnotatedTestConfiguration.class;
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"" + testClass.getName() + "\">" +
                "  <Set name=\"timeout\"><Property name=\"test.timeout\" default=\"-1\"/></Set>" +
                "</Configure>");

        assertDeprecatedPropertySetToDefaultValue(testClass, xmlConfiguration);
    }

    @Test
    public void testDeprecatedPropertySetToDefaultValueWhiteSpace() throws Exception
    {
        Class<?> testClass = AnnotatedTestConfiguration.class;
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"" + testClass.getName() + "\">" +
                "  <Set name=\"timeout\">" +
                "    <Property name=\"test.timeout\" default=\"-1\"/>" +
                "  </Set>" +
                "</Configure>");

        assertDeprecatedPropertySetToDefaultValue(testClass, xmlConfiguration);
    }

    private void assertDeprecatedPropertySetToDefaultValue(Class<?> testClass, XmlConfiguration xmlConfiguration) throws Exception
    {
        List<String> logLines;
        try (StdErrCapture logCapture = new StdErrCapture(XmlConfiguration.class))
        {
            // Leave this line alone, as this tests what happens if property is set,
            // and has the same value as declared on <Property default="">
            xmlConfiguration.getProperties().put("test.timeout", "-1");
            xmlConfiguration.configure();
            logLines = logCapture.getLines();
        }

        List<String> warnings = logLines.stream()
            .filter(LogPredicates.deprecatedWarnings(testClass))
            .collect(Collectors.toList());

        String[] expected = {
            "Deprecated constructor public org.eclipse.jetty.xml.AnnotatedTestConfiguration",
            };
        assertHasExpectedLines("Warnings", warnings, expected);

        List<String> debugs = logLines.stream()
            .filter(LogPredicates.deprecatedDebug(testClass))
            .collect(Collectors.toList());

        expected = new String[]{
            "Deprecated method public void org.eclipse.jetty.xml.AnnotatedTestConfiguration.setTimeout(long)"
        };

        assertHasExpectedLines("Debugs", debugs, expected);
    }

    @Test
    public void testDeprecatedPropertySetToNewValue() throws Exception
    {
        Class<?> testClass = AnnotatedTestConfiguration.class;
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"" + testClass.getName() + "\">" +
                "  <Set name=\"timeout\"><Property name=\"test.timeout\" default=\"-1\"/></Set>" +
                "</Configure>");

        List<String> logLines;
        try (StdErrCapture logCapture = new StdErrCapture(XmlConfiguration.class))
        {
            // Leave this line alone, as this tests what happens if property is set,
            // and has the same value as declared on <Property default="">
            xmlConfiguration.getProperties().put("test.timeout", "30000");
            xmlConfiguration.configure();
            logLines = logCapture.getLines();
        }

        List<String> warnings = logLines.stream()
            .filter(LogPredicates.deprecatedWarnings(testClass))
            .collect(Collectors.toList());
        String[] expected = {
            "Deprecated constructor public org.eclipse.jetty.xml.AnnotatedTestConfiguration",
            "Deprecated method public void org.eclipse.jetty.xml.AnnotatedTestConfiguration.setTimeout(long)"
        };
        assertThat("Count of warnings", warnings.size(), is(expected.length));
        for (int i = 0; i < expected.length; i++)
        {
            assertThat("Warning[" + i + "]", warnings.get(i), containsString(expected[i]));
        }
    }

    @Test
    public void testSetDeprecatedMultipleProperties() throws Exception
    {
        Class<?> testClass = AnnotatedTestConfiguration.class;
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"" + testClass.getName() + "\">" +
                "  <Set name=\"obsolete\">" +
                "    <Property name=\"obs.1\" default=\"foo\"/>" +
                "    <Property name=\"obs.2\" default=\"bar\"/>" +
                "  </Set>" +
                "</Configure>");

        List<String> logLines;
        try (StdErrCapture logCapture = new StdErrCapture(XmlConfiguration.class))
        {
            // Leave this line alone, as this tests what happens if property is set,
            // and has the same value as declared on <Property default="">
            // xmlConfiguration.getProperties().put("obs.1", "30000");
            xmlConfiguration.configure();
            logLines = logCapture.getLines();
        }

        List<String> warnings = logLines.stream()
            .filter(LogPredicates.deprecatedWarnings(testClass))
            .collect(Collectors.toList());
        String[] expected = {
            "Deprecated constructor public org.eclipse.jetty.xml.AnnotatedTestConfiguration",
            "Deprecated field public java.lang.String org.eclipse.jetty.xml.AnnotatedTestConfiguration.obsolete"
        };
        assertThat("Count of warnings", warnings.size(), is(expected.length));
        for (int i = 0; i < expected.length; i++)
        {
            assertThat("Warning[" + i + "]", warnings.get(i), containsString(expected[i]));
        }
    }

    private static class LogPredicates
    {
        public static Predicate<String> deprecatedWarnings(Class<?> testClass)
        {
            return (line) -> line.contains(":WARN:") &&
                line.contains(": Deprecated ") &&
                line.contains(testClass.getName());
        }

        public static Predicate<String> deprecatedDebug(Class<?> testClass)
        {
            return (line) -> line.contains(":DBUG:") &&
                line.contains(": Deprecated ") &&
                line.contains(testClass.getName());
        }
    }

    private void assertHasExpectedLines(String type, List<String> actualLines, String[] expectedLines)
    {
        assertThat("Count of " + type, actualLines.size(), is(expectedLines.length));
        for (int i = 0; i < expectedLines.length; i++)
        {
            assertThat(type + "[" + i + "]", actualLines.get(i), containsString(expectedLines[i]));
        }
    }

    private static class StdErrCapture implements AutoCloseable
    {
        private ByteArrayOutputStream logBytes;
        private List<Logger> loggers = new ArrayList<>();
        private final PrintStream logStream;

        public StdErrCapture(Class<?>... classes)
        {
            for (Class<?> clazz : classes)
            {
                Logger logger = Log.getLogger(clazz);
                loggers.add(logger);
            }

            logBytes = new ByteArrayOutputStream();
            logStream = new PrintStream(logBytes);

            loggers.forEach((logger) ->
            {
                logger.setDebugEnabled(true);
                if (logger instanceof StdErrLog)
                {
                    StdErrLog stdErrLog = (StdErrLog)logger;
                    stdErrLog.setStdErrStream(logStream);
                }
            });
        }

        public List<String> getLines() throws UnsupportedEncodingException
        {
            logStream.flush();
            String[] lines = logBytes.toString(UTF_8.name()).split(System.lineSeparator());
            return Arrays.asList(lines);
        }

        @Override
        public void close()
        {
            loggers.forEach((logger) -> logger.setDebugEnabled(false));
        }
    }
}
