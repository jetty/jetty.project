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

package org.eclipse.jetty.jstl;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import javax.servlet.jsp.JspException;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.JAR;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

public class JstlTest
{
    private static Server server;
    private static URI baseUri;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        // Setup Server
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
        
        // Setup WebAppContext
        File testWebAppDir = MavenTestingUtils.getProjectDir("src/test/webapp");
        
        // Prepare WebApp libs
        File libDir = new File(testWebAppDir, "WEB-INF/lib");
        FS.ensureDirExists(libDir);
        File testTagLibDir = MavenTestingUtils.getProjectDir("src/test/taglibjar");
        JAR.create(testTagLibDir,new File(libDir, "testtaglib.jar"));
        
        // Configure WebAppContext
 
        Configuration.ClassList classlist = Configuration.ClassList
                .setServerDefault(server);

        classlist.addBefore(
                "org.eclipse.jetty.webapp.JettyWebXmlConfiguration",
                "org.eclipse.jetty.annotations.AnnotationConfiguration");
        
        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        
        File scratchDir = MavenTestingUtils.getTargetFile("tests/" + JstlTest.class.getSimpleName() + "-scratch");
        FS.ensureEmpty(scratchDir);
        JspConfig.init(context,testWebAppDir.toURI(),scratchDir);
        
        server.setHandler(context);
        
        // Start Server
        server.start();
        
        // Figure out Base URI
        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        baseUri = new URI(String.format("http://%s:%d/",host,port));
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        if (server != null)
            server.stop();
    }
    
    @Test
    public void testUrlsBasic() throws IOException
    {
        HttpURLConnection http = (HttpURLConnection) baseUri.resolve("/urls.jsp").toURL().openConnection();
        assertThat("http response", http.getResponseCode(), is(200));
        try(InputStream input = http.getInputStream())
        {
            String resp = IO.toString(input, StandardCharsets.UTF_8);
            assertThat("Response should be JSP processed", resp, not(containsString("<c:url")));
            assertThat("Response", resp, containsString("[c:url value] = /ref.jsp;jsessionid="));
            assertThat("Response", resp, containsString("[c:url param] = ref.jsp;key=value;jsessionid="));
        }
    }
    
    @Test
    public void testCatchBasic() throws IOException
    {
        HttpURLConnection http = (HttpURLConnection) baseUri.resolve("/catch-basic.jsp").toURL().openConnection();
        assertThat("http response", http.getResponseCode(), is(200));
        try(InputStream input = http.getInputStream())
        {
            String resp = IO.toString(input, StandardCharsets.UTF_8);
            assertThat("Response should be JSP processed", resp, not(containsString("<c:catch")));
            assertThat("Response", resp, containsString("[c:catch] exception : " + JspException.class.getName()));
            assertThat("Response", resp, containsString("[c:catch] exception.message : In &lt;parseNumber&gt;"));
        }
    }
    
    @Test
    public void testCatchTaglib() throws IOException
    {
        HttpURLConnection http = (HttpURLConnection) baseUri.resolve("/catch-taglib.jsp").toURL().openConnection();
        assertThat("http response", http.getResponseCode(), is(200));
        try(InputStream input = http.getInputStream())
        {
            String resp = IO.toString(input, StandardCharsets.UTF_8);
            assertThat("Response should be JSP processed", resp, not(containsString("<c:catch>")));
            assertThat("Response should be JSP processed", resp, not(containsString("<jtest:errorhandler>")));
            assertThat("Response", resp, not(containsString("[jtest:errorhandler] exception is null")));
        }
    }
}
