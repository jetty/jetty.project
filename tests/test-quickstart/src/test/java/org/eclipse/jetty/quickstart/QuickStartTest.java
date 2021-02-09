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

package org.eclipse.jetty.quickstart;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebDescriptor;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.eclipse.jetty.xml.XmlParser.Node;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class QuickStartTest
{

    @Test
    public void testStandardTestWar() throws Exception
    {
        PreconfigureStandardTestWar.main(new String[]{});

        WebDescriptor descriptor = new WebDescriptor(Resource.newResource("./target/test-standard-preconfigured/WEB-INF/quickstart-web.xml"));
        descriptor.setValidating(true);
        descriptor.parse();
        Node node = descriptor.getRoot();
        assertThat(node, Matchers.notNullValue());

        System.setProperty("jetty.home", "target");

        //war file or dir to start
        String war = "target/test-standard-preconfigured";

        //optional jetty context xml file to configure the webapp
        Resource contextXml = Resource.newResource("src/test/resources/test.xml");

        Server server = new Server(0);

        QuickStartWebApp webapp = new QuickStartWebApp();
        webapp.setAutoPreconfigure(true);
        webapp.setWar(war);
        webapp.setContextPath("/");

        //apply context xml file
        if (contextXml != null)
        {
            // System.err.println("Applying "+contextXml);
            XmlConfiguration xmlConfiguration = new XmlConfiguration(contextXml.getURL());
            xmlConfiguration.configure(webapp);
        }

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
        PreconfigureSpecWar.main(new String[]{});

        WebDescriptor descriptor = new WebDescriptor(Resource.newResource("./target/test-spec-preconfigured/WEB-INF/quickstart-web.xml"));
        descriptor.setValidating(true);
        descriptor.parse();
        Node node = descriptor.getRoot();
        assertThat(node, Matchers.notNullValue());

        System.setProperty("jetty.home", "target");

        //war file or dir to start
        String war = "target/test-spec-preconfigured";

        //optional jetty context xml file to configure the webapp
        Resource contextXml = Resource.newResource("src/test/resources/test-spec.xml");

        Server server = new Server(0);

        QuickStartWebApp webapp = new QuickStartWebApp();
        webapp.setAutoPreconfigure(true);
        webapp.setWar(war);
        webapp.setContextPath("/");

        //apply context xml file
        if (contextXml != null)
        {
            // System.err.println("Applying "+contextXml);
            XmlConfiguration xmlConfiguration = new XmlConfiguration(contextXml.getURL());
            xmlConfiguration.configure(webapp);
        }

        server.setHandler(webapp);

        server.start();

        URL url = new URL("http://127.0.0.1:" + server.getBean(NetworkConnector.class).getLocalPort() + "/");
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        assertEquals(200, connection.getResponseCode());
        assertThat(IO.toString((InputStream)connection.getContent()), Matchers.containsString("Test Specification WebApp"));

        server.stop();
    }

    @Test
    public void testJNDIWar() throws Exception
    {
        PreconfigureJNDIWar.main(new String[]{});

        WebDescriptor descriptor = new WebDescriptor(Resource.newResource("./target/test-jndi-preconfigured/WEB-INF/quickstart-web.xml"));
        descriptor.setValidating(true);
        descriptor.parse();
        Node node = descriptor.getRoot();
        assertThat(node, Matchers.notNullValue());

        System.setProperty("jetty.home", "target");

        //war file or dir to start
        String war = "target/test-jndi-preconfigured";

        //optional jetty context xml file to configure the webapp
        Resource contextXml = Resource.newResource("src/test/resources/test-jndi.xml");

        Server server = new Server(0);

        QuickStartWebApp webapp = new QuickStartWebApp();
        webapp.setAutoPreconfigure(true);
        webapp.setWar(war);
        webapp.setContextPath("/");

        //apply context xml file
        if (contextXml != null)
        {
            // System.err.println("Applying "+contextXml);
            XmlConfiguration xmlConfiguration = new XmlConfiguration(contextXml.getURI().toURL());
            xmlConfiguration.configure(webapp);
        }

        server.setHandler(webapp);

        server.start();

        URL url = new URL("http://127.0.0.1:" + server.getBean(NetworkConnector.class).getLocalPort() + "/");
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        assertEquals(200, connection.getResponseCode());
        String content = IO.toString((InputStream)connection.getContent());
        assertThat(content, Matchers.containsString("JNDI Test WebApp"));

        server.stop();
    }
}
