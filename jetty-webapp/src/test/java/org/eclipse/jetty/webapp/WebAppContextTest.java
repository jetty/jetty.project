//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.webapp;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import javax.servlet.GenericServlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.HotSwapHandler;
import org.eclipse.jetty.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebAppContextTest
{
    public class MySessionListener implements HttpSessionListener
    {
        @Override
        public void sessionCreated(HttpSessionEvent se)
        {
        }

        @Override
        public void sessionDestroyed(HttpSessionEvent se)
        {
        }
    }

    @AfterEach
    public void tearDown()
    {
        Configurations.cleanKnown();
    }

    @Test
    public void testSessionListeners()
    {
        Server server = new Server();

        WebAppContext wac = new WebAppContext();

        wac.setServer(server);
        server.setHandler(wac);
        wac.addEventListener(new MySessionListener());

        Collection<MySessionListener> listeners = wac.getSessionHandler().getBeans(MySessionListener.class);
        assertNotNull(listeners);
        assertEquals(1, listeners.size());
    }

    @Test
    public void testConfigurationClassesFromDefault()
    {
        Configurations.cleanKnown();
        String[] knownAndEnabled = Configurations.getKnown().stream()
            .filter(c -> c.isEnabledByDefault())
            .map(c -> c.getClass().getName())
            .toArray(String[]::new);

        Server server = new Server();

        //test if no classnames set, its the defaults
        WebAppContext wac = new WebAppContext();
        assertThat(wac.getConfigurations().stream()
                .map(c -> c.getClass().getName())
                .collect(Collectors.toList()),
            Matchers.containsInAnyOrder(knownAndEnabled));
        String[] classNames = wac.getConfigurationClasses();
        assertNotNull(classNames);

        //test if no classname set, and none from server its the defaults
        wac.setServer(server);
        assertTrue(Arrays.equals(classNames, wac.getConfigurationClasses()));
    }

    @Test
    public void testConfigurationOrder()
    {
        Configurations.cleanKnown();
        WebAppContext wac = new WebAppContext();
        wac.setServer(new Server());
        assertThat(wac.getConfigurations().stream().map(c -> c.getClass().getName()).collect(Collectors.toList()),
            Matchers.contains(
                "org.eclipse.jetty.webapp.JmxConfiguration",
                "org.eclipse.jetty.webapp.WebInfConfiguration",
                "org.eclipse.jetty.webapp.WebXmlConfiguration",
                "org.eclipse.jetty.webapp.MetaInfConfiguration",
                "org.eclipse.jetty.webapp.FragmentConfiguration",
                "org.eclipse.jetty.webapp.WebAppConfiguration",
                "org.eclipse.jetty.webapp.JettyWebXmlConfiguration"));
    }

    @Test
    public void testConfigurationInstances()
    {
        Configurations.cleanKnown();
        Configuration[] configs = {new WebInfConfiguration()};
        WebAppContext wac = new WebAppContext();
        wac.setConfigurations(configs);
        assertThat(wac.getConfigurations(), Matchers.contains(configs));

        //test that explicit config instances override any from server
        String[] classNames = {"x.y.z"};
        Server server = new Server();
        server.setAttribute(Configuration.ATTR, classNames);
        wac.setServer(server);
        assertThat(wac.getConfigurations(), Matchers.contains(configs));
    }

    @Test
    public void testRealPathDoesNotExist() throws Exception
    {
        Server server = new Server(0);
        WebAppContext context = new WebAppContext(".", "/");
        server.setHandler(context);
        server.start();

        ServletContext ctx = context.getServletContext();
        assertNotNull(ctx.getRealPath("/doesnotexist"));
        assertNotNull(ctx.getRealPath("/doesnotexist/"));
    }

    /**
     * tests that the servlet context white list works
     *
     * @throws Exception on test failure
     */
    @Test
    public void testContextWhiteList() throws Exception
    {
        Server server = new Server(0);
        HandlerList handlers = new HandlerList();
        WebAppContext contextA = new WebAppContext(".", "/A");

        contextA.addServlet(ServletA.class, "/s");
        handlers.addHandler(contextA);
        WebAppContext contextB = new WebAppContext(".", "/B");

        contextB.addServlet(ServletB.class, "/s");
        contextB.setContextWhiteList("/doesnotexist", "/B/s");
        handlers.addHandler(contextB);

        server.setHandler(handlers);
        server.start();

        // context A should be able to get both A and B servlet contexts
        assertNotNull(contextA.getServletHandler().getServletContext().getContext("/A/s"));
        assertNotNull(contextA.getServletHandler().getServletContext().getContext("/B/s"));

        // context B has a contextWhiteList set and should only be able to get ones that are approved
        assertNull(contextB.getServletHandler().getServletContext().getContext("/A/s"));
        assertNotNull(contextB.getServletHandler().getServletContext().getContext("/B/s"));
    }

    @Test
    public void testAlias() throws Exception
    {
        File dir = File.createTempFile("dir", null);
        dir.delete();
        dir.mkdir();
        dir.deleteOnExit();

        File webinf = new File(dir, "WEB-INF");
        webinf.mkdir();

        File classes = new File(dir, "classes");
        classes.mkdir();

        File someclass = new File(classes, "SomeClass.class");
        someclass.createNewFile();

        WebAppContext context = new WebAppContext();
        context.setBaseResource(new ResourceCollection(dir.getAbsolutePath()));

        context.setResourceAlias("/WEB-INF/classes/", "/classes/");

        assertTrue(Resource.newResource(context.getServletContext().getResource("/WEB-INF/classes/SomeClass.class")).exists());
        assertTrue(Resource.newResource(context.getServletContext().getResource("/classes/SomeClass.class")).exists());
    }

    @Test
    public void testIsProtected()
    {
        WebAppContext context = new WebAppContext();
        assertTrue(context.isProtectedTarget("/web-inf/lib/foo.jar"));
        assertTrue(context.isProtectedTarget("/meta-inf/readme.txt"));
        assertFalse(context.isProtectedTarget("/something-else/web-inf"));
    }

    @Test
    public void testNullPath() throws Exception
    {
        Server server = new Server(0);
        HandlerList handlers = new HandlerList();
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        WebAppContext context = new WebAppContext();
        context.setBaseResource(Resource.newResource("./src/test/webapp"));
        context.setContextPath("/");
        server.setHandler(handlers);
        handlers.addHandler(contexts);
        contexts.addHandler(context);

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);

        server.start();

        try
        {
            String response = connector.getResponse("GET http://localhost:8080 HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n");
            assertThat(response, containsString("200 OK"));
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testNullSessionAndSecurityHandler() throws Exception
    {
        Server server = new Server(0);
        HandlerList handlers = new HandlerList();
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        WebAppContext context = new WebAppContext(null, null, null, null, null, new ErrorPageErrorHandler(),
            ServletContextHandler.NO_SESSIONS | ServletContextHandler.NO_SECURITY);
        context.setContextPath("/");
        context.setBaseResource(Resource.newResource("./src/test/webapp"));
        server.setHandler(handlers);
        handlers.addHandler(contexts);
        contexts.addHandler(context);

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);

        try
        {
            server.start();
            assertTrue(context.isAvailable());
        }
        finally
        {
            server.stop();
        }
    }

    class ServletA extends GenericServlet
    {
        @Override
        public void service(ServletRequest req, ServletResponse res)
        {
            this.getServletContext().getContext("/A/s");
        }
    }

    class ServletB extends GenericServlet
    {
        @Override
        public void service(ServletRequest req, ServletResponse res)
        {
            this.getServletContext().getContext("/B/s");
        }
    }

    @Test
    public void testServletContextListener() throws Exception
    {
        Server server = new Server();
        HotSwapHandler swap = new HotSwapHandler();
        server.setHandler(swap);
        server.start();

        ServletContextHandler context = new ServletContextHandler(
            ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.setResourceBase(System.getProperty("java.io.tmpdir"));

        final List<String> history = new ArrayList<>();

        context.addEventListener(new ServletContextListener()
        {
            @Override
            public void contextInitialized(ServletContextEvent servletContextEvent)
            {
                history.add("I0");
            }

            @Override
            public void contextDestroyed(ServletContextEvent servletContextEvent)
            {
                history.add("D0");
            }
        });
        context.addEventListener(new ServletContextListener()
        {
            @Override
            public void contextInitialized(ServletContextEvent servletContextEvent)
            {
                history.add("I1");
            }

            @Override
            public void contextDestroyed(ServletContextEvent servletContextEvent)
            {
                history.add("D1");
                throw new RuntimeException("Listener1 destroy broken");
            }
        });
        context.addEventListener(new ServletContextListener()
        {
            @Override
            public void contextInitialized(ServletContextEvent servletContextEvent)
            {
                history.add("I2");
                throw new RuntimeException("Listener2 init broken");
            }

            @Override
            public void contextDestroyed(ServletContextEvent servletContextEvent)
            {
                history.add("D2");
            }
        });
        context.addEventListener(new ServletContextListener()
        {
            @Override
            public void contextInitialized(ServletContextEvent servletContextEvent)
            {
                history.add("I3");
            }

            @Override
            public void contextDestroyed(ServletContextEvent servletContextEvent)
            {
                history.add("D3");
            }
        });

        try
        {
            swap.setHandler(context);
            context.start();
        }
        catch (Exception e)
        {
            history.add(e.getMessage());
        }
        finally
        {
            try
            {
                swap.setHandler(null);
            }
            catch (Exception e)
            {
                while (e.getCause() instanceof Exception)
                {
                    e = (Exception)e.getCause();
                }
                history.add(e.getMessage());
            }
        }

        assertThat(history, contains("I0", "I1", "I2", "Listener2 init broken", "D1", "D0", "Listener1 destroy broken"));

        server.stop();
    }

    @Test
    public void ordering() throws Exception
    {
        Path testWebappDir = MavenTestingUtils.getProjectDirPath("src/test/webapp");
        Resource webapp = new PathResource(testWebappDir);
        WebAppContext context = new WebAppContext();
        context.setBaseResource(webapp);
        context.setContextPath("/test");
        context.setServer(new Server());
        new MetaInfConfiguration().preConfigure(context);
        assertEquals(Arrays.asList("acme.jar", "alpha.jar", "omega.jar"),
            context.getMetaData().getWebInfJars().stream().map(r -> r.getURI().toString().replaceFirst(".+/", "")).collect(Collectors.toList()));
    }
}
