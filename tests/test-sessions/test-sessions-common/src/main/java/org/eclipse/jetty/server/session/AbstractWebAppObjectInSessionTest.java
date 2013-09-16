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

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.util.Random;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * AbstractWebAppObjectInSessionTest
 *
 * Target of this test is to check that when a webapp on nodeA puts in the session
 * an object of a class loaded from the war (and hence with a WebAppClassLoader),
 * the same webapp on nodeB is able to load that object from the session.
 *
 * This test is only appropriate for clustered session managers.
 */
public abstract class AbstractWebAppObjectInSessionTest
{
    public abstract AbstractTestServer createServer(int port);

    @Test
    public void testWebappObjectInSession() throws Exception
    {
        String contextName = "webappObjectInSessionTest";
        String contextPath = "/" + contextName;
        String servletMapping = "/server";

        File targetDir = new File(System.getProperty("basedir"), "target");
        File warDir = new File(targetDir, contextName);
        warDir.mkdir();
        File webInfDir = new File(warDir, "WEB-INF");
        webInfDir.mkdir();
        // Write web.xml
        File webXml = new File(webInfDir, "web.xml");
        String xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://java.sun.com/xml/ns/j2ee\"\n" +
                "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "         xsi:schemaLocation=\"http://java.sun.com/xml/ns/j2ee http://java.sun.com/xml/ns/j2ee/web-app_2_4.xsd\"\n" +
                "         version=\"2.4\">\n" +
                "\n" +
                "</web-app>";
        FileWriter w = new FileWriter(webXml);
        w.write(xml);
        w.close();
        File classesDir = new File(webInfDir, "classes");
        classesDir.mkdir();
        String packageName = WebAppObjectInSessionServlet.class.getPackage().getName();
        File packageDirs = new File(classesDir, packageName.replace('.', File.separatorChar));
        packageDirs.mkdirs();

        String resourceName = WebAppObjectInSessionServlet.class.getSimpleName() + ".class";
        Resource resource = Resource.newResource(getClass().getResource(resourceName));

        //File sourceFile = new File(getClass().getClassLoader().getResource(resourceName).toURI());
        File targetFile = new File(packageDirs, resourceName);
        //copy(sourceFile, targetFile);
        IO.copy(resource.getInputStream(), new FileOutputStream(targetFile));

        resourceName = WebAppObjectInSessionServlet.class.getSimpleName() + "$" + WebAppObjectInSessionServlet.TestSharedStatic.class.getSimpleName() + ".class";
        resource = Resource.newResource(getClass().getResource(resourceName));
        //sourceFile = new File(getClass().getClassLoader().getResource(resourceName).toURI());
        targetFile = new File(packageDirs, resourceName);
        //copy(sourceFile, targetFile);
        IO.copy(resource.getInputStream(), new FileOutputStream(targetFile));

        AbstractTestServer server1 = createServer(0);
        server1.addWebAppContext(warDir.getCanonicalPath(), contextPath).addServlet(WebAppObjectInSessionServlet.class.getName(), servletMapping);
        server1.start();
        int port1 = server1.getPort();
        try
        {
            AbstractTestServer server2 = createServer(0);
            server2.addWebAppContext(warDir.getCanonicalPath(), contextPath).addServlet(WebAppObjectInSessionServlet.class.getName(), servletMapping);
            server2.start();
            int port2 = server2.getPort();
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
                    exchange1.setURL("http://localhost:" + port1 + contextPath + servletMapping + "?action=set");
                    client.send(exchange1);
                    exchange1.waitForDone();
                    assertEquals( HttpServletResponse.SC_OK, exchange1.getResponseStatus());
                    String sessionCookie = exchange1.getResponseFields().getStringField("Set-Cookie");
                    assertTrue(sessionCookie != null);
                    // Mangle the cookie, replacing Path with $Path, etc.
                    sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                    // Perform a request to server2 using the session cookie from the previous request
                    ContentExchange exchange2 = new ContentExchange(true);
                    exchange2.setMethod(HttpMethods.GET);
                    exchange2.setURL("http://localhost:" + port2 + contextPath + servletMapping + "?action=get");
                    exchange2.getRequestFields().add("Cookie", sessionCookie);
                    client.send(exchange2);
                    exchange2.waitForDone();

                    assertEquals(HttpServletResponse.SC_OK,exchange2.getResponseStatus());
                }
                finally
                {
                    client.stop();
                }
            }
            finally
            {
                server2.stop();
            }
        }
        finally
        {
            server1.stop();
        }
    }
}
