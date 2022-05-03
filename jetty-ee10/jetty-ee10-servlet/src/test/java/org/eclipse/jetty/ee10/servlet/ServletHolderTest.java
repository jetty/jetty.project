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

package org.eclipse.jetty.ee10.servlet;

import java.util.Collections;
import java.util.Set;

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServlet;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.MultiException;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class ServletHolderTest
{
    public static class FakeServlet extends HttpServlet
    {
    }

    @Test
    public void testInitParams() throws Exception
    {
        ServletHolder holder = new ServletHolder(Source.JAVAX_API);
        ServletRegistration reg = holder.getRegistration();

        assertThrows(IllegalArgumentException.class, () -> reg.setInitParameter(null, "foo"));

        assertThrows(IllegalArgumentException.class, () -> reg.setInitParameter("foo", null));

        reg.setInitParameter("foo", "bar");
        assertFalse(reg.setInitParameter("foo", "foo"));

        Set<String> clash = reg.setInitParameters(Collections.singletonMap("foo", "bax"));
        assertTrue(clash != null && clash.size() == 1, "should be one clash");

        assertThrows(IllegalArgumentException.class, () -> reg.setInitParameters(Collections.singletonMap((String)null, "bax")));
        assertThrows(IllegalArgumentException.class, () -> reg.setInitParameters(Collections.singletonMap("foo", (String)null)));

        Set<String> clash2 = reg.setInitParameters(Collections.singletonMap("FOO", "bax"));
        assertTrue(clash2.isEmpty(), "should be no clash");
        assertEquals(2, reg.getInitParameters().size(), "setInitParameters should not replace existing non-clashing init parameters");
    }

    @Test
    public void testTransitiveCompareTo() throws Exception
    {
        // example of jsp-file referenced in web.xml
        final ServletHolder one = new ServletHolder();
        one.setInitOrder(-1);
        one.setName("Login");
        one.setClassName(null);

        // example of pre-compiled jsp
        final ServletHolder two = new ServletHolder();
        two.setInitOrder(-1);
        two.setName("org.my.package.jsp.WEB_002dINF.pages.precompiled_002dpage_jsp");
        two.setClassName("org.my.package.jsp.WEB_002dINF.pages.precompiled_002dpage_jsp");

        // example of servlet referenced in web.xml
        final ServletHolder three = new ServletHolder();
        three.setInitOrder(-1);
        three.setName("Download");
        three.setClassName("org.my.package.web.DownloadServlet");

        // verify compareTo transitivity
        assertTrue(one.compareTo(two) < 0);
        assertTrue(two.compareTo(three) < 0);
        assertTrue(one.compareTo(three) < 0);
    }

    @Test // TODO: Parameterize
    public void testJspFileNameToClassName() throws Exception
    {
        ServletHolder h = new ServletHolder();
        h.setName("test");

        assertNull(h.getClassNameForJsp(null));
        assertNull(h.getClassNameForJsp(""));
        assertNull(h.getClassNameForJsp("/blah/"));
        assertNull(h.getClassNameForJsp("//blah///"));
        assertNull(h.getClassNameForJsp("/a/b/c/blah/"));
        assertEquals("org.apache.jsp.a.b.c.blah", h.getClassNameForJsp("/a/b/c/blah"));
        assertEquals("org.apache.jsp.blah_jsp", h.getClassNameForJsp("/blah.jsp"));
        assertEquals("org.apache.jsp.blah_jsp", h.getClassNameForJsp("//blah.jsp"));
        assertEquals("org.apache.jsp.blah_jsp", h.getClassNameForJsp("blah.jsp"));
        assertEquals("org.apache.jsp.a.b.c.blah_jsp", h.getClassNameForJsp("/a/b/c/blah.jsp"));
        assertEquals("org.apache.jsp.a.b.c.blah_jsp", h.getClassNameForJsp("a/b/c/blah.jsp"));
    }

    @Test
    public void testCreateInstance() throws Exception
    {
        try (StacklessLogging ignore = new StacklessLogging(ServletHandler.class, ContextHandler.class, ServletContextHandler.class))
        {
            //test without a ServletContextHandler or current ContextHandler
            ServletHolder holder = new ServletHolder();
            holder.setName("foo");
            holder.setHeldClass(FakeServlet.class);
            Servlet servlet = holder.createInstance();
            assertNotNull(servlet);

            //test with a ServletContextHandler
            ServletContextHandler context = new ServletContextHandler();
            ServletHandler handler = context.getServletHandler();
            handler.addServlet(holder);
            holder.setServletHandler(handler);
            context.start();
            assertNotNull(holder.getServlet());
        }
    }

    @Test
    public void testNoClassName() throws Exception
    {
        try (StacklessLogging ignore = new StacklessLogging(ServletHandler.class, ContextHandler.class, ServletContextHandler.class))
        {
            ServletContextHandler context = new ServletContextHandler();
            ServletHandler handler = context.getServletHandler();
            ServletHolder holder = new ServletHolder();
            holder.setName("foo");
            holder.setForcedPath("/blah/");
            handler.addServlet(holder);
            context.start();
            fail("No class in ServletHolder");
        }
        catch (UnavailableException e)
        {
            assertThat(e.getMessage(), containsString("foo"));
        }
        catch (MultiException e)
        {
            assertThat(e.getCause().getMessage(), containsString("foo"));
        }
    }

    @Test
    public void testWithClass() throws Exception
    {
        //Test adding servlet by class
        try (StacklessLogging stackless = new StacklessLogging(BaseHolder.class, ServletHandler.class, ContextHandler.class, ServletContextHandler.class))
        {
            ServletContextHandler context = new ServletContextHandler();
            ServletHandler handler = context.getServletHandler();
            ServletHolder holder = new ServletHolder();
            holder.setName("foo");
            holder.setHeldClass(FakeServlet.class);
            handler.addServlet(holder);
            context.start();
            assertTrue(holder.isAvailable());
            assertTrue(holder.isStarted());
        }
    }

    @Test
    public void testWithClassName() throws Exception
    {
        //Test adding servlet by classname
        try (StacklessLogging stackless = new StacklessLogging(BaseHolder.class, ServletHandler.class, ContextHandler.class, ServletContextHandler.class))
        {
            ServletContextHandler context = new ServletContextHandler();
            ServletHandler handler = context.getServletHandler();
            ServletHolder holder = new ServletHolder();
            holder.setName("foo");
            holder.setClassName("org.eclipse.jetty.ee10.servlet.ServletHolderTest$FakeServlet");
            handler.addServlet(holder);
            context.start();
            assertTrue(holder.isAvailable());
            assertTrue(holder.isStarted());
        }
    }

    @Test
    public void testUnloadableClassName() throws Exception
    {
        try (StacklessLogging stackless = new StacklessLogging(BaseHolder.class, ServletHandler.class, ContextHandler.class, ServletContextHandler.class))
        {
            ServletContextHandler context = new ServletContextHandler();
            ServletHandler handler = context.getServletHandler();
            ServletHolder holder = new ServletHolder();
            holder.setName("foo");
            holder.setClassName("a.b.c.class");
            handler.addServlet(holder);
            context.start();
            fail("Unloadable class");
        }
        catch (UnavailableException e)
        {
            assertThat(e.getMessage(), containsString("foo"));
        }
        catch (MultiException e)
        {
            assertThat(e.getCause().getMessage(), containsString("foo"));
        }
    }
}
