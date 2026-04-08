//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee11.test.jmx;

import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Optional;
import java.util.TreeSet;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.eclipse.jetty.ee11.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee11.webapp.JmxConfiguration;
import org.eclipse.jetty.ee11.webapp.WebAppContext;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.jmx.ConnectorServer;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

public class JmxIT
{
    private Server _server;
    private JMXConnector _jmxc;
    private MBeanServerConnection _mbsc;
    private int _httpPort;

    @BeforeEach
    public void prepare() throws Exception
    {
        Path target = MavenPaths.targetDir();
        // JETTY_BASE prepared in the pom.xml file,
        // although this test runs an embedded server.
        Path jettyBase = target.resolve("test-base");
        Path webapps = jettyBase.resolve("webapps");
        Path war = webapps.resolve("jetty-ee11-jmx-webapp.war");

        _server = new Server(0);
        _server.setName("server");

        WebAppContext context = new WebAppContext();
        context.addConfiguration(new AnnotationConfiguration());
        // Allow web applications to see jetty-util
        // and jetty-jmx classes from the server.
        context.addConfiguration(new JmxConfiguration());
        context.getHiddenClassMatcher().exclude("org.eclipse.jetty.util.");
        context.setWar(war.toString());
        context.setContextPath("/jmx-webapp");
        context.setAttribute("org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern",
            ".*/jetty-jakarta-servlet-api-[^/]*\\.jar$");
        _server.setHandler(context);

        MBeanContainer mbContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        _server.addBean(mbContainer);

        ServerSocket serverSocket = new ServerSocket(0);
        int jmxPort = serverSocket.getLocalPort();
        serverSocket.close();

        JMXServiceURL jmxURL = new JMXServiceURL("rmi", null, jmxPort, "/jndi/rmi://localhost:" + jmxPort + "/jmxrmi");
        ConnectorServer jmxConnServer = new ConnectorServer(jmxURL, "org.eclipse.jetty.jmx:name=rmiconnectorserver");
        _server.addBean(jmxConnServer);

        _server.start();
        _httpPort = ((NetworkConnector)_server.getConnectors()[0]).getLocalPort();
        _jmxc = JMXConnectorFactory.connect(jmxURL);
        _mbsc = _jmxc.getMBeanServerConnection();
    }

    @AfterEach
    public void dispose()
    {
        IO.close(_jmxc);
        LifeCycle.stop(_server);
    }

    @Test
    public void testBasic() throws Exception
    {
        URI serverURI = new URI("http://localhost:" + _httpPort + "/jmx-webapp/");
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(serverURI.resolve("ping")).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode(), is(HttpStatus.OK_200));
        assertThat(response.body(), startsWith("Servlet Pong at "));
    }

    @Test
    public void testObtainRunningServerVersion() throws Exception
    {
        ObjectName serverName = new ObjectName("org.eclipse.jetty.server:type=server,*");
        String version = getAttribute(serverName, "version");
        assertThat(version, equalTo(Server.getVersion()));
    }

    @Test
    public void testObtainJmxWebAppState() throws Exception
    {
        ObjectName webappName = new ObjectName("org.eclipse.jetty.ee11.webapp:type=webappcontext,*");

        String contextPath = getAttribute(webappName, "contextPath");
        assertThat(contextPath, is("/jmx-webapp"));

        String displayName = getAttribute(webappName, "displayName");
        assertThat(displayName, is("Test JMX WebApp"));
    }

    /**
     * Test for directly annotated POJOs in the JMX tree
     */
    @Test
    public void testAccessToCommonComponent() throws Exception
    {
        ObjectName commonName = new ObjectName("org.eclipse.jetty.ee11.test.jmx:type=commoncomponent,*");
        String name = getAttribute(commonName, "name");
        assertThat(name, is("i am common"));
    }

    /**
     * Test for POJO (not annotated) that is supplemented with a MBean that
     * declares the annotations.
     */
    @Test
    public void testAccessToPingerMBean() throws Exception
    {
        ObjectName pingerName = new ObjectName("org.eclipse.jetty.ee11.test.jmx:type=pinger,*");
        // Get initial count
        int count = getAttribute(pingerName, "count");
        // Operations
        Object val = invoke(pingerName, "ping", null, null);
        assertThat(val.toString(), startsWith("Pong"));
        // Attributes
        assertThat(getAttribute(pingerName, "count"), is(count + 1));
    }

    /**
     * Test for POJO (annotated) that is merged with a MBean that
     * declares more annotations.
     */
    @Test
    public void testAccessToEchoerMBean() throws Exception
    {
        ObjectName echoerName = new ObjectName("org.eclipse.jetty.ee11.test.jmx:type=echoer,*");
        // Get initial count
        int count = getAttribute(echoerName, "count");
        // Operations
        Object val = invoke(echoerName, "echo", new Object[]{"Its Me"}, new String[]{String.class.getName()});
        assertThat(val.toString(), is("Its Me"));
        // Attributes
        assertThat(getAttribute(echoerName, "count"), is(count + 1));
        assertThat(getAttribute(echoerName, "foo"), is("foo-ish"));
    }

    private <T> T getAttribute(ObjectName objName, String attrName) throws Exception
    {
        Optional<ObjectName> objNameOpt = _mbsc.queryNames(objName, null).stream().findFirst();
        if (objNameOpt.isEmpty())
            throw new InstanceNotFoundException("%s not found among %s".formatted(objName, new TreeSet<>(_mbsc.queryNames(null, null))));
        Object val = _mbsc.getAttribute(objNameOpt.get(), attrName);
        assertThat(attrName, val, notNullValue());
        return (T)val;
    }

    private Object invoke(ObjectName objName, String operation, Object[] args, String[] params) throws Exception
    {
        Optional<ObjectName> objNameOpt = _mbsc.queryNames(objName, null).stream().findFirst();
        if (objNameOpt.isEmpty())
            throw new InstanceNotFoundException("%s not found among %s".formatted(objName, new TreeSet<>(_mbsc.queryNames(null, null))));
        return _mbsc.invoke(objNameOpt.get(), operation, args, params);
    }
}
