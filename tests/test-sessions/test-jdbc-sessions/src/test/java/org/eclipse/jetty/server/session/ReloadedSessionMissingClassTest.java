//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.net.URL;
import java.net.URLClassLoader;

import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.Test;

/**
 * ReloadedSessionMissingClassTest
 *
 *
 *
 */
public class ReloadedSessionMissingClassTest
{
    
    @Test
    public void testSessionReloadWithMissingClass() throws Exception
    {
        ((StdErrLog)Log.getLogger(org.eclipse.jetty.server.session.JDBCSessionManager.class)).setHideStacks(true);
        String contextPath = "/foo";
        File srcDir = new File(System.getProperty("basedir"), "src");
        File targetDir = new File(System.getProperty("basedir"), "target");
        File testDir = new File (srcDir, "test");
        File resourcesDir = new File (testDir, "resources");

        File unpackedWarDir = new File (targetDir, "foo");
        if (unpackedWarDir.exists())
            IO.delete(unpackedWarDir);
        unpackedWarDir.mkdir();

        File webInfDir = new File (unpackedWarDir, "WEB-INF");
        webInfDir.mkdir();

        File webXml = new File(webInfDir, "web.xml");
        String xml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<web-app xmlns=\"http://java.sun.com/xml/ns/j2ee\"\n" +
            "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
            "         xsi:schemaLocation=\"http://java.sun.com/xml/ns/j2ee http://java.sun.com/xml/ns/j2ee/web-app_2_4.xsd\"\n" +
            "         version=\"2.4\">\n" +
            "\n" +
            "<session-config>\n"+
            " <session-timeout>1</session-timeout>\n" +
            "</session-config>\n"+
            "</web-app>";
        FileWriter w = new FileWriter(webXml);
        w.write(xml);
        w.close();

        File foobarJar = new File (resourcesDir, "foobar.jar");
        File foobarNOfooJar = new File (resourcesDir, "foobarNOfoo.jar");
        
        URL[] foobarUrls = new URL[]{foobarJar.toURI().toURL()};
        URL[] barUrls = new URL[]{foobarNOfooJar.toURI().toURL()};
        
        URLClassLoader loaderWithFoo = new URLClassLoader(foobarUrls, Thread.currentThread().getContextClassLoader());
        URLClassLoader loaderWithoutFoo = new URLClassLoader(barUrls, Thread.currentThread().getContextClassLoader());

       
        AbstractTestServer server1 = new JdbcTestServer(0);
        WebAppContext webApp = server1.addWebAppContext(unpackedWarDir.getCanonicalPath(), contextPath);
        webApp.setClassLoader(loaderWithFoo);
        webApp.addServlet("Bar", "/bar");
        server1.start();
        int port1 = server1.getPort();
        try
        {
            HttpClient client = new HttpClient();
            client.setConnectorType(HttpClient.CONNECTOR_SOCKET);
            client.start();
            try
            {
                // Perform one request to server1 to create a session
                ContentExchange exchange1 = new ContentExchange(true);
                exchange1.setMethod(HttpMethods.GET);
                exchange1.setURL("http://localhost:" + port1 + contextPath +"/bar?action=set");
                client.send(exchange1);
                exchange1.waitForDone();
                assertEquals( HttpServletResponse.SC_OK, exchange1.getResponseStatus());
                String sessionCookie = exchange1.getResponseFields().getStringField("Set-Cookie");
                assertTrue(sessionCookie != null);
                String sessionId = (String)webApp.getServletContext().getAttribute("foo");
                assertNotNull(sessionId);
                // Mangle the cookie, replacing Path with $Path, etc.
                sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");
                
                //Stop the webapp
                webApp.stop();
                
                webApp.setClassLoader(loaderWithoutFoo);
                webApp.addServlet("Bar", "/bar");
                
                //restart webapp
                webApp.start();
                
                ContentExchange exchange2 = new ContentExchange(true);
                exchange2.setMethod(HttpMethods.GET);
                exchange2.setURL("http://localhost:" + port1 + contextPath + "/bar?action=get");
                exchange2.getRequestFields().add("Cookie", sessionCookie);
                client.send(exchange2);
                exchange2.waitForDone();
                assertEquals(HttpServletResponse.SC_OK,exchange2.getResponseStatus());
                String afterStopSessionId = (String)webApp.getServletContext().getAttribute("foo.session");
                
                assertNotNull(afterStopSessionId);
                assertTrue(!afterStopSessionId.equals(sessionId));  

            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            server1.stop();
        }
    }
}
