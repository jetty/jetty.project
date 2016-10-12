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

package org.eclipse.jetty.annotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.webapp.DiscoveredAnnotation;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * TestServletAnnotations
 */
public class TestServletAnnotations
{
    public class TestWebServletAnnotationHandler extends WebServletAnnotationHandler
    {
        List<DiscoveredAnnotation> _list = null;

        public TestWebServletAnnotationHandler(WebAppContext context, List<DiscoveredAnnotation> list)
        {
            super(context);
            _list = list;
        }

        @Override
        public void addAnnotation(DiscoveredAnnotation a)
        {
            super.addAnnotation(a);
            _list.add(a);
        }
    }

    @Test
    public void testServletAnnotation() throws Exception
    {
        List<String> classes = new ArrayList<String>();
        classes.add("org.eclipse.jetty.annotations.ServletC");
        AnnotationParser parser = new AnnotationParser();

        WebAppContext wac = new WebAppContext();
        List<DiscoveredAnnotation> results = new ArrayList<DiscoveredAnnotation>();

        TestWebServletAnnotationHandler handler = new TestWebServletAnnotationHandler(wac, results);

        parser.parse(Collections.singleton(handler), classes);


        assertEquals(1, results.size());
        assertTrue(results.get(0) instanceof WebServletAnnotation);

        results.get(0).apply();

        ServletHolder[] holders = wac.getServletHandler().getServlets();
        assertNotNull(holders);
        assertEquals(1, holders.length);

        // Verify servlet annotations
        ServletHolder cholder = holders[0];
        assertThat("Servlet Name", cholder.getName(), is("CServlet"));
        assertThat("InitParameter[x]", cholder.getInitParameter("x"), is("y"));
        assertThat("Init Order", cholder.getInitOrder(), is(2));
        assertThat("Async Supported", cholder.isAsyncSupported(), is(false));

        // Verify mappings
        ServletMapping[] mappings = wac.getServletHandler().getServletMappings();
        assertNotNull(mappings);
        assertEquals(1, mappings.length);
        String[] paths = mappings[0].getPathSpecs();
        assertNotNull(paths);
        assertEquals(2, paths.length);
    }


    @Test
    public void testWebServletAnnotationOverrideDefault () throws Exception
    {
        //if the existing servlet mapping TO A DIFFERENT SERVLET IS from a default descriptor we
        //DO allow the annotation to replace the mapping.

        WebAppContext wac = new WebAppContext();
        ServletHolder defaultServlet = new ServletHolder();
        defaultServlet.setClassName("org.eclipse.jetty.servlet.DefaultServlet");
        defaultServlet.setName("default");
        wac.getServletHandler().addServlet(defaultServlet);

        ServletMapping m = new ServletMapping();
        m.setPathSpec("/");
        m.setServletName("default");
        m.setDefault(true);  //this mapping will be from a default descriptor
        wac.getServletHandler().addServletMapping(m);

        WebServletAnnotation annotation = new WebServletAnnotation(wac, "org.eclipse.jetty.annotations.ServletD", null);
        annotation.apply();

        //test that as the original servlet mapping had only 1 pathspec, then the whole
        //servlet mapping should be deleted as that pathspec will be remapped to the DServlet
        ServletMapping[] resultMappings = wac.getServletHandler().getServletMappings();
        assertNotNull(resultMappings);
        assertEquals(1, resultMappings.length);
        assertEquals(2, resultMappings[0].getPathSpecs().length);
        resultMappings[0].getServletName().equals("DServlet");
        for (String s:resultMappings[0].getPathSpecs())
        {
            assertTrue (s.equals("/") || s.equals("/bah/*"));
        }
    }



    @Test
    public void testWebServletAnnotationReplaceDefault () throws Exception
    {
        //if the existing servlet mapping TO A DIFFERENT SERVLET IS from a default descriptor we
        //DO allow the annotation to replace the mapping.
        WebAppContext wac = new WebAppContext();
        ServletHolder defaultServlet = new ServletHolder();
        defaultServlet.setClassName("org.eclipse.jetty.servlet.DefaultServlet");
        defaultServlet.setName("default");
        wac.getServletHandler().addServlet(defaultServlet);

        ServletMapping m = new ServletMapping();
        m.setPathSpec("/");
        m.setServletName("default");
        m.setDefault(true);  //this mapping will be from a default descriptor
        wac.getServletHandler().addServletMapping(m);

        ServletMapping m2 = new ServletMapping();
        m2.setPathSpec("/other");
        m2.setServletName("default");
        m2.setDefault(true);  //this mapping will be from a default descriptor
        wac.getServletHandler().addServletMapping(m2);

        WebServletAnnotation annotation = new WebServletAnnotation(wac, "org.eclipse.jetty.annotations.ServletD", null);
        annotation.apply();

        //test that only the mapping for "/" was removed from the mappings to the default servlet
        ServletMapping[] resultMappings = wac.getServletHandler().getServletMappings();
        assertNotNull(resultMappings);
        assertEquals(2, resultMappings.length);
        for (ServletMapping r:resultMappings)
        {
           if (r.getServletName().equals("default"))
           {
               assertEquals(1,r.getPathSpecs().length);
               assertEquals("/other", r.getPathSpecs()[0]);
           }
           else if (r.getServletName().equals("DServlet"))
           {
               assertEquals(2,r.getPathSpecs().length);
               for (String p:r.getPathSpecs())
               {
                   if (!p.equals("/") && !p.equals("/bah/*"))
                       fail("Unexpected path");
               }
           }
           else
               fail("Unexpected servlet mapping");
        }

    }


    @Test
    public void testWebServletAnnotationNotOverride () throws Exception
    {
        //if the existing servlet mapping TO A DIFFERENT SERVLET IS NOT from a default descriptor we
        //DO NOT allow the annotation to replace the mapping
        WebAppContext wac = new WebAppContext();
        ServletHolder servlet = new ServletHolder();
        servlet.setClassName("org.eclipse.jetty.servlet.FooServlet");
        servlet.setName("foo");
        wac.getServletHandler().addServlet(servlet);
        ServletMapping m = new ServletMapping();
        m.setPathSpec("/");
        m.setServletName("foo");
        wac.getServletHandler().addServletMapping(m);

        WebServletAnnotation annotation = new WebServletAnnotation(wac, "org.eclipse.jetty.annotations.ServletD", null);
        annotation.apply();

        ServletMapping[] resultMappings = wac.getServletHandler().getServletMappings();
        assertEquals(2, resultMappings.length);
        for (ServletMapping r:resultMappings)
        {
            if (r.getServletName().equals("DServlet"))
            {
                assertEquals(2, r.getPathSpecs().length);
            }
            else if (r.getServletName().equals("foo"))
            {
                assertEquals(1, r.getPathSpecs().length);
            }
            else
                fail("Unexpected servlet name");
        }
    }

    @Test
    public void testWebServletAnnotationIgnore () throws Exception
    {
        //an existing servlet OF THE SAME NAME has even 1 non-default mapping we can't use
        //any of the url mappings in the annotation
        WebAppContext wac = new WebAppContext();
        ServletHolder servlet = new ServletHolder();
        servlet.setClassName("org.eclipse.jetty.servlet.OtherDServlet");
        servlet.setName("DServlet");
        wac.getServletHandler().addServlet(servlet);

        ServletMapping m = new ServletMapping();
        m.setPathSpec("/default");
        m.setDefault(true);
        m.setServletName("DServlet");
        wac.getServletHandler().addServletMapping(m);

        ServletMapping m2 = new ServletMapping();
        m2.setPathSpec("/other");
        m2.setServletName("DServlet");
        wac.getServletHandler().addServletMapping(m2);

        WebServletAnnotation annotation = new WebServletAnnotation(wac, "org.eclipse.jetty.annotations.ServletD", null);
        annotation.apply();

        ServletMapping[] resultMappings = wac.getServletHandler().getServletMappings();
        assertEquals(2, resultMappings.length);

        for (ServletMapping r:resultMappings)
        {
            assertEquals(1, r.getPathSpecs().length);
            if (!r.getPathSpecs()[0].equals("/default") && !r.getPathSpecs()[0].equals("/other"))
                fail("Unexpected path in mapping");
        }

    }

    @Test
    public void testWebServletAnnotationNoMappings () throws Exception
    {
        //an existing servlet OF THE SAME NAME has no mappings, therefore all mappings in the annotation
        //should be accepted
        WebAppContext wac = new WebAppContext();
        ServletHolder servlet = new ServletHolder();
        servlet.setName("foo");
        wac.getServletHandler().addServlet(servlet);


        WebServletAnnotation annotation = new WebServletAnnotation(wac, "org.eclipse.jetty.annotations.ServletD", null);
        annotation.apply();

        ServletMapping[] resultMappings = wac.getServletHandler().getServletMappings();
        assertEquals(1, resultMappings.length);
        assertEquals(2, resultMappings[0].getPathSpecs().length);
        for (String s:resultMappings[0].getPathSpecs())
        {
            if (!s.equals("/") && !s.equals("/bah/*"))
                fail("Unexpected path mapping");
        }
    }



    @Test
    public void testDeclareRoles ()
    throws Exception
    {
        WebAppContext wac = new WebAppContext();
        ConstraintSecurityHandler sh = new ConstraintSecurityHandler();
        wac.setSecurityHandler(sh);
        sh.setRoles(new HashSet<String>(Arrays.asList(new String[]{"humpty", "dumpty"})));
        DeclareRolesAnnotationHandler handler = new DeclareRolesAnnotationHandler(wac);
        handler.doHandle(ServletC.class);
        assertTrue(sh.getRoles().contains("alice"));
        assertTrue(sh.getRoles().contains("humpty"));
        assertTrue(sh.getRoles().contains("dumpty"));
    }
}
