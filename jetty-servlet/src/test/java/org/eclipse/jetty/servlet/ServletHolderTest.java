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

package org.eclipse.jetty.servlet;

import javax.servlet.UnavailableException;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.junit.Assert;
import org.junit.Test;

public class ServletHolderTest {

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
        Assert.assertTrue(one.compareTo(two) < 0);
        Assert.assertTrue(two.compareTo(three) < 0);
        Assert.assertTrue(one.compareTo(three) < 0);
    }
    

    @Test
    public void testJspFileNameToClassName() throws Exception
    {
        ServletHolder h = new ServletHolder();
        h.setName("test");


        Assert.assertEquals(null,  h.getClassNameForJsp(null));

        Assert.assertEquals(null,  h.getClassNameForJsp(""));

        Assert.assertEquals(null,  h.getClassNameForJsp("/blah/"));

        Assert.assertEquals(null,  h.getClassNameForJsp("//blah///"));

        Assert.assertEquals(null,  h.getClassNameForJsp("/a/b/c/blah/"));

        Assert.assertEquals("org.apache.jsp.a.b.c.blah",  h.getClassNameForJsp("/a/b/c/blah"));

        Assert.assertEquals("org.apache.jsp.blah_jsp", h.getClassNameForJsp("/blah.jsp"));

        Assert.assertEquals("org.apache.jsp.blah_jsp", h.getClassNameForJsp("//blah.jsp"));

        Assert.assertEquals("org.apache.jsp.blah_jsp", h.getClassNameForJsp("blah.jsp"));

        Assert.assertEquals("org.apache.jsp.a.b.c.blah_jsp", h.getClassNameForJsp("/a/b/c/blah.jsp"));

        Assert.assertEquals("org.apache.jsp.a.b.c.blah_jsp", h.getClassNameForJsp("a/b/c/blah.jsp"));
    }


    @Test
    public void testNoClassName() throws Exception
    {
        try (StacklessLogging stackless = new StacklessLogging(ServletHandler.class, ContextHandler.class, ServletContextHandler.class))
        {
            ServletContextHandler context = new ServletContextHandler(); 
            ServletHandler handler = context.getServletHandler();
            ServletHolder holder = new ServletHolder();
            holder.setName("foo");
            holder.setForcedPath("/blah/");
            handler.addServlet(holder);
            handler.start();
            Assert.fail("No class in ServletHolder");
        }
        catch (UnavailableException e)
        {
            Assert.assertTrue(e.getMessage().contains("foo"));
        }
        catch (MultiException e)
        {
            MultiException m = (MultiException)e;
            Assert.assertTrue(m.getCause().getMessage().contains("foo"));
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
            handler.start();
            Assert.fail("Unloadable class");
        }
        catch (UnavailableException e)
        {
            Assert.assertTrue(e.getMessage().contains("foo"));
        }
        catch (MultiException e)
        {
            MultiException m = (MultiException)e;
            Assert.assertTrue(m.getCause().getMessage().contains("foo"));
        }
    }
   
}
