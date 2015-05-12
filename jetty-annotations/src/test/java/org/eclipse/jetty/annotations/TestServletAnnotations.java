//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

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
       
        parser.parse(Collections.singleton(handler), classes, new ClassNameResolver ()
        {
            public boolean isExcluded(String name)
            {
                return false;
            }

            public boolean shouldOverride(String name)
            {
                return false;
            }
        });

  
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
