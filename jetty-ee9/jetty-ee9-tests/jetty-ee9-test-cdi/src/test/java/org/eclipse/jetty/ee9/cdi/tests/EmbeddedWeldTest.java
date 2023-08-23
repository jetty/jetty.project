//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.cdi.tests;

import java.io.File;
import java.util.EnumSet;

import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.ee9.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee9.cdi.CdiConfiguration;
import org.eclipse.jetty.ee9.cdi.CdiDecoratingListener;
import org.eclipse.jetty.ee9.cdi.CdiServletContainerInitializer;
import org.eclipse.jetty.ee9.cdi.CdiSpiDecorator;
import org.eclipse.jetty.ee9.servlet.ListenerHolder;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class EmbeddedWeldTest
{
    static
    {
        // Wire up java.util.logging (used by weld) to slf4j.
        org.slf4j.bridge.SLF4JBridgeHandler.removeHandlersForRootLogger();
        org.slf4j.bridge.SLF4JBridgeHandler.install();
    }

    // @BeforeEach
    public void dumpClassLoaderState()
    {
        String[] cpEntries = System.getProperty("java.class.path").split(File.pathSeparator);

        for (int i = 0; i < cpEntries.length; i++)
        {
            String entry = cpEntries[i];
            if (entry.contains(".m2/repo"))
                System.out.print(" maven");
            else if (entry.contains("/idea"))
                System.out.print(" idea ");
            else if (entry.contains("/target/"))
                System.out.print("*JETTY");
            System.out.printf(" [%2d] %s%n", i, entry);
        }
    }

    public static Server createServerWithServletContext(String mode)
    {
        Server server = new Server();
        server.addConnector(new LocalConnector(server));
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.setBaseResourceAsString("src/test/weldtest");
        server.setHandler(context);

        // Setup context
        context.addFilter(MyFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        context.addServlet(GreetingsServlet.class, "/");
        context.getServletHandler().addListener(new ListenerHolder(MyContextListener.class));

        // Setup Jetty weld integration
        switch (mode)
        {
            case "none": // Do nothing, let weld work it out.
                // Expect:INFO: WELD-ENV-001201: Jetty 7.2+ detected, CDI injection will be available in Servlets and Filters. Injection into Listeners is not supported.
                context.getServletHandler().addListener(new ListenerHolder(org.jboss.weld.environment.servlet.Listener.class));
                break;

            case "DecoratingListener+Listener":
                // Expect:INFO: WELD-ENV-001212: Jetty CdiDecoratingListener support detected, CDI injection will be available in Listeners, Servlets and Filters.
                context.addEventListener(new org.eclipse.jetty.ee9.webapp.DecoratingListener(context));
                context.getServletHandler().addListener(new ListenerHolder(org.jboss.weld.environment.servlet.Listener.class));
                break;

            case "CdiDecoratingListener+Listener":
                // Expect:INFO: WELD-ENV-001212: Jetty CdiDecoratingListener support detected, CDI injection will be available in Listeners, Servlets and Filters.
                context.addEventListener(new CdiDecoratingListener(context));
                context.addEventListener(new org.jboss.weld.environment.servlet.Listener());
                break;

            case "CdiSpiDecorator+Listener":
                // Expect:INFO: WELD-ENV-001213: Jetty CDI SPI support detected, CDI injection will be available in Listeners, Servlets and Filters.
                context.getObjectFactory().addDecorator(new CdiSpiDecorator(context));
                context.getServletHandler().addListener(new ListenerHolder(org.jboss.weld.environment.servlet.Listener.class));
                break;

            case "CdiServletContainerInitializer+Listener":
                // Expect:INFO: WELD-ENV-001213: Jetty CDI SPI support detected, CDI injection will be available in Listeners, Servlets and Filters.
                context.addBean(new ServletContextHandler.Initializer(context, new CdiServletContainerInitializer()));
                context.addEventListener(new org.jboss.weld.environment.servlet.Listener());
                break;

            case "CdiServletContainerInitializer(CdiDecoratingListener)+Listener":
                // Expect:INFO: WELD-ENV-001212: Jetty CdiDecoratingListener support detected, CDI injection will be available in Listeners, Servlets and Filters
                context.setInitParameter(CdiServletContainerInitializer.CDI_INTEGRATION_ATTRIBUTE, CdiDecoratingListener.MODE);
                context.addBean(new ServletContextHandler.Initializer(context, new CdiServletContainerInitializer()));
                context.addEventListener(new org.jboss.weld.environment.servlet.Listener());
                break;

            case "CdiServletContainerInitializer+EnhancedListener":
                // Expect:INFO: WELD-ENV-001213: Jetty CDI SPI support detected, CDI injection will be available in Listeners, Servlets and Filters.
                context.addBean(new ServletContextHandler.Initializer(context, new CdiServletContainerInitializer()));
                context.addBean(new ServletContextHandler.Initializer(context, new org.jboss.weld.environment.servlet.EnhancedListener()));
                break;

            // NOTE: This is the preferred mode from the Weld team.
            case "CdiServletContainerInitializer(CdiDecoratingListener)+EnhancedListener":
                // Expect:INFO: WELD-ENV-001212: Jetty CdiDecoratingListener support detected, CDI injection will be available in Listeners, Servlets and Filters
                context.setInitParameter(CdiServletContainerInitializer.CDI_INTEGRATION_ATTRIBUTE, CdiDecoratingListener.MODE);
                context.addBean(new ServletContextHandler.Initializer(context, new CdiServletContainerInitializer()));
                context.addBean(new ServletContextHandler.Initializer(context, new org.jboss.weld.environment.servlet.EnhancedListener()));
                break;
        }

        return server;
    }

    @ParameterizedTest()
    @ValueSource(strings =
        {
            "none",
            "DecoratingListener+Listener",
            "CdiDecoratingListener+Listener",
            "CdiSpiDecorator+Listener",
            "CdiServletContainerInitializer+Listener",
            "CdiServletContainerInitializer(CdiDecoratingListener)+Listener",
            "CdiServletContainerInitializer+EnhancedListener",
            "CdiServletContainerInitializer(CdiDecoratingListener)+EnhancedListener"
        })
    public void testServletContext(String mode) throws Exception
    {
        //Mode "none" is incompatible with jetty-12. This mode delegates to Weld to work out how to
        //instantiate injection capabilities into jetty, but as of Weld 5.1.1 this relied on pre jetty-12 classes.
        //Mode "DecoratingListener+Listener is incompatible with jetty-12. The name of the attribute that communicates
        //the injector decorator between Weld and jetty-12 relies on the classname of the DecoratingListener class, which
        //in pre jetty-12 was org.eclipse.jetty.webapp.DecoratingListener, but is now org.eclipse.jetty.ee10.webapp.DecoratingListener.
        Assumptions.assumeFalse(mode.equals("none") || mode.equals("DecoratingListener+Listener"));

        Server server = createServerWithServletContext(mode);
        server.start();
        LocalConnector connector = server.getBean(LocalConnector.class);
        String response = connector.getResponse("GET / HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Hello GreetingsServlet filtered by Weld BeanManager "));
        assertThat(response, containsString("Beans from Weld BeanManager "));
        if (mode.contains("EnhancedListener"))
            assertThat(response, containsString("Listener saw Weld BeanManager"));
        else
            assertThat(response, containsString("Listener saw null"));

        assertThat(response, containsString("Beans from Weld BeanManager for "));

        server.stop();
    }

    @Test
    public void testServletContextSimone() throws Exception
    {
        Server server = createServerWithServletContext("CdiDecoratingListener+Listener");
        server.start();
        LocalConnector connector = server.getBean(LocalConnector.class);
        String response = connector.getResponse("GET / HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Hello GreetingsServlet filtered by Weld BeanManager "));
        assertThat(response, containsString("Beans from Weld BeanManager "));
        assertThat(response, containsString("Listener saw null"));
        assertThat(response, containsString("Beans from Weld BeanManager for "));

        server.stop();
    }

    @Test
    public void testWebappContext() throws Exception
    {
        Server server = new Server();
        server.addConnector(new LocalConnector(server));
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        webapp.setBaseResourceAsString("src/test/weldtest");
        server.setHandler(webapp);

        webapp.setInitParameter(org.eclipse.jetty.ee9.cdi.CdiServletContainerInitializer.CDI_INTEGRATION_ATTRIBUTE, org.eclipse.jetty.ee9.cdi.CdiDecoratingListener.MODE);
        webapp.addBean(new ServletContextHandler.Initializer(webapp, new org.eclipse.jetty.ee9.cdi.CdiServletContainerInitializer()));
        webapp.addBean(new ServletContextHandler.Initializer(webapp, new org.jboss.weld.environment.servlet.EnhancedListener()));

        String pkg = EmbeddedWeldTest.class.getPackage().getName();
        webapp.getServerClassMatcher().add("-" + pkg + ".");
        webapp.getSystemClassMatcher().add(pkg + ".");

        webapp.addServlet(GreetingsServlet.class, "/");
        webapp.addFilter(MyFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        webapp.getServletHandler().addListener(new ListenerHolder(MyContextListener.class));

        server.start();

        LocalConnector connector = server.getBean(LocalConnector.class);
        String response = connector.getResponse("GET / HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Hello GreetingsServlet filtered by Weld BeanManager "));
        assertThat(response, containsString("Beans from Weld BeanManager "));
        assertThat(response, containsString("Listener saw Weld BeanManager"));
        server.stop();
    }

    @Test
    public void testWebappContextDiscovered() throws Exception
    {
        Server server = new Server();
        server.addConnector(new LocalConnector(server));
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        webapp.setBaseResourceAsString("src/test/weldtest");
        server.setHandler(webapp);

        // Need to expose our SCI.
        webapp.addConfiguration(new CdiConfiguration());

        //ensure our CDI SCI is run first so the decorator is set up before Weld runs
        webapp.setAttribute(AnnotationConfiguration.SERVLET_CONTAINER_INITIALIZER_ORDER, "org.eclipse.jetty.ee9.cdi.CdiServletContainerInitializer, *");

        // This is ugly but needed for maven for testing in a overlaid war pom
        String pkg = EmbeddedWeldTest.class.getPackage().getName();
        webapp.getServerClassMatcher().add("-" + pkg + ".");
        webapp.getSystemClassMatcher().add(pkg + ".");

        webapp.getServletHandler().addListener(new ListenerHolder(MyContextListener.class));
        webapp.addFilter(MyFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        webapp.addServlet(GreetingsServlet.class, "/");

        server.start();

        LocalConnector connector = server.getBean(LocalConnector.class);
        String response = connector.getResponse("GET / HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Hello GreetingsServlet filtered by Weld BeanManager "));
        assertThat(response, containsString("Beans from Weld BeanManager "));
        assertThat(response, containsString("Listener saw Weld BeanManager"));
        server.stop();
    }
}
