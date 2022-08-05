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

package org.eclipse.jetty.ee10.annotations;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.servlet.ServletMapping;
import org.eclipse.jetty.ee10.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee10.webapp.DiscoveredAnnotation;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TestServletAnnotations
 */
@ExtendWith(WorkDirExtension.class)
public class TestServletAnnotations
{
    public WorkDir workDir;

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
        Path root = workDir.getEmptyPathDir();
        copyClass(org.eclipse.jetty.ee10.annotations.ServletC.class, root);

        AnnotationParser parser = new AnnotationParser();

        WebAppContext wac = new WebAppContext();
        List<DiscoveredAnnotation> results = new ArrayList<DiscoveredAnnotation>();

        TestWebServletAnnotationHandler handler = new TestWebServletAnnotationHandler(wac, results);

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            parser.parse(Collections.singleton(handler), resourceFactory.newResource(root));
        }

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
    public void testWebServletAnnotationOverrideDefault()
    {
        //if the existing servlet mapping TO A DIFFERENT SERVLET IS from a default descriptor we
        //DO allow the annotation to replace the mapping.

        WebAppContext wac = new WebAppContext();
        ServletHolder defaultServlet = new ServletHolder();
        defaultServlet.setClassName("org.eclipse.jetty.ee10.servlet.DefaultServlet");
        defaultServlet.setName("default");
        wac.getServletHandler().addServlet(defaultServlet);

        ServletMapping m = new ServletMapping();
        m.setPathSpec("/");
        m.setServletName("default");
        m.setFromDefaultDescriptor(true);  //this mapping will be from a default descriptor
        wac.getServletHandler().addServletMapping(m);

        WebServletAnnotation annotation = new WebServletAnnotation(wac, "org.eclipse.jetty.ee10.annotations.ServletD", null);
        annotation.apply();

        //test that as the original servlet mapping had only 1 pathspec, then the whole
        //servlet mapping should be deleted as that pathspec will be remapped to the DServlet
        ServletMapping[] resultMappings = wac.getServletHandler().getServletMappings();
        assertNotNull(resultMappings);
        assertEquals(1, resultMappings.length);
        assertEquals(2, resultMappings[0].getPathSpecs().length);
        resultMappings[0].getServletName().equals("DServlet");
        for (String s : resultMappings[0].getPathSpecs())
        {
            assertThat(s, anyOf(is("/"), is("/bah/*")));
        }
    }

    @Test
    public void testWebServletAnnotationReplaceDefault()
    {
        //if the existing servlet mapping TO A DIFFERENT SERVLET IS from a default descriptor we
        //DO allow the annotation to replace the mapping.
        WebAppContext wac = new WebAppContext();
        ServletHolder defaultServlet = new ServletHolder();
        defaultServlet.setClassName("org.eclipse.jetty.ee10.servlet.DefaultServlet");
        defaultServlet.setName("default");
        wac.getServletHandler().addServlet(defaultServlet);

        ServletMapping m = new ServletMapping();
        m.setPathSpec("/");
        m.setServletName("default");
        m.setFromDefaultDescriptor(true);  //this mapping will be from a default descriptor
        wac.getServletHandler().addServletMapping(m);

        ServletMapping m2 = new ServletMapping();
        m2.setPathSpec("/other");
        m2.setServletName("default");
        m2.setFromDefaultDescriptor(true);  //this mapping will be from a default descriptor
        wac.getServletHandler().addServletMapping(m2);

        WebServletAnnotation annotation = new WebServletAnnotation(wac, "org.eclipse.jetty.ee10.annotations.ServletD", null);
        annotation.apply();

        //test that only the mapping for "/" was removed from the mappings to the default servlet
        ServletMapping[] resultMappings = wac.getServletHandler().getServletMappings();
        assertNotNull(resultMappings);
        assertEquals(2, resultMappings.length);
        for (ServletMapping r : resultMappings)
        {
            if (r.getServletName().equals("default"))
            {
                assertEquals(1, r.getPathSpecs().length);
                assertEquals("/other", r.getPathSpecs()[0]);
            }
            else if (r.getServletName().equals("DServlet"))
            {
                assertEquals(2, r.getPathSpecs().length);
                for (String p : r.getPathSpecs())
                {
                    if (!p.equals("/") && !p.equals("/bah/*"))
                        fail("Unexpected path");
                }
            }
            else
                fail("Unexpected servlet mapping: " + r);
        }
    }

    @Test
    public void testWebServletAnnotationNotOverride()
    {
        //if the existing servlet mapping TO A DIFFERENT SERVLET IS NOT from a default descriptor we
        //DO NOT allow the annotation to replace the mapping
        WebAppContext wac = new WebAppContext();
        ServletHolder servlet = new ServletHolder();
        servlet.setClassName("org.eclipse.jetty.ee10.servlet.FooServlet");
        servlet.setName("foo");
        wac.getServletHandler().addServlet(servlet);
        ServletMapping m = new ServletMapping();
        m.setPathSpec("/");
        m.setServletName("foo");
        wac.getServletHandler().addServletMapping(m);

        WebServletAnnotation annotation = new WebServletAnnotation(wac, "org.eclipse.jetty.ee10.annotations.ServletD", null);
        annotation.apply();

        ServletMapping[] resultMappings = wac.getServletHandler().getServletMappings();
        assertEquals(2, resultMappings.length);
        for (ServletMapping r : resultMappings)
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
                fail("Unexpected servlet name: " + r);
        }
    }

    @Test
    public void testWebServletAnnotationIgnore()
    {
        //an existing servlet OF THE SAME NAME has even 1 non-default mapping we can't use
        //any of the url mappings in the annotation
        WebAppContext wac = new WebAppContext();
        ServletHolder servlet = new ServletHolder();
        servlet.setClassName("org.eclipse.jetty.ee10.servlet.OtherDServlet");
        servlet.setName("DServlet");
        wac.getServletHandler().addServlet(servlet);

        ServletMapping m = new ServletMapping();
        m.setPathSpec("/default");
        m.setFromDefaultDescriptor(true);
        m.setServletName("DServlet");
        wac.getServletHandler().addServletMapping(m);

        ServletMapping m2 = new ServletMapping();
        m2.setPathSpec("/other");
        m2.setServletName("DServlet");
        wac.getServletHandler().addServletMapping(m2);

        WebServletAnnotation annotation = new WebServletAnnotation(wac, "org.eclipse.jetty.ee10.annotations.ServletD", null);
        annotation.apply();

        ServletMapping[] resultMappings = wac.getServletHandler().getServletMappings();
        assertEquals(2, resultMappings.length);

        for (ServletMapping r : resultMappings)
        {
            assertEquals(1, r.getPathSpecs().length);
            if (!r.getPathSpecs()[0].equals("/default") && !r.getPathSpecs()[0].equals("/other"))
                fail("Unexpected path in mapping: " + r);
        }
    }

    @Test
    public void testWebServletAnnotationNoMappings()
    {
        //an existing servlet OF THE SAME NAME has no mappings, therefore all mappings in the annotation
        //should be accepted
        WebAppContext wac = new WebAppContext();
        ServletHolder servlet = new ServletHolder();
        servlet.setName("foo");
        wac.getServletHandler().addServlet(servlet);

        WebServletAnnotation annotation = new WebServletAnnotation(wac, "org.eclipse.jetty.ee10.annotations.ServletD", null);
        annotation.apply();

        ServletMapping[] resultMappings = wac.getServletHandler().getServletMappings();
        assertEquals(1, resultMappings.length);
        assertEquals(2, resultMappings[0].getPathSpecs().length);
        for (String s : resultMappings[0].getPathSpecs())
        {
            assertThat(s, anyOf(is("/"), is("/bah/*")));
        }
    }

    @Test
    public void testDeclareRoles()
    {
        WebAppContext wac = new WebAppContext();
        ConstraintSecurityHandler sh = new ConstraintSecurityHandler();
        wac.setSecurityHandler(sh);
        sh.setRoles(Set.of("humpty", "dumpty"));
        DeclareRolesAnnotationHandler handler = new DeclareRolesAnnotationHandler(wac);
        handler.doHandle(ServletC.class);
        assertThat(sh.getRoles(), containsInAnyOrder("humpty", "alice", "dumpty"));
    }

    private void copyClass(Class<?> clazz, Path outputDir) throws IOException, URISyntaxException
    {
        String classRef = TypeUtil.toClassReference(clazz);
        URL url = this.getClass().getResource('/' + classRef);
        assertThat("URL for: " + classRef, url, notNullValue());

        Path srcClass = Paths.get(url.toURI());
        Path dest = outputDir.resolve(classRef);
        FS.ensureDirExists(dest.getParent());
        Files.copy(srcClass, dest);
    }
}
