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

package org.eclipse.jetty.server.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;

import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.Test;

/**
 * AbstractWebAppObjectInSessionTest
 *
 * Target of this test is to check that when a webapp on nodeA puts in the session
 * an object of a class loaded from the war (and hence with a WebAppClassLoader),
 * the same webapp on nodeB is able to load that object from the session.
 *
 * This test is only appropriate for clustered session managers.
 */
public abstract class AbstractWebAppObjectInSessionTest extends AbstractTestBase
{
 
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

        AbstractTestServer server1 = createServer(0, AbstractTestServer.DEFAULT_MAX_INACTIVE,  AbstractTestServer.DEFAULT_SCAVENGE_SEC,  AbstractTestServer.DEFAULT_EVICTIONPOLICY);
        server1.addWebAppContext(warDir.getCanonicalPath(), contextPath).addServlet(WebAppObjectInSessionServlet.class.getName(), servletMapping);

        try
        {
            server1.start();
            int port1 = server1.getPort();
            
            AbstractTestServer server2 = createServer(0, AbstractTestServer.DEFAULT_MAX_INACTIVE,  AbstractTestServer.DEFAULT_SCAVENGE_SEC,  AbstractTestServer.DEFAULT_EVICTIONPOLICY);
            server2.addWebAppContext(warDir.getCanonicalPath(), contextPath).addServlet(WebAppObjectInSessionServlet.class.getName(), servletMapping);

            try
            {
                server2.start();
                int port2 = server2.getPort();
                
                HttpClient client = new HttpClient();
                client.start();
                try
                {
                    // Perform one request to server1 to create a session
                    Request request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=set");
                    request.method(HttpMethod.GET);

                    ContentResponse response = request.send();
                    assertEquals( HttpServletResponse.SC_OK, response.getStatus());
                    String sessionCookie = response.getHeaders().get("Set-Cookie");
                    assertTrue(sessionCookie != null);
                    // Mangle the cookie, replacing Path with $Path, etc.
                    sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                    // Perform a request to server2 using the session cookie from the previous request
                    Request request2 = client.newRequest("http://localhost:" + port2 + contextPath + servletMapping + "?action=get");
                    request2.method(HttpMethod.GET);
                    request2.header("Cookie", sessionCookie);
                    ContentResponse response2 = request2.send();

                    assertEquals(HttpServletResponse.SC_OK,response2.getStatus());
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
