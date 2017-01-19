//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.test.jmx;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.IOException;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Some JMX information tests.
 */
public class JmxIT
{
    private static JMXConnector jmxc;
    private static MBeanServerConnection mbsc;

    @BeforeClass
    public static void connectToMBeanServer() throws IOException
    {
        JMXServiceURL url = new JMXServiceURL("service:jmx:rmi://localhost:1099/jndi/rmi://localhost:1099/jmxrmi");
        jmxc = JMXConnectorFactory.connect(url,null);
        mbsc = jmxc.getMBeanServerConnection();
    }

    @AfterClass
    public static void disconnectFromMBeanServer() throws IOException
    {
        jmxc.close();
    }

    private String getStringAttribute(ObjectName objName, String attrName) throws Exception
    {
        Object val = mbsc.getAttribute(objName,attrName);
        assertThat(attrName,val,notNullValue());
        assertThat(attrName,val,instanceOf(String.class));
        return (String)val;
    }

    private int getIntegerAttribute(ObjectName objName, String attrName) throws Exception
    {
        Object val = mbsc.getAttribute(objName,attrName);
        assertThat(attrName,val,notNullValue());
        assertThat(attrName,val,instanceOf(Integer.class));
        return (Integer)val;
    }

    @Test
    public void testObtainRunningServerVersion() throws Exception
    {
        ObjectName serverName = new ObjectName("org.eclipse.jetty.server:type=server,id=0");
        String version = getStringAttribute(serverName,"version");
        System.err.println("Running version: " + version);
        assertThat("Version",version,startsWith("9.2."));
    }

    @Test
    public void testObtainJmxWebAppState() throws Exception
    {
        ObjectName webappName = new ObjectName("org.eclipse.jetty.webapp:context=jmx-webapp,type=webappcontext,id=0");

        String contextPath = getStringAttribute(webappName,"contextPath");
        String displayName = getStringAttribute(webappName,"displayName");

        assertThat("Context Path",contextPath,is("/jmx-webapp"));
        assertThat("Display Name",displayName,is("Test JMX WebApp"));
    }

    /**
     * Test for directly annotated POJOs in the JMX tree
     */
    @Test
    public void testAccessToCommonComponent() throws Exception
    {
        ObjectName commonName = new ObjectName("org.eclipse.jetty.test.jmx:type=commoncomponent,context=jmx-webapp,id=0");
        String name = getStringAttribute(commonName,"name");
        assertThat("Name",name,is("i am common"));
    }

    /**
     * Test for POJO (not annotated) that is supplemented with a MBean that
     * declares the annotations.
     */
    @Test
    public void testAccessToPingerMBean() throws Exception
    {
        ObjectName pingerName = new ObjectName("org.eclipse.jetty.test.jmx:type=pinger,context=jmx-webapp,id=0");
        // Get initial count
        int count = getIntegerAttribute(pingerName,"count");
        // Operations
        Object val = mbsc.invoke(pingerName,"ping",null,null);
        assertThat("ping() return",val.toString(),startsWith("Pong"));
        // Attributes
        assertThat("count",getIntegerAttribute(pingerName,"count"),is(count+1));
    }
    
    /**
     * Test for POJO (annotated) that is merged with a MBean that
     * declares more annotations.
     */
    @Test
    public void testAccessToEchoerMBean() throws Exception
    {
        ObjectName echoerName = new ObjectName("org.eclipse.jetty.test.jmx:type=echoer,context=jmx-webapp,id=0");
        // Get initial count
        int count = getIntegerAttribute(echoerName,"count");
        // Operations
        Object val = mbsc.invoke(echoerName,"echo",new Object[]{"Its Me"},new String[]{String.class.getName()});
        assertThat("echo() return",val.toString(),is("Its Me"));
        // Attributes
        assertThat("count",getIntegerAttribute(echoerName,"count"),is(count+1));
        assertThat("foo",getStringAttribute(echoerName,"foo"),is("foo-ish"));
    }
}
