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

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.Container;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServletHandlerTest
{
    FilterHolder fh1 = new FilterHolder(new Source(Source.Origin.DESCRIPTOR, "foo.xml"));
    FilterMapping fm1 = new FilterMapping();

    FilterHolder fh2 = new FilterHolder(new Source(Source.Origin.DESCRIPTOR, "foo.xml"));
    FilterMapping fm2 = new FilterMapping();

    FilterHolder fh3 = new FilterHolder(Source.JAVAX_API);
    FilterMapping fm3 = new FilterMapping();

    FilterHolder fh4 = new FilterHolder(Source.JAVAX_API);
    FilterMapping fm4 = new FilterMapping();

    FilterHolder fh5 = new FilterHolder(Source.JAVAX_API);
    FilterMapping fm5 = new FilterMapping();

    ServletHolder sh1 = new ServletHolder(new Source(Source.Origin.DESCRIPTOR, "foo.xml"));
    ServletMapping sm1 = new ServletMapping();

    ServletHolder sh2 = new ServletHolder(new Source(Source.Origin.DESCRIPTOR, "foo.xml"));
    ServletMapping sm2 = new ServletMapping();

    ServletHolder sh3 = new ServletHolder(new Source(Source.Origin.DESCRIPTOR, "foo.xml"));
    ServletMapping sm3 = new ServletMapping();

    @BeforeEach
    public void initMappings()
    {
        fh1.setName("fh1");
        fm1.setPathSpec("/*");
        fm1.setFilterHolder(fh1);

        fh2.setName("fh2");
        fm2.setPathSpec("/*");
        fm2.setFilterHolder(fh2);

        fh3.setName("fh3");
        fm3.setPathSpec("/*");
        fm3.setFilterHolder(fh3);

        fh4.setName("fh4");
        fm4.setPathSpec("/*");
        fm4.setFilterHolder(fh4);

        fh5.setName("fh5");
        fm5.setPathSpec("/*");
        fm5.setFilterHolder(fh5);

        sh1.setName("s1");
        sm1.setDefault(false);
        sm1.setPathSpec("/foo/*");
        sm1.setServletName("s1");

        sh2.setName("s2");
        sm2.setDefault(false);
        sm2.setPathSpec("/foo/*");
        sm2.setServletName("s2");

        sh3.setName("s3");
        sm3.setDefault(true);
        sm3.setPathSpec("/foo/*");
        sm3.setServletName("s3");
    }

    @Test
    public void testAddFilterIgnoresDuplicates() throws Exception
    {

        ServletHandler handler = new ServletHandler();
        FilterHolder h = new FilterHolder();
        h.setName("x");
        handler.addFilter(h);
        FilterHolder[] holders = handler.getFilters();
        assertNotNull(holders);
        assertTrue(holders[0] == h);

        handler.addFilter(h);
        holders = handler.getFilters();
        assertNotNull(holders);
        assertTrue(holders.length == 1);
        assertTrue(holders[0] == h);

        FilterHolder h2 = new FilterHolder();
        h2.setName("x"); //not allowed by servlet spec, just here to test object equality
        handler.addFilter(h2);
        holders = handler.getFilters();
        assertNotNull(holders);
        assertTrue(holders.length == 2);
        assertTrue(holders[1] == h2);
    }

    @Test
    public void testAddFilterIgnoresDuplicates2() throws Exception
    {

        ServletHandler handler = new ServletHandler();
        FilterHolder h = new FilterHolder();
        h.setName("x");
        FilterMapping m = new FilterMapping();
        m.setPathSpec("/*");
        m.setFilterHolder(h);

        handler.addFilter(h, m);
        FilterHolder[] holders = handler.getFilters();
        assertNotNull(holders);
        assertTrue(holders[0] == h);

        FilterMapping m2 = new FilterMapping();
        m2.setPathSpec("/*");
        m2.setFilterHolder(h);
        handler.addFilter(h, m2);
        holders = handler.getFilters();
        assertNotNull(holders);
        assertTrue(holders.length == 1);
        assertTrue(holders[0] == h);

        FilterHolder h2 = new FilterHolder();
        h2.setName("x"); //not allowed by servlet spec, just here to test object equality
        FilterMapping m3 = new FilterMapping();
        m3.setPathSpec("/*");
        m3.setFilterHolder(h);

        handler.addFilter(h2, m3);
        holders = handler.getFilters();
        assertNotNull(holders);
        assertTrue(holders.length == 2);
        assertTrue(holders[1] == h2);
    }

    @Test
    public void testAddFilterWithMappingIgnoresDuplicateFilters() throws Exception
    {
        ServletHandler handler = new ServletHandler();
        FilterHolder h = new FilterHolder();
        h.setName("x");

        handler.addFilterWithMapping(h, "/*", 0);
        FilterHolder[] holders = handler.getFilters();
        assertNotNull(holders);
        assertTrue(holders[0] == h);

        handler.addFilterWithMapping(h, "/*", 1);
        holders = handler.getFilters();
        assertNotNull(holders);
        assertTrue(holders.length == 1);
        assertTrue(holders[0] == h);

        FilterHolder h2 = new FilterHolder();
        h2.setName("x"); //not allowed by servlet spec, just here to test object equality

        handler.addFilterWithMapping(h2, "/*", 0);
        holders = handler.getFilters();
        assertNotNull(holders);
        assertTrue(holders.length == 2);
        assertTrue(holders[1] == h2);
    }

    @Test
    public void testAddFilterWithMappingIngoresDuplicateFilters2() throws Exception
    {
        ServletHandler handler = new ServletHandler();
        FilterHolder h = new FilterHolder();
        h.setName("x");

        handler.addFilterWithMapping(h, "/*", EnumSet.allOf(DispatcherType.class));
        FilterHolder[] holders = handler.getFilters();
        assertNotNull(holders);
        assertTrue(holders[0] == h);

        handler.addFilterWithMapping(h, "/x", EnumSet.allOf(DispatcherType.class));
        holders = handler.getFilters();
        assertNotNull(holders);
        assertTrue(holders.length == 1);
        assertTrue(holders[0] == h);

        FilterHolder h2 = new FilterHolder();
        h2.setName("x"); //not allowed by servlet spec, just here to test object equality

        handler.addFilterWithMapping(h2, "/*", EnumSet.allOf(DispatcherType.class));
        holders = handler.getFilters();
        assertNotNull(holders);
        assertTrue(holders.length == 2);
        assertTrue(holders[1] == h2);
    }

    @Test
    public void testDuplicateMappingsForbidden() throws Exception
    {
        ServletHandler handler = new ServletHandler();
        handler.setAllowDuplicateMappings(false);
        handler.addServlet(sh1);
        handler.addServlet(sh2);
        handler.updateNameMappings();

        handler.addServletMapping(sm1);
        handler.addServletMapping(sm2);

        try
        {
            handler.updateMappings();
        }
        catch (IllegalStateException e)
        {
            //expected error
        }
    }

    @Test
    public void testDuplicateMappingsWithDefaults() throws Exception
    {
        ServletHandler handler = new ServletHandler();
        handler.setAllowDuplicateMappings(false);
        handler.addServlet(sh1);
        handler.addServlet(sh3);
        handler.updateNameMappings();

        handler.addServletMapping(sm3);
        handler.addServletMapping(sm1);

        handler.updateMappings();

        MappedResource<ServletHolder> entry = handler.getMappedServlet("/foo/*");
        assertNotNull(entry);
        assertEquals("s1", entry.getResource().getName());
    }

    @Test
    public void testDuplicateMappingsSameServlet() throws Exception
    {
        ServletHolder sh4 = new ServletHolder();

        sh4.setName("s1");

        ServletMapping sm4 = new ServletMapping();
        sm4.setPathSpec("/foo/*");
        sm4.setServletName("s1");

        ServletHandler handler = new ServletHandler();
        handler.setAllowDuplicateMappings(true);
        handler.addServlet(sh1);
        handler.addServlet(sh4);
        handler.updateNameMappings();

        handler.addServletMapping(sm1);
        handler.addServletMapping(sm4);

        handler.updateMappings();
    }

    @Test
    public void testDuplicateMappingsAllowed() throws Exception
    {
        ServletHandler handler = new ServletHandler();
        handler.setAllowDuplicateMappings(true);
        handler.addServlet(sh1);
        handler.addServlet(sh2);
        handler.updateNameMappings();

        handler.addServletMapping(sm1);
        handler.addServletMapping(sm2);
        handler.updateMappings();

        MappedResource<ServletHolder> entry = handler.getMappedServlet("/foo/*");
        assertNotNull(entry);
        assertEquals("s2", entry.getResource().getName());
    }

    @Test
    public void testAllNonProgrammaticFilterMappings() throws Exception
    {
        ServletHandler handler = new ServletHandler();
        handler.addFilter(fh1);
        handler.addFilter(fh2);

        //add some ordinary filter mappings
        handler.addFilterMapping(fm1);
        handler.addFilterMapping(fm2);

        FilterMapping[] mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertTrue(fm1 == mappings[0]);
        assertTrue(fm2 == mappings[1]);

        //add another ordinary mapping
        FilterHolder of1 = new FilterHolder(new Source(Source.Origin.DESCRIPTOR, "foo.xml"));
        of1.setName("foo");
        FilterMapping ofm1 = new FilterMapping();
        ofm1.setFilterHolder(of1);
        ofm1.setPathSpec("/*");
        handler.addFilter(of1);
        handler.addFilterMapping(ofm1);

        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertTrue(fm1 == mappings[0]);
        assertTrue(fm2 == mappings[1]);
        assertTrue(ofm1 == mappings[2]);
    }

    @Test
    public void testAllBeforeFilterMappings() throws Exception
    {
        ServletHandler handler = new ServletHandler();

        //do equivalent of FilterRegistration.addMappingForUrlPatterns(isMatchAfter=false)
        handler.addFilter(fh4);
        handler.prependFilterMapping(fm4);

        FilterMapping[] mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(1, mappings.length);

        //add another with isMatchAfter=false
        handler.addFilter(fh5);
        handler.prependFilterMapping(fm5);

        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(2, mappings.length);

        assertTrue(fm4 == mappings[0]);
        assertTrue(fm5 == mappings[1]);
    }

    @Test
    public void testAllAfterFilterMappings() throws Exception
    {
        ServletHandler handler = new ServletHandler();
        //do equivalent of FilterRegistration.addMappingForUrlPatterns(isMatchAfter=true)
        handler.addFilter(fh4);
        handler.addFilterMapping(fm4);
        FilterMapping[] mappings = handler.getFilterMappings();
        assertEquals(1, mappings.length);
        assertTrue(fm4 == mappings[0]);

        //do equivalent of FilterRegistration.addMappingForUrlPatterns(isMatchAfter=true)
        handler.addFilter(fh5);
        handler.addFilterMapping(fm5);
        mappings = handler.getFilterMappings();
        assertEquals(2, mappings.length);
        assertTrue(fm4 == mappings[0]);
        assertTrue(fm5 == mappings[1]);
    }

    @Test
    public void testMatchAfterAndBefore() throws Exception
    {
        ServletHandler handler = new ServletHandler();

        //add a programmatic one, isMatchAfter=true
        handler.addFilter(fh3);
        handler.addFilterMapping(fm3);
        FilterMapping[] mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(1, mappings.length);
        assertTrue(fm3 == mappings[0]);

        //add a programmatic one, isMatchAfter=false
        handler.addFilter(fh4);
        handler.prependFilterMapping(fm4);
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(2, mappings.length);
        assertTrue(fm4 == mappings[0]);
        assertTrue(fm3 == mappings[1]);
    }

    @Test
    public void testMatchBeforeAndAfter() throws Exception
    {
        ServletHandler handler = new ServletHandler();

        //add a programmatic one, isMatchAfter=false
        handler.addFilter(fh3);
        handler.prependFilterMapping(fm3);
        FilterMapping[] mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(1, mappings.length);
        assertTrue(fm3 == mappings[0]);

        //add a programmatic one, isMatchAfter=true
        handler.addFilter(fh4);
        handler.addFilterMapping(fm4);
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(2, mappings.length);
        assertTrue(fm3 == mappings[0]);
        assertTrue(fm4 == mappings[1]);
    }

    @Test
    public void testExistingFilterMappings() throws Exception
    {
        ServletHandler handler = new ServletHandler();
        handler.addFilter(fh1);
        handler.addFilter(fh2);

        //add some ordinary filter mappings first
        handler.addFilterMapping(fm1);
        handler.addFilterMapping(fm2);

        FilterMapping[] mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertTrue(fm1 == mappings[0]);
        assertTrue(fm2 == mappings[1]);

        //do equivalent of FilterRegistration.addMappingForUrlPatterns(isMatchAfter=false)
        handler.addFilter(fh4);
        handler.prependFilterMapping(fm4);
        mappings = handler.getFilterMappings();
        assertEquals(3, mappings.length);
        assertTrue(fm4 == mappings[0]);

        //do equivalent of FilterRegistration.addMappingForUrlPatterns(isMatchAfter=true)
        handler.addFilter(fh5);
        handler.addFilterMapping(fm5);
        mappings = handler.getFilterMappings();
        assertEquals(4, mappings.length);
        assertTrue(fm5 == mappings[mappings.length - 1]);
    }

    @Test
    public void testFilterMappingNoFilter() throws Exception
    {
        FilterMapping mapping = new FilterMapping();
        mapping.setPathSpec("/*");
        mapping.setFilterName("foo");
        //default dispatch is REQUEST, and there is no holder to check for async supported
        assertFalse(mapping.appliesTo(DispatcherType.ASYNC));
    }

    @Test
    public void testFilterMappingsMix() throws Exception
    {
        ServletHandler handler = new ServletHandler();

        //add a non-programmatic one to begin with
        handler.addFilter(fh1);
        handler.addFilterMapping(fm1);
        FilterMapping[] mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertTrue(fm1 == mappings[0]);

        //add a programmatic one, isMatchAfter=false
        handler.addFilter(fh4);
        handler.prependFilterMapping(fm4);
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(2, mappings.length);
        assertTrue(fm4 == mappings[0]);
        assertTrue(fm1 == mappings[1]);

        //add a programmatic one, isMatchAfter=true
        handler.addFilter(fh3);
        handler.addFilterMapping(fm3);
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(3, mappings.length);
        assertTrue(fm4 == mappings[0]);
        assertTrue(fm1 == mappings[1]);
        assertTrue(fm3 == mappings[2]);

        //add a programmatic one, isMatchAfter=false
        handler.addFilter(fh5);
        handler.prependFilterMapping(fm5);
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(4, mappings.length);
        assertTrue(fm4 == mappings[0]); //isMatchAfter = false;
        assertTrue(fm5 == mappings[1]); //isMatchAfter = false;
        assertTrue(fm1 == mappings[2]); //ordinary
        assertTrue(fm3 == mappings[3]); //isMatchAfter = true;

        //add a non-programmatic one
        FilterHolder f = new FilterHolder(Source.EMBEDDED);
        f.setName("non-programmatic");
        FilterMapping fm = new FilterMapping();
        fm.setFilterHolder(f);
        fm.setPathSpec("/*");
        handler.addFilter(f);
        handler.addFilterMapping(fm);
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(5, mappings.length);
        assertTrue(fm4 == mappings[0]); //isMatchAfter = false;
        assertTrue(fm5 == mappings[1]); //isMatchAfter = false;
        assertTrue(fm1 == mappings[2]); //ordinary
        assertTrue(fm == mappings[3]);  //ordinary
        assertTrue(fm3 == mappings[4]); //isMatchAfter = true;

        //add a programmatic one, isMatchAfter=true
        FilterHolder pf = new FilterHolder(Source.JAVAX_API);
        pf.setName("programmaticA");
        FilterMapping pfm = new FilterMapping();
        pfm.setFilterHolder(pf);
        pfm.setPathSpec("/*");
        handler.addFilter(pf);
        handler.addFilterMapping(pfm);
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(6, mappings.length);
        assertTrue(fm4 == mappings[0]); //isMatchAfter = false;
        assertTrue(fm5 == mappings[1]); //isMatchAfter = false;
        assertTrue(fm1 == mappings[2]); //ordinary
        assertTrue(fm == mappings[3]);  //ordinary
        assertTrue(fm3 == mappings[4]); //isMatchAfter = true;
        assertTrue(pfm == mappings[5]); //isMatchAfter = true;

        //add a programmatic one, isMatchAfter=false
        FilterHolder pf2 = new FilterHolder(Source.JAVAX_API);
        pf2.setName("programmaticB");
        FilterMapping pfm2 = new FilterMapping();
        pfm2.setFilterHolder(pf2);
        pfm2.setPathSpec("/*");
        handler.addFilter(pf2);
        handler.prependFilterMapping(pfm2);
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(7, mappings.length);
        assertTrue(fm4 == mappings[0]); //isMatchAfter = false;
        assertTrue(fm5 == mappings[1]); //isMatchAfter = false;
        assertTrue(pfm2 == mappings[2]); //isMatchAfter = false;
        assertTrue(fm1 == mappings[3]); //ordinary
        assertTrue(fm == mappings[4]);  //ordinary
        assertTrue(fm3 == mappings[5]); //isMatchAfter = true;
        assertTrue(pfm == mappings[6]); //isMatchAfter = true;
    }

    @Test
    public void testAddFilterWithMappingAPI() throws Exception
    {
        ServletHandler handler = new ServletHandler();

        //add a non-programmatic one to begin with
        handler.addFilterWithMapping(fh1, "/*", EnumSet.allOf(DispatcherType.class));
        handler.updateMappings();
        FilterMapping[] mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertTrue(fh1 == mappings[0].getFilterHolder());

        //add a programmatic one, isMatchAfter=false
        fh4.setServletHandler(handler);
        handler.addFilter(fh4);
        fh4.getRegistration().addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), false, "/*");
        handler.updateMappings();
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(2, mappings.length);
        assertTrue(fh4 == mappings[0].getFilterHolder());
        assertTrue(fh1 == mappings[1].getFilterHolder());

        //add a programmatic one, isMatchAfter=true
        fh3.setServletHandler(handler);
        handler.addFilter(fh3);
        fh3.getRegistration().addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), true, "/*");
        handler.updateMappings();
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(3, mappings.length);
        assertTrue(fh4 == mappings[0].getFilterHolder());
        assertTrue(fh1 == mappings[1].getFilterHolder());
        assertTrue(fh3 == mappings[2].getFilterHolder());

        //add a programmatic one, isMatchAfter=false
        fh5.setServletHandler(handler);
        handler.addFilter(fh5);
        fh5.getRegistration().addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), false, "/*");
        handler.updateMappings();
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(4, mappings.length);
        assertTrue(fh4 == mappings[0].getFilterHolder()); //isMatchAfter = false;
        assertTrue(fh5 == mappings[1].getFilterHolder()); //isMatchAfter = false;
        assertTrue(fh1 == mappings[2].getFilterHolder()); //ordinary
        assertTrue(fh3 == mappings[3].getFilterHolder()); //isMatchAfter = true;

        //add a non-programmatic one
        FilterHolder f = new FilterHolder(Source.EMBEDDED);
        f.setName("non-programmatic");
        handler.addFilterWithMapping(f, "/*", EnumSet.allOf(DispatcherType.class));
        handler.updateMappings();
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(5, mappings.length);
        assertTrue(fh4 == mappings[0].getFilterHolder()); //isMatchAfter = false;
        assertTrue(fh5 == mappings[1].getFilterHolder()); //isMatchAfter = false;
        assertTrue(fh1 == mappings[2].getFilterHolder()); //ordinary
        assertTrue(f == mappings[3].getFilterHolder());  //ordinary
        assertTrue(fh3 == mappings[4].getFilterHolder()); //isMatchAfter = true;

        //add a programmatic one, isMatchAfter=true
        FilterHolder pf = new FilterHolder(Source.JAVAX_API);
        pf.setServletHandler(handler);
        pf.setName("programmaticA");
        handler.addFilter(pf);
        pf.getRegistration().addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), true, "/*");
        handler.updateMappings();
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(6, mappings.length);
        assertTrue(fh4 == mappings[0].getFilterHolder()); //isMatchAfter = false;
        assertTrue(fh5 == mappings[1].getFilterHolder()); //isMatchAfter = false;
        assertTrue(fh1 == mappings[2].getFilterHolder()); //ordinary
        assertTrue(f == mappings[3].getFilterHolder());  //ordinary
        assertTrue(fh3 == mappings[4].getFilterHolder()); //isMatchAfter = true;
        assertTrue(pf == mappings[5].getFilterHolder()); //isMatchAfter = true;

        //add a programmatic one, isMatchAfter=false
        FilterHolder pf2 = new FilterHolder(Source.JAVAX_API);
        pf2.setServletHandler(handler);
        pf2.setName("programmaticB");
        handler.addFilter(pf2);
        pf2.getRegistration().addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), false, "/*");
        handler.updateMappings();
        mappings = handler.getFilterMappings();

        assertNotNull(mappings);
        assertEquals(7, mappings.length);
        assertTrue(fh4 == mappings[0].getFilterHolder()); //isMatchAfter = false;
        assertTrue(fh5 == mappings[1].getFilterHolder()); //isMatchAfter = false;
        assertTrue(pf2 == mappings[2].getFilterHolder()); //isMatchAfter = false;
        assertTrue(fh1 == mappings[3].getFilterHolder()); //ordinary
        assertTrue(f == mappings[4].getFilterHolder());  //ordinary
        assertTrue(fh3 == mappings[5].getFilterHolder()); //isMatchAfter = true;
        assertTrue(pf == mappings[6].getFilterHolder()); //isMatchAfter = true;
    }
    
    @Test
    public void testFiltersServletsListenersAsBeans() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        
        ServletHandler handler = context.getServletHandler();
        
        //test that filters, servlets and listeners are added as beans
        //and thus reported in a Container.Listener
        List<Object> addResults = new ArrayList<>();
        List<Object> removeResults = new ArrayList<>();
        handler.addEventListener(new Container.Listener()
        {
            @Override
            public void beanAdded(Container parent, Object child)
            {
                addResults.add(child);
            }

            @Override
            public void beanRemoved(Container parent, Object child)
            {
                removeResults.add(child);
            }
        
        });

        handler.addFilter(fh1);
        handler.addServlet(sh1);
        ListenerHolder lh1 = new ListenerHolder(new Source(Source.Origin.DESCRIPTOR, "foo.xml"));
        lh1.setInstance(new HttpSessionListener()
        {  
            @Override
            public void sessionDestroyed(HttpSessionEvent se)
            {
            }
            
            @Override
            public void sessionCreated(HttpSessionEvent se)
            {   
            }
        });
        handler.addListener(lh1);
        
        assertTrue(addResults.contains(fh1));
        assertTrue(addResults.contains(sh1));
        assertTrue(addResults.contains(lh1));
        
        //test that servlets, filters and listeners are dumped, but
        //not as beans
        String dump = handler.dump();
        dump = dump.substring(0, dump.indexOf("key:"));

        assertFalse(dump.contains("+-")); //not dumped as beans
        assertFalse(dump.contains("+=")); //not dumped as managed beans
        assertFalse(dump.contains("+~")); //not dumped as unmanaged beans
        assertFalse(dump.contains("+?")); //not dumped as auto beans

        handler.setFilters(null);
        handler.setServlets(null);
        handler.setListeners(null);

        //check they're removed as beans
        assertTrue(removeResults.contains(fh1));
        assertTrue(removeResults.contains(sh1));
        assertTrue(removeResults.contains(lh1));
    }

    @Test
    public void testServletMappings() throws Exception
    {
        Server server = new Server();
        ServletHandler handler = new ServletHandler();
        server.setHandler(handler);
        for (final String mapping : new String[] {"/", "/foo", "/bar/*", "*.bob"})
        {
            handler.addServletWithMapping(new ServletHolder(new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
                {
                    resp.getOutputStream().println("mapping='" + mapping + "'");
                }
            }), mapping);
        }
        // add servlet with no mapping
        handler.addServlet(new ServletHolder(new HttpServlet() {}));

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);

        server.start();

        assertThat(connector.getResponse("GET /default HTTP/1.0\r\n\r\n"), containsString("mapping='/'"));
        assertThat(connector.getResponse("GET /foo HTTP/1.0\r\n\r\n"), containsString("mapping='/foo'"));
        assertThat(connector.getResponse("GET /bar HTTP/1.0\r\n\r\n"), containsString("mapping='/bar/*'"));
        assertThat(connector.getResponse("GET /bar/bob HTTP/1.0\r\n\r\n"), containsString("mapping='/bar/*'"));
        assertThat(connector.getResponse("GET /bar/foo.bob HTTP/1.0\r\n\r\n"), containsString("mapping='/bar/*'"));
        assertThat(connector.getResponse("GET /other/foo.bob HTTP/1.0\r\n\r\n"), containsString("mapping='*.bob'"));
    }

    @Test
    public void testFilterMappings() throws Exception
    {
        Server server = new Server();
        ServletHandler handler = new ServletHandler();
        server.setHandler(handler);

        ServletHolder foo = new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                resp.getOutputStream().println("FOO");
            }
        });
        foo.setName("foo");
        handler.addServletWithMapping(foo, "/foo/*");

        ServletHolder def = new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                resp.getOutputStream().println("default");
            }
        });
        def.setName("default");
        handler.addServletWithMapping(def, "/");

        for (final String mapping : new String[]{"/*", "/foo", "/bar/*", "*.bob"})
        {
            handler.addFilterWithMapping(new FilterHolder((TestFilter)(request, response, chain) ->
            {
                response.getOutputStream().print("path-" + mapping + "-");
                chain.doFilter(request, response);
            }), mapping, EnumSet.of(DispatcherType.REQUEST));
        }

        FilterHolder fooFilter = new FilterHolder((TestFilter)(request, response, chain) ->
        {
            response.getOutputStream().print("name-foo-");
            chain.doFilter(request, response);
        });
        fooFilter.setName("fooFilter");
        FilterMapping named = new FilterMapping();
        named.setFilterHolder(fooFilter);
        named.setServletName("foo");
        handler.addFilter(fooFilter, named);

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);

        server.start();

        assertThat(connector.getResponse("GET /default HTTP/1.0\r\n\r\n"), containsString("path-/*-default"));
        assertThat(connector.getResponse("GET /foo HTTP/1.0\r\n\r\n"), containsString("path-/*-path-/foo-name-foo-FOO"));
        assertThat(connector.getResponse("GET /foo/bar HTTP/1.0\r\n\r\n"), containsString("path-/*-name-foo-FOO"));
        assertThat(connector.getResponse("GET /foo/bar.bob HTTP/1.0\r\n\r\n"), containsString("path-/*-path-*.bob-name-foo-FOO"));
        assertThat(connector.getResponse("GET /other.bob HTTP/1.0\r\n\r\n"), containsString("path-/*-path-*.bob-default"));
    }

    private interface TestFilter extends Filter
    {
        default void init(FilterConfig filterConfig) throws ServletException
        {
        }

        @Override
        default void destroy()
        {
        }
    }

}
