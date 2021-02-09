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

package org.eclipse.jetty.test.jmx;

import java.io.File;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.eclipse.jetty.jmx.ConnectorServer;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@Disabled
public class JmxIT
{
    private Server _server;
    private JMXConnector _jmxc;
    private MBeanServerConnection _mbsc;
    private int _httpPort;
    private JMXServiceURL _jmxURL;

    @BeforeEach
    public void connectToMBeanServer() throws Exception
    {
        startJetty();

        new CountDownLatch(1).await();

        _jmxc = JMXConnectorFactory.connect(_jmxURL);
        _mbsc = _jmxc.getMBeanServerConnection();
    }

    @AfterEach
    public void disconnectFromMBeanServer() throws Exception
    {
        _jmxc.close();
        stopJetty();
    }

    public void startJetty() throws Exception
    {
        File target = MavenTestingUtils.getTargetDir();
        File jettyBase = new File(target, "test-base");
        File webapps = new File(jettyBase, "webapps");
        File war = new File(webapps, "jmx-webapp.war");

        _server = new Server(0);

        WebAppContext context = new WebAppContext();
        context.setWar(war.getCanonicalPath());
        context.setContextPath("/jmx-webapp");
        Configuration.ClassList classlist = Configuration.ClassList
            .setServerDefault(_server);
        classlist.addBefore("org.eclipse.jetty.webapp.JettyWebXmlConfiguration",
            "org.eclipse.jetty.annotations.AnnotationConfiguration");
        context.setAttribute("org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern",
            ".*/javax.servlet-[^/]*\\.jar$|.*/servlet-api-[^/]*\\.jar$");
        _server.setHandler(context);

        MBeanContainer mbContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        _server.addBean(mbContainer);

        ServerSocket serverSocket = new ServerSocket(0);
        int jmxPort = serverSocket.getLocalPort();
        serverSocket.close();

        _jmxURL = new JMXServiceURL("rmi", "0.0.0.0", jmxPort, "/jndi/rmi://0.0.0.0:" + jmxPort + "/jmxrmi");
        ConnectorServer jmxConnServer = new ConnectorServer(_jmxURL, "org.eclipse.jetty.jmx:name=rmiconnectorserver");
        _server.addBean(jmxConnServer);

        _server.start();
        _httpPort = ((NetworkConnector)_server.getConnectors()[0]).getLocalPort();
    }

    public void stopJetty() throws Exception
    {
        if (_server != null)
            _server.stop();
    }

    private String getStringAttribute(ObjectName objName, String attrName) throws Exception
    {
        Object val = _mbsc.getAttribute(objName, attrName);
        assertThat(attrName, val, notNullValue());
        assertThat(attrName, val, instanceOf(String.class));
        return (String)val;
    }

    private int getIntegerAttribute(ObjectName objName, String attrName) throws Exception
    {
        Object val = _mbsc.getAttribute(objName, attrName);
        assertThat(attrName, val, notNullValue());
        assertThat(attrName, val, instanceOf(Integer.class));
        return (Integer)val;
    }

    @Test
    public void testBasic() throws Exception
    {
        URI serverURI = new URI("http://localhost:" + String.valueOf(_httpPort) + "/jmx-webapp/");
        HttpURLConnection http = (HttpURLConnection)serverURI.resolve("ping").toURL().openConnection();
        try (InputStream inputStream = http.getInputStream())
        {
            assertThat("http response", http.getResponseCode(), is(200));
            String resp = IO.toString(inputStream);
            assertThat(resp, startsWith("Servlet Pong at "));
        }
    }

    @Test
    public void testObtainRunningServerVersion() throws Exception
    {
        ObjectName serverName = new ObjectName("org.eclipse.jetty.server:type=server,id=0");
        String version = getStringAttribute(serverName, "version");
        assertThat("Version", version, startsWith("9.4."));
    }

    @Test
    public void testObtainJmxWebAppState() throws Exception
    {
        ObjectName webappName = new ObjectName("org.eclipse.jetty.webapp:context=jmx-webapp,type=webappcontext,id=0");

        String contextPath = getStringAttribute(webappName, "contextPath");
        assertThat("Context Path", contextPath, is("/jmx-webapp"));

        String displayName = getStringAttribute(webappName, "displayName");
        assertThat("Display Name", displayName, is("Test JMX WebApp"));
    }

    /**
     * Test for directly annotated POJOs in the JMX tree
     */
    @Test
    public void testAccessToCommonComponent() throws Exception
    {
        ObjectName commonName = new ObjectName("org.eclipse.jetty.test.jmx:type=commoncomponent,context=jmx-webapp,id=0");
        String name = getStringAttribute(commonName, "name");
        assertThat("Name", name, is("i am common"));
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
        int count = getIntegerAttribute(pingerName, "count");
        // Operations
        Object val = _mbsc.invoke(pingerName, "ping", null, null);
        assertThat("ping() return", val.toString(), startsWith("Pong"));
        // Attributes
        assertThat("count", getIntegerAttribute(pingerName, "count"), is(count + 1));
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
        int count = getIntegerAttribute(echoerName, "count");
        // Operations
        Object val = _mbsc.invoke(echoerName, "echo", new Object[]{"Its Me"}, new String[]{String.class.getName()});
        assertThat("echo() return", val.toString(), is("Its Me"));
        // Attributes
        assertThat("count", getIntegerAttribute(echoerName, "count"), is(count + 1));
        assertThat("foo", getStringAttribute(echoerName, "foo"), is("foo-ish"));
    }
}
