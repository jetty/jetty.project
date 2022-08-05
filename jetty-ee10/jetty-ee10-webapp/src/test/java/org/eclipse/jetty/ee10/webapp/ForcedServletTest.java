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

package org.eclipse.jetty.ee10.webapp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.stream.Stream;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.servlet.ServletMapping;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.ResourceFactory;
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

        WebAppContext context = new WebAppContext();
        context.addBean(new TestInit(context));

        // Lets setup the Webapp base resource properly
        Path basePath = MavenTestingUtils.getTargetTestingPath(ForcedServletTest.class.getName()).resolve("webapp");
        FS.ensureEmpty(basePath);
        Path srcWebApp = MavenTestingUtils.getProjectDirPath("src/test/webapp-alt-jsp");
        copyDir(srcWebApp, basePath);
        copyClass(FakePrecompiledJSP.class, basePath.resolve("WEB-INF/classes"));

        // Use the new base
        context.setWarResource(ResourceFactory.of(server).newResource(basePath));

        server.setHandler(context);
        // server.setDumpAfterStart(true);
        server.start();
    }

    private void copyClass(Class<?> clazz, Path destClasses) throws IOException
    {
        String classRelativeFilename = clazz.getName().replace('.', '/') + ".class";
        Path destFile = destClasses.resolve(classRelativeFilename);
        FS.ensureDirExists(destFile.getParent());

        Path srcFile = MavenTestingUtils.getTargetPath("test-classes/" + classRelativeFilename);
        Files.copy(srcFile, destFile);
    }

    private void copyDir(Path src, Path dest) throws IOException
    {
        FS.ensureDirExists(dest);
        try (Stream<Path> srcStream = Files.list(src))
        {
            for (Iterator<Path> it = srcStream.iterator(); it.hasNext(); )
            {
                Path path = it.next();
                if (Files.isRegularFile(path))
                    Files.copy(path, dest.resolve(path.getFileName()));
                else if (Files.isDirectory(path))
                    copyDir(path, dest.resolve(path.getFileName()));
            }
        }
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
        // Since this was a request to a resource ending in `*.jsp`, the RejectUncompiledJspServlet responded
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
        // Since this was a request to a resource ending in `*.jsp`, the RejectUncompiledJspServlet responded
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
        // status code 555 is from RejectUncompiledJspServlet
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

    public static class TestInit extends AbstractLifeCycle implements ServletContextHandler.ServletContainerInitializerCaller
    {
        private final WebAppContext _webapp;

        public TestInit(WebAppContext webapp)
        {
            _webapp = webapp;
        }

        @Override
        protected void doStart() throws Exception
        {
            // This will result in a 404 for all requests that don't belong to a more precise servlet
            forceServlet("default", ServletHandler.Default404Servlet.class);
            addServletMapping("default", "/");

            // This will result in any attempt to use an JSP that isn't precompiled and in the descriptor with status code 555
            forceServlet("jsp", RejectUncompiledJspServlet.class);
            addServletMapping("jsp", "*.jsp");
            super.doStart();
        }

        private void addServletMapping(String name, String pathSpec)
        {
            ServletMapping mapping = new ServletMapping();
            mapping.setServletName(name);
            mapping.setPathSpec(pathSpec);
            _webapp.getServletHandler().addServletMapping(mapping);
        }

        private void forceServlet(String name, Class<? extends HttpServlet> servlet) throws Exception
        {
            ServletHandler handler = _webapp.getServletHandler();

            // Remove existing holder
            handler.setServlets(Arrays.stream(handler.getServlets())
                .filter(h -> !h.getName().equals(name))
                .toArray(ServletHolder[]::new));

            // add the forced servlet
            ServletHolder holder = new ServletHolder(servlet.getConstructor().newInstance());
            holder.setInitOrder(1);
            holder.setName(name);
            holder.setAsyncSupported(true);
            handler.addServlet(holder);
        }
    }

    public static class RejectUncompiledJspServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            log(String.format("Uncompiled JSPs not supported by %s", request.getRequestURI()));
            response.sendError(555);
        }
    }

    public static class FakeJspServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setCharacterEncoding("utf-8");
            resp.setContentType("text/plain");
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().println("This is the FakeJspServlet");
        }
    }

    public static class FakePrecompiledJSP extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setCharacterEncoding("utf-8");
            resp.setContentType("text/plain");
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().println("This is the FakePrecompiledJSP");
        }
    }
}
