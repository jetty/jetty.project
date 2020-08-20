//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.cdi.weld;

import java.io.IOException;
import java.util.EnumSet;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.cdi.CdiServletContainerInitializer;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ListenerHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.test.GreetingsServlet;
import org.eclipse.jetty.test.MyContextListener;
import org.eclipse.jetty.test.ServerIDFilter;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.JettyWebXmlConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class EmbeddedWeldTest
{
    public static Server createServerWithServletContext(int mode)
    {
        Server server = new Server();
        server.addConnector(new LocalConnector(server));
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);

        // Setup context
        context.addServlet(GreetingsServlet.class, "/");
        context.addServlet(BeanServlet.class, "/beans");
        context.addFilter(ServerIDFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));

        // Setup Jetty weld integration
        switch (mode)
        {
            case 0: // Do nothing, let weld work it out.
                // Expect:INFO: WELD-ENV-001201: Jetty 7.2+ detected, CDI injection will be available in Servlets and Filters. Injection into Listeners is not supported.
                context.getServletHandler().addListener(new ListenerHolder(org.jboss.weld.environment.servlet.Listener.class));
                break;

            case 1:  // Deprecated use of Decorating Listener
                // Expect:INFO: WELD-ENV-001212: Jetty CdiDecoratingListener support detected, CDI injection will be available in Listeners, Servlets and Filters.
                context.addEventListener(new org.eclipse.jetty.webapp.DecoratingListener(context));
                context.getServletHandler().addListener(new ListenerHolder(org.jboss.weld.environment.servlet.Listener.class));
                break;

            case 2: // CDI Decorating Listener
                // Expect:INFO: WELD-ENV-001212: Jetty CdiDecoratingListener support detected, CDI injection will be available in Listeners, Servlets and Filters.
                context.addEventListener(new org.eclipse.jetty.cdi.CdiDecoratingListener(context));
                context.addEventListener(new org.jboss.weld.environment.servlet.Listener());
                break;

            case 3: // CDI SPI
                // Expect:INFO: WELD-ENV-001213: Jetty CDI SPI support detected, CDI injection will be available in Listeners, Servlets and Filters.
                context.getObjectFactory().addDecorator(new org.eclipse.jetty.cdi.CdiSpiDecorator(context));
                context.getServletHandler().addListener(new ListenerHolder(org.jboss.weld.environment.servlet.Listener.class));
                break;

            case 4: // SCI invocation with no mode selected
                // Expect:INFO: WELD-ENV-001213: Jetty CDI SPI support detected, CDI injection will be available in Listeners, Servlets and Filters.
                context.addBean(new ServletContextHandler.Initializer(context, new org.eclipse.jetty.cdi.CdiServletContainerInitializer()));
                context.addEventListener(new org.jboss.weld.environment.servlet.Listener());
                // context.getServletHandler().addListener(new ListenerHolder(org.jboss.weld.environment.servlet.Listener.class));
                break;

            case 5: // SCI invocation with mode selected
                // Expect:INFO: WELD-ENV-001212: Jetty CdiDecoratingListener support detected, CDI injection will be available in Listeners, Servlets and Filters
                context.setInitParameter(org.eclipse.jetty.cdi.CdiServletContainerInitializer.CDI_INTEGRATION_ATTRIBUTE, org.eclipse.jetty.cdi.CdiDecoratingListener.MODE);
                context.addBean(new ServletContextHandler.Initializer(context, new org.eclipse.jetty.cdi.CdiServletContainerInitializer()));
                context.getServletHandler().addListener(new ListenerHolder(org.jboss.weld.environment.servlet.Listener.class));
                break;

            case 6: // direct SCI invocation of jetty and Weld SCI
                // Expect:INFO: WELD-ENV-001213: Jetty CDI SPI support detected, CDI injection will be available in Listeners, Servlets and Filters.
                context.addBean(new ServletContextHandler.Initializer(context, new org.jboss.weld.environment.servlet.EnhancedListener()));
                context.addBean(new ServletContextHandler.Initializer(context, new org.eclipse.jetty.cdi.CdiServletContainerInitializer()));

                // Can decorate MyContextListener in this setup
                context.getServletHandler().addListener(new ListenerHolder(MyContextListener.class));
                break;

            case 7: // direct SCI invocation of jetty and Weld SCI with mode selected
                // Expect:INFO: WELD-ENV-001212: Jetty CdiDecoratingListener support detected, CDI injection will be available in Listeners, Servlets and Filters
                context.setInitParameter(org.eclipse.jetty.cdi.CdiServletContainerInitializer.CDI_INTEGRATION_ATTRIBUTE, org.eclipse.jetty.cdi.CdiDecoratingListener.MODE);
                context.addBean(new ServletContextHandler.Initializer(context, new org.jboss.weld.environment.servlet.EnhancedListener()));
                context.addBean(new ServletContextHandler.Initializer(context, new org.eclipse.jetty.cdi.CdiServletContainerInitializer()));

                // Can decorate MyContextListener in this setup
                context.getServletHandler().addListener(new ListenerHolder(MyContextListener.class));
                break;

            case 8: // direct SCI invocation of jetty and Weld SCI with mode selected - check order independent
                // Expect:INFO: WELD-ENV-001212: Jetty CdiDecoratingListener support detected, CDI injection will be available in Listeners, Servlets and Filters
                context.setInitParameter(org.eclipse.jetty.cdi.CdiServletContainerInitializer.CDI_INTEGRATION_ATTRIBUTE, org.eclipse.jetty.cdi.CdiDecoratingListener.MODE);
                context.addBean(new ServletContextHandler.Initializer(context, new org.eclipse.jetty.cdi.CdiServletContainerInitializer()));
                context.addBean(new ServletContextHandler.Initializer(context, new org.jboss.weld.environment.servlet.EnhancedListener()));

                // Can decorate MyContextListener in this setup
                context.getServletHandler().addListener(new ListenerHolder(MyContextListener.class));
                break;
        }

        return server;
    }

    @ParameterizedTest()
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    public void testServletContext(int mode) throws Exception
    {
        Server server = createServerWithServletContext(mode);
        server.start();
        LocalConnector connector = server.getBean(LocalConnector.class);
        String response = connector.getResponse("GET / HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Hello GreetingsServlet"));
        if (mode >= 6)
            assertThat(response, containsString(" from CDI-Demo-org.eclipse.jetty.test"));

        response = connector.getResponse("GET /beans HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("Beans from Weld BeanManager for "));

        server.stop();
    }

    @Test
    public void testWebappContext() throws Exception
    {
        Server server = new Server(8080);
        server.addConnector(new LocalConnector(server));
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        webapp.setResourceBase("src/test/resources");
        server.setHandler(webapp);

        webapp.setInitParameter(org.eclipse.jetty.cdi.CdiServletContainerInitializer.CDI_INTEGRATION_ATTRIBUTE, org.eclipse.jetty.cdi.CdiDecoratingListener.MODE);
        webapp.addBean(new ServletContextHandler.Initializer(webapp, new org.eclipse.jetty.cdi.CdiServletContainerInitializer()));
        webapp.addBean(new ServletContextHandler.Initializer(webapp, new org.jboss.weld.environment.servlet.EnhancedListener()));

        // This is ugly but needed for maven for testing in a overlaid war pom
        webapp.getServerClasspathPattern().add("-org.eclipse.jetty.test.");
        webapp.getSystemClasspathPattern().add("org.eclipse.jetty.test.");

        webapp.addServlet(GreetingsServlet.class, "/");
        webapp.addFilter(ServerIDFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        webapp.getServletHandler().addListener(new ListenerHolder(MyContextListener.class));

        server.start();

        LocalConnector connector = server.getBean(LocalConnector.class);
        String response = connector.getResponse("GET / HTTP/1.0\r\n\r\n");
        System.err.println(response);
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Hello GreetingsServlet"));
        assertThat(response, containsString(" from CDI-Demo-org.eclipse.jetty.test"));
        server.stop();

    }

    @Test
    public void testWebappContextDiscovered() throws Exception
    {
        Server server = new Server(8080);
        server.addConnector(new LocalConnector(server));
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        webapp.setResourceBase("src/test/resources");
        server.setHandler(webapp);

        // Need the AnnotationConfiguration to detect SCIs
        Configuration.ClassList.setServerDefault(server).addBefore(JettyWebXmlConfiguration.class.getName(),
            AnnotationConfiguration.class.getName());

        // Need to expose our SCI.  This is ugly could be made better in jetty-10 with a CdiConfiguration
        webapp.getServerClasspathPattern().add("-" + CdiServletContainerInitializer.class.getName());
        webapp.getSystemClasspathPattern().add(CdiServletContainerInitializer.class.getName());

        // This is ugly but needed for maven for testing in a overlaid war pom
        webapp.getServerClasspathPattern().add("-org.eclipse.jetty.test.");
        webapp.getSystemClasspathPattern().add("org.eclipse.jetty.test.");

        webapp.addServlet(GreetingsServlet.class, "/");
        webapp.addFilter(ServerIDFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        webapp.getServletHandler().addListener(new ListenerHolder(MyContextListener.class));

        server.start();

        LocalConnector connector = server.getBean(LocalConnector.class);
        String response = connector.getResponse("GET / HTTP/1.0\r\n\r\n");
        System.err.println(response);
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Hello GreetingsServlet"));
        assertThat(response, containsString(" from CDI-Demo-org.eclipse.jetty.test"));
        server.stop();

    }

    public static class BeanServlet extends HttpServlet
    {
        @Inject
        BeanManager manager;

        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setContentType("text/plain");
            resp.getWriter().append("Beans from " + manager);
        }
    }

}
