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

package org.eclipse.jetty.webapp;

import java.nio.file.Path;
import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.PathResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ForcedServletTest
{
    private Server server;
    private LocalConnector connector;

    @BeforeEach
    public void setup() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        WebAppContext context = new ForcedWebAppContext();
        // use a default descriptor with no "default" or "jsp" defined.
        Path altWebDefault = MavenTestingUtils.getTestResourcePathFile("alt-jsp-webdefault.xml");
        context.setDefaultsDescriptor(altWebDefault.toAbsolutePath().toString());
        Path altWebApp = MavenTestingUtils.getProjectDirPath("src/test/webapp-alt-jsp");
        context.setWarResource(new PathResource(altWebApp));

        // context.getSystemClasspathPattern().add("org.eclipse.jetty.webapp.jsp.");
        // context.getServerClasspathPattern().add("-org.eclipse.jetty.webapp.jsp.");

        server.setHandler(context);
        server.setDumpAfterStart(true);
        server.start();
    }

    @AfterEach
    public void teardown()
    {
        LifeCycle.stop(server);
    }

    /**
     * Test access to a jsp resource defined in the web.xml, but doesn't actually exist.
     * <p>
     * Think of this as a precompiled jsp entry, but the class doesn't exist.
     * </p>
     */
    @Test
    public void testAccessBadDescriptorEntry() throws Exception
    {
        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /does/not/exist/index.jsp HTTP/1.1\r\n");
        rawRequest.append("Host: local\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = connector.getResponse(rawRequest.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertEquals(555, response.getStatus());
    }

    /**
     * Test access of a jsp resource that doesn't exist in the base resource or the descriptor.
     */
    @Test
    public void testAccessNonExistentEntry() throws Exception
    {
        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /bogus.jsp HTTP/1.1\r\n");
        rawRequest.append("Host: local\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = connector.getResponse(rawRequest.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertEquals(555, response.getStatus());
    }

    /**
     * Test access of a jsp resource that exist in the base resource, but not in the descriptor.
     */
    @Test
    public void testAccessUncompiledEntry() throws Exception
    {
        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /hello.jsp HTTP/1.1\r\n");
        rawRequest.append("Host: local\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = connector.getResponse(rawRequest.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertEquals(555, response.getStatus());
    }

    /**
     * Test access of a jsp resource that does not exist in the base resource, but in the descriptor, as a precompiled jsp entry
     */
    @Test
    public void testPrecompiledEntry() throws Exception
    {
        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /precompiled/world.jsp HTTP/1.1\r\n");
        rawRequest.append("Host: local\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = connector.getResponse(rawRequest.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertEquals(200, response.getStatus());

        String responseBody = response.getContent();
        assertThat(responseBody, containsString("This is the FakePrecompiledJSP"));
    }

    public static class ForcedWebAppContext extends WebAppContext
    {
        @Override
        protected void startWebapp() throws Exception
        {
            System.err.printf("### Thread.cl = %s%n", Thread.currentThread().getContextClassLoader());
            // This will result in any attempt to use an JSP that isn't precompiled and in the descriptor with status code 555
            // forceServlet("jsp", RejectUncompiledJspServlet.class);

            // TODO: alt fix - remove forced path
            /*for (ServletHolder h : getServletHandler().getServlets())
            {
                if (h.getForcedPath() != null)
                {
                    System.err.println("FORCED " + h.getForcedPath());
                    h.setHeldClass(RejectUncompiledJspServlet.class);
                    h.setForcedPath(null);
                }
            }*/

            super.startWebapp();
        }

        @SuppressWarnings("SameParameterValue")
        private void forceServlet(String name, Class<? extends HttpServlet> servlet) throws Exception
        {
            ServletHolder holder = getServletHandler().getServlet(name);
            if (holder == null)
            {
                holder = new ServletHolder(servlet.getConstructor().newInstance());
                holder.setInitOrder(1);
                holder.setName(name);
                holder.setAsyncSupported(true);
                // Without the mapping, this forced servlet has no impact on non-precompiled JSP files.
                // getServletHandler().addServletWithMapping(holder, "*.jsp");
                // BAD / False Success - getServletHandler().addServlet(holder);
            }
        }
    }
}
