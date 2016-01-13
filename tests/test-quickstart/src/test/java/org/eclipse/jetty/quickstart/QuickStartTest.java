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

package org.eclipse.jetty.quickstart;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebDescriptor;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.eclipse.jetty.xml.XmlParser.Node;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

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
        assertThat(node,Matchers.notNullValue());
        
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

        URL url = new URL("http://127.0.0.1:"+server.getBean(NetworkConnector.class).getLocalPort()+"/test/dump/info");
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        assertEquals(200,connection.getResponseCode());
        assertThat(IO.toString((InputStream)connection.getContent()),Matchers.containsString("Dump Servlet"));
      
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
        assertThat(node,Matchers.notNullValue());
        
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

        URL url = new URL("http://127.0.0.1:"+server.getBean(NetworkConnector.class).getLocalPort()+"/");
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        assertEquals(200,connection.getResponseCode());
        assertThat(IO.toString((InputStream)connection.getContent()),Matchers.containsString("Test Specification WebApp"));
      
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
        assertThat(node,Matchers.notNullValue());
        
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
            XmlConfiguration xmlConfiguration = new XmlConfiguration(contextXml.getURL());  
            xmlConfiguration.configure(webapp);   
        }
        
        server.setHandler(webapp);

        server.start();

        URL url = new URL("http://127.0.0.1:"+server.getBean(NetworkConnector.class).getLocalPort()+"/");
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        assertEquals(200,connection.getResponseCode());
        String content=IO.toString((InputStream)connection.getContent());
        assertThat(content,Matchers.containsString("JNDI Test WebApp"));
      
        server.stop();
    }
    
    @Test
    public void testNormalize() throws Exception
    {
        String jetty_base=System.getProperty("jetty.base");
        String jetty_home=System.getProperty("jetty.home");
        String user_home=System.getProperty("user.home");
        String user_dir=System.getProperty("user.dir");
        try
        {
            System.setProperty("jetty.home","/opt/jetty-distro");
            System.setProperty("jetty.base","/opt/jetty-distro/demo.base");
            System.setProperty("user.home","/home/user");
            System.setProperty("user.dir","/etc/init.d");
            AttributeNormalizer normalizer = new AttributeNormalizer(Resource.newResource("/opt/jetty-distro/demo.base/webapps/root"));

            String[][] tests = { 
                    { "WAR", "/opt/jetty-distro/demo.base/webapps/root" },
                    { "jetty.home", "/opt/jetty-distro" },
                    { "jetty.base", "/opt/jetty-distro/demo.base" },
                    { "user.home", "/home/user" },
                    { "user.dir", "/etc/init.d" },
            };

            for (String[] test : tests)
            {
                Assert.assertEquals("file:${"+test[0]+"}",normalizer.normalize("file:"+test[1]));
                Assert.assertEquals("file:${"+test[0]+"}",normalizer.normalize("file:"+test[1]+"/"));
                Assert.assertEquals("file:${"+test[0]+"}/file",normalizer.normalize("file:"+test[1]+"/file"));
                Assert.assertEquals("file:${"+test[0]+"}",normalizer.normalize(new URI("file:"+test[1])));
                Assert.assertEquals("file:${"+test[0]+"}",normalizer.normalize(new URI("file:"+test[1]+"/")));
                Assert.assertEquals("file:${"+test[0]+"}/file",normalizer.normalize(new URI("file:"+test[1]+"/file")));
                Assert.assertEquals("file:${"+test[0]+"}",normalizer.normalize(new URL("file:"+test[1])));
                Assert.assertEquals("file:${"+test[0]+"}",normalizer.normalize(new URL("file:"+test[1]+"/")));
                Assert.assertEquals("file:${"+test[0]+"}/file",normalizer.normalize(new URL("file:"+test[1]+"/file")));
                Assert.assertEquals("jar:file:${"+test[0]+"}!/file",normalizer.normalize("jar:file:"+test[1]+"!/file"));
                Assert.assertEquals("jar:file:${"+test[0]+"}!/file",normalizer.normalize("jar:file:"+test[1]+"/!/file"));
                Assert.assertEquals("jar:file:${"+test[0]+"}/file!/file",normalizer.normalize("jar:file:"+test[1]+"/file!/file"));
                Assert.assertEquals("jar:file:${"+test[0]+"}!/file",normalizer.normalize(new URI("jar:file:"+test[1]+"!/file")));
                Assert.assertEquals("jar:file:${"+test[0]+"}!/file",normalizer.normalize(new URI("jar:file:"+test[1]+"/!/file")));
                Assert.assertEquals("jar:file:${"+test[0]+"}/file!/file",normalizer.normalize(new URI("jar:file:"+test[1]+"/file!/file")));
                Assert.assertEquals("jar:file:${"+test[0]+"}!/file",normalizer.normalize(new URL("jar:file:"+test[1]+"!/file")));
                Assert.assertEquals("jar:file:${"+test[0]+"}!/file",normalizer.normalize(new URL("jar:file:"+test[1]+"/!/file")));
                Assert.assertEquals("jar:file:${"+test[0]+"}/file!/file",normalizer.normalize(new URL("jar:file:"+test[1]+"/file!/file")));
            }
        }
        finally
        {
            if (user_dir==null)
                System.clearProperty("user.dir");
            else
                System.setProperty("user.dir",user_dir);

            if (user_home==null)
                System.clearProperty("user.home");
            else
                System.setProperty("user.home",user_home);

            if (jetty_home==null)
                System.clearProperty("jetty.home");
            else
                System.setProperty("jetty.home",jetty_home);

            if (jetty_base==null)
                System.clearProperty("jetty.base");
            else
                System.setProperty("jetty.base",jetty_base);
        }
    }
}
