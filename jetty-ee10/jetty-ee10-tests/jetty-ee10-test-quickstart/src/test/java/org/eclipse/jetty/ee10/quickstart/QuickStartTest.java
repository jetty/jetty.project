//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.quickstart;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.ee10.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee10.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.ee10.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.ee10.webapp.WebDescriptor;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.eclipse.jetty.xml.XmlParser.Node;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class QuickStartTest
{

    @Test
    public void testStandardTestWar() throws Exception
    {
        WebAppContext webapp = new WebAppContext();

        //Generate the quickstart
        PreconfigureStandardTestWar.main(new String[]{});

        Path workDir = MavenTestingUtils.getTargetTestingPath(PreconfigureStandardTestWar.class.getSimpleName());
        Path targetDir = workDir.resolve("test-standard-preconfigured");

        Path webXmlPath = targetDir.resolve("WEB-INF/quickstart-web.xml");
        WebDescriptor descriptor = new WebDescriptor(webapp.getResourceFactory().newResource(webXmlPath));
        descriptor.parse(WebDescriptor.getParser(!QuickStartGeneratorConfiguration.LOG.isDebugEnabled()));
        Node node = descriptor.getRoot();
        assertNotNull(node);

        System.setProperty("jetty.home", targetDir.toString());

        //war file or dir to start
        String war = targetDir.toString();

        //optional jetty context xml file to configure the webapp
        Path testResourceXml = MavenTestingUtils.getTestResourcePathFile("test.xml");
        Resource contextXml = webapp.getResourceFactory().newResource(testResourceXml);

        Server server = new Server(0);

        webapp.addConfiguration(new QuickStartConfiguration(),
            new EnvConfiguration(),
            new PlusConfiguration(),
            new AnnotationConfiguration());
        webapp.setAttribute(QuickStartConfiguration.MODE, QuickStartConfiguration.Mode.QUICKSTART);
        webapp.setWar(war);
        webapp.setContextPath("/");

        //apply context xml file
        XmlConfiguration xmlConfiguration = new XmlConfiguration(contextXml);
        xmlConfiguration.configure(webapp);

        server.setHandler(webapp);

        server.start();

        URL url = new URL("http://127.0.0.1:" + server.getBean(NetworkConnector.class).getLocalPort() + "/test/dump/info");
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        assertEquals(200, connection.getResponseCode());
        assertThat(IO.toString((InputStream)connection.getContent()), Matchers.containsString("Dump Servlet"));

        server.stop();
    }

    @Test
    public void testSpecWar() throws Exception
    {
        WebAppContext webapp = new WebAppContext();

        //Generate the quickstart xml
        PreconfigureSpecWar.main(new String[]{});

        Path workDir = MavenTestingUtils.getTargetTestingPath(PreconfigureSpecWar.class.getSimpleName());
        Path targetDir = workDir.resolve("test-spec-preconfigured");

        Path webXmlPath = targetDir.resolve("WEB-INF/quickstart-web.xml");
        assertTrue(Files.exists(webXmlPath), "Path should exist:" + webXmlPath);

        WebDescriptor descriptor = new WebDescriptor(webapp.getResourceFactory().newResource(webXmlPath));
        descriptor.parse(WebDescriptor.getParser(!QuickStartGeneratorConfiguration.LOG.isDebugEnabled()));
        Node node = descriptor.getRoot();
        assertNotNull(node);

        System.setProperty("jetty.home", targetDir.toString());

        // war file or dir to start
        String war = targetDir.toString();

        //optional jetty context xml file to configure the webapp
        Path testResourceSpec = MavenTestingUtils.getTestResourcePathFile("test-spec.xml");
        Resource contextXml = webapp.getResourceFactory().newResource(testResourceSpec);

        Server server = new Server(0);

        webapp.addConfiguration(new QuickStartConfiguration(),
            new EnvConfiguration(),
            new PlusConfiguration(),
            new AnnotationConfiguration());
        webapp.setAttribute(QuickStartConfiguration.MODE, QuickStartConfiguration.Mode.QUICKSTART);
        webapp.setWar(war);
        webapp.setContextPath("/");

        //apply context xml file
        // System.err.println("Applying "+contextXml);
        XmlConfiguration xmlConfiguration = new XmlConfiguration(contextXml);
        xmlConfiguration.configure(webapp);

        server.setHandler(webapp);
        server.start();

        URL url = new URL("http://127.0.0.1:" + server.getBean(NetworkConnector.class).getLocalPort() + "/test/");
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        connection.setRequestMethod("POST");
        
        assertEquals(200, connection.getResponseCode());
        String content = IO.toString((InputStream)connection.getContent());
        assertThat(content, Matchers.containsString("Results"));
        assertThat(content, Matchers.not(Matchers.containsString("FAIL")));
        server.stop();
    }

    @Disabled //TODO needs DefaultServlet
    @Test
    public void testJNDIWar() throws Exception
    {
        WebAppContext webapp = new WebAppContext();

        //Generate the quickstart
        PreconfigureJNDIWar.main(new String[]{});

        Path workDir = MavenTestingUtils.getTargetTestingPath(PreconfigureJNDIWar.class.getSimpleName());
        Path targetDir = workDir.resolve("test-jndi-preconfigured");

        Path webXmlPath = targetDir.resolve("WEB-INF/quickstart-web.xml");
        WebDescriptor descriptor = new WebDescriptor(webapp.getResourceFactory().newResource(webXmlPath));
        descriptor.parse(WebDescriptor.getParser(!QuickStartGeneratorConfiguration.LOG.isDebugEnabled()));
        Node node = descriptor.getRoot();
        assertNotNull(node);

        System.setProperty("jetty.home", targetDir.toString());

        //war file or dir to start
        String war = targetDir.toString();

        //optional jetty context xml file to configure the webapp
        Path testResourceJndi = MavenTestingUtils.getTestResourcePathFile("test-jndi.xml");
        Resource contextXml = webapp.getResourceFactory().newResource(testResourceJndi);

        Server server = new Server(0);

        webapp.addConfiguration(new QuickStartConfiguration(),
            new EnvConfiguration(),
            new PlusConfiguration(),
            new AnnotationConfiguration());
        webapp.setAttribute(QuickStartConfiguration.MODE, QuickStartConfiguration.Mode.QUICKSTART);
        webapp.setWar(war);
        webapp.setContextPath("/");

        //apply context xml file
        // System.err.println("Applying "+contextXml);
        XmlConfiguration xmlConfiguration = new XmlConfiguration(contextXml);
        xmlConfiguration.configure(webapp);

        server.setHandler(webapp);

        server.start();

        URL url = new URL("http://127.0.0.1:" + server.getBean(NetworkConnector.class).getLocalPort() + "/");
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        String content = IO.toString((InputStream)connection.getContent());
        assertEquals(200, connection.getResponseCode());
        assertThat(content, Matchers.containsString("JNDI Demo WebApp"));

        server.stop();
    }
}
