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

package org.eclipse.jetty.test.jmx;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URI;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.SimpleRequest;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.jmx.ConnectorServer;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.NetworkConnector;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Some JMX information tests.
 */
public class JmxIT
{
    private static JMXConnector __jmxc;
    private static MBeanServerConnection __mbsc;
    private static Server __server;
    private static int __port;

    @BeforeClass
    public static void connectToMBeanServer() throws Exception
    {
        startJetty();
        JMXServiceURL url = new JMXServiceURL("service:jmx:rmi://localhost:1099/jndi/rmi://localhost:1099/jmxrmi");
        __jmxc = JMXConnectorFactory.connect(url,null);
        __mbsc = __jmxc.getMBeanServerConnection();
    }

    @AfterClass
    public static void disconnectFromMBeanServer() throws Exception
    {
        stopJetty();
        __jmxc.close();
    }

    public static void startJetty() throws Exception
    {
        File target = MavenTestingUtils.getTargetDir();
        File jettyBase = new File (target, "test-base");
        File webapps = new File (jettyBase, "webapps");
        File war = new File (webapps, "jmx-webapp.war");

        //create server instance
        __server = new Server(0);
         
        //set up the webapp
        WebAppContext context = new WebAppContext();
        
        context.setWar(war.getCanonicalPath());
        context.setContextPath("/jmx-webapp");
        
        Configuration.ClassList classlist = Configuration.ClassList
                .setServerDefault(__server);
        classlist.addBefore("org.eclipse.jetty.webapp.JettyWebXmlConfiguration",
                "org.eclipse.jetty.annotations.AnnotationConfiguration");

        context.setAttribute(
                            "org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern",
                            ".*/javax.servlet-[^/]*\\.jar$|.*/servlet-api-[^/]*\\.jar$");
        __server.setHandler(context);
        
        //set up jmx remote
        MBeanContainer mbContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        __server.addBean(mbContainer);

        JMXServiceURL serviceUrl = new JMXServiceURL("rmi", "localhost", 1099, "/jndi/rmi://localhost:1099/jmxrmi");
        ConnectorServer jmxConnServer = new ConnectorServer(serviceUrl, "org.eclipse.jetty.jmx:name=rmiconnectorserver");
        __server.addBean(jmxConnServer);

        //start server
        __server.start();
        
        //remember chosen port
        __port = ((NetworkConnector)__server.getConnectors()[0]).getLocalPort();
    }
    
    
    public static void stopJetty () throws Exception
    {
        if (__server != null)
            __server.stop();
    }

    private String getStringAttribute(ObjectName objName, String attrName) throws Exception
    {
        Object val = __mbsc.getAttribute(objName,attrName);
        assertThat(attrName,val,notNullValue());
        assertThat(attrName,val,instanceOf(String.class));
        return (String)val;
    }

    private int getIntegerAttribute(ObjectName objName, String attrName) throws Exception
    {
        Object val = __mbsc.getAttribute(objName,attrName);
        assertThat(attrName,val,notNullValue());
        assertThat(attrName,val,instanceOf(Integer.class));
        return (Integer)val;
    }

    @Test
    public void testBasic() throws Exception
    {
        URI serverURI = new URI("http://localhost:"+String.valueOf(__port)+"/jmx-webapp/");
        SimpleRequest req = new SimpleRequest(serverURI);
        assertThat(req.getString("ping"),startsWith("Servlet Pong at "));
    }
    
    @Test
    public void testObtainRunningServerVersion() throws Exception
    {
        ObjectName serverName = new ObjectName("org.eclipse.jetty.server:type=server,id=0");
        String version = getStringAttribute(serverName,"version");
        assertThat("Version",version,startsWith("9.4."));
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
        Object val = __mbsc.invoke(pingerName,"ping",null,null);
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
        Object val = __mbsc.invoke(echoerName,"echo",new Object[]{"Its Me"},new String[]{String.class.getName()});
        assertThat("echo() return",val.toString(),is("Its Me"));
        // Attributes
        assertThat("count",getIntegerAttribute(echoerName,"count"),is(count+1));
        assertThat("foo",getStringAttribute(echoerName,"foo"),is("foo-ish"));
    }
}
