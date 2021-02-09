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

package org.eclipse.jetty.spring;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class SpringXmlConfigurationTest
{
    protected String _configure = "org/eclipse/jetty/spring/configure.xml";

    @BeforeEach
    public void init() throws Exception
    {
        // Jetty's XML configuration will make use of java.util.ServiceLoader
        // to load the proper ConfigurationProcessorFactory, so these tests
        // will always fail in JDK 5.

        String javaVersion = System.getProperty("java.version");
        Pattern regexp = Pattern.compile("1\\.(\\d{1})\\..*");
        Matcher matcher = regexp.matcher(javaVersion);
        if (matcher.matches())
        {
            String minor = matcher.group(1);
            assumeTrue(Integer.parseInt(minor) > 5);
        }
    }

    @Test
    public void testPassedObject() throws Exception
    {
        TestConfiguration.VALUE = 77;

        URL url = SpringXmlConfigurationTest.class.getClassLoader().getResource(_configure);
        XmlConfiguration configuration = new XmlConfiguration(url);

        Map<String, String> properties = new HashMap<>();
        properties.put("test", "xxx");

        TestConfiguration nested = new TestConfiguration();
        nested.setTestString0("nested");
        configuration.getIdMap().put("nested", nested);

        TestConfiguration tc = new TestConfiguration();
        tc.setTestString0("preconfig");
        tc.setTestInt0(42);
        configuration.getProperties().putAll(properties);

        tc = (TestConfiguration)configuration.configure(tc);

        assertEquals("preconfig", tc.getTestString0());
        assertEquals(42, tc.getTestInt0());
        assertEquals("SetValue", tc.getTestString1());
        assertEquals(1, tc.getTestInt1());

        assertEquals("nested", tc.getNested().getTestString0());
        assertEquals("nested", tc.getNested().getTestString1());
        assertEquals("default", tc.getNested().getNested().getTestString0());
        assertEquals("deep", tc.getNested().getNested().getTestString1());

        assertEquals("deep", ((TestConfiguration)configuration.getIdMap().get("nestedDeep")).getTestString1());
        assertEquals(2, ((TestConfiguration)configuration.getIdMap().get("nestedDeep")).getTestInt2());

        assertEquals("xxx", tc.getTestString2());
    }

    @Test
    public void testNewObject() throws Exception
    {
        final String newDefaultValue = "NEW DEFAULT";
        TestConfiguration.VALUE = 71;

        URL url = SpringXmlConfigurationTest.class.getClassLoader().getResource(_configure);
        final AtomicInteger count = new AtomicInteger(0);
        XmlConfiguration configuration = new XmlConfiguration(url)
        {
            @Override
            public void initializeDefaults(Object object)
            {
                super.initializeDefaults(object);
                if (object instanceof TestConfiguration)
                {
                    count.incrementAndGet();
                    ((TestConfiguration)object).setTestString0(newDefaultValue);
                    ((TestConfiguration)object).setTestString1("WILL BE OVERRIDDEN");
                }
            }
        };

        Map<String, String> properties = new HashMap<String, String>();
        properties.put("test", "xxx");

        TestConfiguration nested = new TestConfiguration();
        nested.setTestString0("nested");
        configuration.getIdMap().put("nested", nested);

        configuration.getProperties().putAll(properties);
        TestConfiguration tc = (TestConfiguration)configuration.configure();

        assertEquals(3, count.get());

        assertEquals(newDefaultValue, tc.getTestString0());
        assertEquals(-1, tc.getTestInt0());
        assertEquals("SetValue", tc.getTestString1());
        assertEquals(1, tc.getTestInt1());

        assertEquals(newDefaultValue, tc.getNested().getTestString0());
        assertEquals("nested", tc.getNested().getTestString1());
        assertEquals(newDefaultValue, tc.getNested().getNested().getTestString0());
        assertEquals("deep", tc.getNested().getNested().getTestString1());

        assertEquals("deep", ((TestConfiguration)configuration.getIdMap().get("nestedDeep")).getTestString1());
        assertEquals(2, ((TestConfiguration)configuration.getIdMap().get("nestedDeep")).getTestInt2());

        assertEquals("xxx", tc.getTestString2());
    }

    @Test
    public void testJettyXml() throws Exception
    {
        URL url = SpringXmlConfigurationTest.class.getClassLoader().getResource("org/eclipse/jetty/spring/jetty.xml");
        XmlConfiguration configuration = new XmlConfiguration(url);

        Server server = (Server)configuration.configure();

        server.dumpStdErr();
    }

    @Test
    public void xmlConfigurationMain() throws Exception
    {
        XmlConfiguration.main("src/test/resources/org/eclipse/jetty/spring/jetty.xml");
    }
}
