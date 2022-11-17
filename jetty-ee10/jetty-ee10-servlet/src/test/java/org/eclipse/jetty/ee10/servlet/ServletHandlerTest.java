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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.Container;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
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
        sm1.setFromDefaultDescriptor(false);
        sm1.setPathSpec("/foo/*");
        sm1.setServletName("s1");

        sh2.setName("s2");
        sm2.setFromDefaultDescriptor(false);
        sm2.setPathSpec("/foo/*");
        sm2.setServletName("s2");

        sh3.setName("s3");
        sm3.setFromDefaultDescriptor(true);
        sm3.setPathSpec("/foo/*");
        sm3.setServletName("s3");
    }

    @Test
    public void testAddFilterIgnoresDuplicates()
    {
        ServletHandler handler = new ServletHandler();
        FilterHolder h = new FilterHolder();
        h.setName("x");
        handler.addFilter(h);
        FilterHolder[] holders = handler.getFilters();
        assertNotNull(holders);
        assertThat(h, is(holders[0]));

        handler.addFilter(h);
        holders = handler.getFilters();
        assertNotNull(holders);
        assertThat(1, is(holders.length));
        assertThat(h, is(holders[0]));

        FilterHolder h2 = new FilterHolder();
        h2.setName("x"); //not allowed by servlet spec, just here to test object equality
        handler.addFilter(h2);
        holders = handler.getFilters();
        assertNotNull(holders);
        assertThat(2, is(holders.length));
        assertThat(h2, is(holders[1]));
    }

    @Test
    public void testAddFilterIgnoresDuplicates2()
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
        assertThat(h, is(holders[0]));

        FilterMapping m2 = new FilterMapping();
        m2.setPathSpec("/*");
        m2.setFilterHolder(h);
        handler.addFilter(h, m2);
        holders = handler.getFilters();
        assertNotNull(holders);
        assertThat(1, is(holders.length));
        assertThat(h, is(holders[0]));

        FilterHolder h2 = new FilterHolder();
        h2.setName("x"); //not allowed by servlet spec, just here to test object equality
        FilterMapping m3 = new FilterMapping();
        m3.setPathSpec("/*");
        m3.setFilterHolder(h);

        handler.addFilter(h2, m3);
        holders = handler.getFilters();
        assertNotNull(holders);
        assertThat(2, is(holders.length));
        assertThat(h2, is(holders[1]));
    }

    @Test
    public void testAddFilterWithMappingIgnoresDuplicateFilters()
    {
        ServletHandler handler = new ServletHandler();
        FilterHolder h = new FilterHolder();
        h.setName("x");

        handler.addFilterWithMapping(h, "/*", 0);
        FilterHolder[] holders = handler.getFilters();
        assertNotNull(holders);
        assertThat(h, is(holders[0]));

        handler.addFilterWithMapping(h, "/*", 1);
        holders = handler.getFilters();
        assertNotNull(holders);
        assertThat(1, is(holders.length));
        assertThat(h, is(holders[0]));

        FilterHolder h2 = new FilterHolder();
        h2.setName("x"); //not allowed by servlet spec, just here to test object equality

        handler.addFilterWithMapping(h2, "/*", 0);
        holders = handler.getFilters();
        assertNotNull(holders);
        assertThat(2, is(holders.length));
        assertThat(h2, is(holders[1]));
    }

    @Test
    public void testAddFilterWithMappingIngoresDuplicateFilters2()
    {
        ServletHandler handler = new ServletHandler();
        FilterHolder h = new FilterHolder();
        h.setName("x");

        handler.addFilterWithMapping(h, "/*", EnumSet.allOf(DispatcherType.class));
        FilterHolder[] holders = handler.getFilters();
        assertNotNull(holders);
        assertThat(h, is(holders[0]));

        handler.addFilterWithMapping(h, "/x", EnumSet.allOf(DispatcherType.class));
        holders = handler.getFilters();
        assertNotNull(holders);
        assertThat(1, is(holders.length));
        assertThat(h, is(holders[0]));

        FilterHolder h2 = new FilterHolder();
        h2.setName("x"); //not allowed by servlet spec, just here to test object equality

        handler.addFilterWithMapping(h2, "/*", EnumSet.allOf(DispatcherType.class));
        holders = handler.getFilters();
        assertNotNull(holders);
        assertThat(2, is(holders.length));
        assertThat(h2, is(holders[1]));
    }

    @Test
    public void testDuplicateMappingsForbidden()
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
    public void testDuplicateMappingsWithDefaults()
    {
        ServletHandler handler = new ServletHandler();
        handler.setAllowDuplicateMappings(false);
        handler.addServlet(sh1);
        handler.addServlet(sh3);
        handler.updateNameMappings();

        handler.addServletMapping(sm3);
        handler.addServletMapping(sm1);

        handler.updateMappings();

        ServletHandler.MappedServlet entry = handler.getMappedServlet("/foo/*");
        assertNotNull(entry);
        assertEquals("s1", entry.getServletHolder().getName());
    }

    @Test
    public void testDuplicateMappingsSameServlet()
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
    public void testDuplicateMappingsAllowed()
    {
        ServletHandler handler = new ServletHandler();
        handler.setAllowDuplicateMappings(true);
        handler.addServlet(sh1);
        handler.addServlet(sh2);
        handler.updateNameMappings();

        handler.addServletMapping(sm1);
        handler.addServletMapping(sm2);
        handler.updateMappings();

        ServletHandler.MappedServlet entry = handler.getMappedServlet("/foo/*");
        assertNotNull(entry);
        assertEquals("s2", entry.getServletHolder().getName());
    }

    @Test
    public void testAllNonProgrammaticFilterMappings()
    {
        ServletHandler handler = new ServletHandler();
        handler.addFilter(fh1);
        handler.addFilter(fh2);

        //add some ordinary filter mappings
        handler.addFilterMapping(fm1);
        handler.addFilterMapping(fm2);

        FilterMapping[] mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertThat(mappings[0], is(fm1));
        assertThat(mappings[1], is(fm2));

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
        assertThat(mappings[0], is(fm1));
        assertThat(mappings[1], is(fm2));
        assertThat(mappings[2], is(ofm1));
    }

    @Test
    public void testAllBeforeFilterMappings()
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

        assertThat(mappings[0], is(fm4));
        assertThat(mappings[1], is(fm5));
    }

    @Test
    public void testAllAfterFilterMappings()
    {
        ServletHandler handler = new ServletHandler();
        //do equivalent of FilterRegistration.addMappingForUrlPatterns(isMatchAfter=true)
        handler.addFilter(fh4);
        handler.addFilterMapping(fm4);
        FilterMapping[] mappings = handler.getFilterMappings();
        assertEquals(1, mappings.length);
        assertThat(mappings[0], is(fm4));

        //do equivalent of FilterRegistration.addMappingForUrlPatterns(isMatchAfter=true)
        handler.addFilter(fh5);
        handler.addFilterMapping(fm5);
        mappings = handler.getFilterMappings();
        assertEquals(2, mappings.length);
        assertThat(mappings[0], is(fm4));
        assertThat(mappings[1], is(fm5));
    }

    @Test
    public void testMatchAfterAndBefore()
    {
        ServletHandler handler = new ServletHandler();

        //add a programmatic one, isMatchAfter=true
        handler.addFilter(fh3);
        handler.addFilterMapping(fm3);
        FilterMapping[] mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(1, mappings.length);
        assertThat(mappings[0], is(fm3));

        //add a programmatic one, isMatchAfter=false
        handler.addFilter(fh4);
        handler.prependFilterMapping(fm4);
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(2, mappings.length);
        assertThat(mappings[0], is(fm4));
        assertThat(mappings[1], is(fm3));
    }

    @Test
    public void testMatchBeforeAndAfter()
    {
        ServletHandler handler = new ServletHandler();

        //add a programmatic one, isMatchAfter=false
        handler.addFilter(fh3);
        handler.prependFilterMapping(fm3);
        FilterMapping[] mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(1, mappings.length);
        assertThat(mappings[0], is(fm3));

        //add a programmatic one, isMatchAfter=true
        handler.addFilter(fh4);
        handler.addFilterMapping(fm4);
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(2, mappings.length);
        assertThat(mappings[0], is(fm3));
        assertThat(mappings[1], is(fm4));
    }

    @Test
    public void testExistingFilterMappings()
    {
        ServletHandler handler = new ServletHandler();
        handler.addFilter(fh1);
        handler.addFilter(fh2);

        //add some ordinary filter mappings first
        handler.addFilterMapping(fm1);
        handler.addFilterMapping(fm2);

        FilterMapping[] mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertThat(mappings[0], is(fm1));
        assertThat(mappings[1], is(fm2));

        //do equivalent of FilterRegistration.addMappingForUrlPatterns(isMatchAfter=false)
        handler.addFilter(fh4);
        handler.prependFilterMapping(fm4);
        mappings = handler.getFilterMappings();
        assertEquals(3, mappings.length);
        assertThat(mappings[0], is(fm4));

        //do equivalent of FilterRegistration.addMappingForUrlPatterns(isMatchAfter=true)
        handler.addFilter(fh5);
        handler.addFilterMapping(fm5);
        mappings = handler.getFilterMappings();
        assertEquals(4, mappings.length);
        assertThat(mappings[mappings.length - 1], is(fm5));
    }

    @Test
    public void testFilterMappingNoFilter()
    {
        FilterMapping mapping = new FilterMapping();
        mapping.setPathSpec("/*");
        mapping.setFilterName("foo");
        //default dispatch is REQUEST, and there is no holder to check for async supported
        assertFalse(mapping.appliesTo(DispatcherType.ASYNC));
    }

    @Test
    public void testFilterMappingsMix()
    {
        ServletHandler handler = new ServletHandler();

        //add a non-programmatic one to begin with
        handler.addFilter(fh1);
        handler.addFilterMapping(fm1);
        FilterMapping[] mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertThat(mappings[0], is(fm1));

        //add a programmatic one, isMatchAfter=false
        handler.addFilter(fh4);
        handler.prependFilterMapping(fm4);
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(2, mappings.length);
        assertThat(mappings[0], is(fm4));
        assertThat(mappings[1], is(fm1));

        //add a programmatic one, isMatchAfter=true
        handler.addFilter(fh3);
        handler.addFilterMapping(fm3);
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(3, mappings.length);
        assertThat(mappings[0], is(fm4));
        assertThat(mappings[1], is(fm1));
        assertThat(mappings[2], is(fm3));

        //add a programmatic one, isMatchAfter=false
        handler.addFilter(fh5);
        handler.prependFilterMapping(fm5);
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(4, mappings.length);
        assertThat(mappings[0], is(fm4)); //isMatchAfter = false;
        assertThat(mappings[1], is(fm5)); //isMatchAfter = false;
        assertThat(mappings[2], is(fm1)); //ordinary
        assertThat(mappings[3], is(fm3)); //isMatchAfter = true;

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
        assertThat(mappings[0], is(fm4)); //isMatchAfter = false;
        assertThat(mappings[1], is(fm5)); //isMatchAfter = false;
        assertThat(mappings[2], is(fm1)); //ordinary
        assertThat(mappings[3], is(fm));  //ordinary
        assertThat(mappings[4], is(fm3)); //isMatchAfter = true;

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
        assertThat(mappings[0], is(fm4)); //isMatchAfter = false;
        assertThat(mappings[1], is(fm5)); //isMatchAfter = false;
        assertThat(mappings[2], is(fm1)); //ordinary
        assertThat(mappings[3], is(fm));  //ordinary
        assertThat(mappings[4], is(fm3)); //isMatchAfter = true;
        assertThat(mappings[5], is(pfm)); //isMatchAfter = true;

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
        assertThat(mappings[0], is(fm4)); //isMatchAfter = false;
        assertThat(mappings[1], is(fm5)); //isMatchAfter = false;
        assertThat(mappings[2], is(pfm2)); //isMatchAfter = false;
        assertThat(mappings[3], is(fm1)); //ordinary
        assertThat(mappings[4], is(fm));  //ordinary
        assertThat(mappings[5], is(fm3)); //isMatchAfter = true;
        assertThat(mappings[6], is(pfm)); //isMatchAfter = true;
    }

    @Test
    public void testAddFilterWithMappingAPI()
    {
        ServletHandler handler = new ServletHandler();

        //add a non-programmatic one to begin with
        handler.addFilterWithMapping(fh1, "/*", EnumSet.allOf(DispatcherType.class));
        handler.updateMappings();
        FilterMapping[] mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertThat(mappings[0].getFilterHolder(), is(fh1));

        //add a programmatic one, isMatchAfter=false
        fh4.setServletHandler(handler);
        handler.addFilter(fh4);
        fh4.getRegistration().addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), false, "/*");
        handler.updateMappings();
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(2, mappings.length);
        assertThat(mappings[0].getFilterHolder(), is(fh4));
        assertThat(mappings[1].getFilterHolder(), is(fh1));

        //add a programmatic one, isMatchAfter=true
        fh3.setServletHandler(handler);
        handler.addFilter(fh3);
        fh3.getRegistration().addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), true, "/*");
        handler.updateMappings();
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(3, mappings.length);
        assertThat(mappings[0].getFilterHolder(), is(fh4));
        assertThat(mappings[1].getFilterHolder(), is(fh1));
        assertThat(mappings[2].getFilterHolder(), is(fh3));

        //add a programmatic one, isMatchAfter=false
        fh5.setServletHandler(handler);
        handler.addFilter(fh5);
        fh5.getRegistration().addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), false, "/*");
        handler.updateMappings();
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(4, mappings.length);
        assertThat(mappings[0].getFilterHolder(), is(fh4)); //isMatchAfter = false;
        assertThat(mappings[1].getFilterHolder(), is(fh5)); //isMatchAfter = false;
        assertThat(mappings[2].getFilterHolder(), is(fh1)); //ordinary
        assertThat(mappings[3].getFilterHolder(), is(fh3)); //isMatchAfter = true;

        //add a non-programmatic one
        FilterHolder f = new FilterHolder(Source.EMBEDDED);
        f.setName("non-programmatic");
        handler.addFilterWithMapping(f, "/*", EnumSet.allOf(DispatcherType.class));
        handler.updateMappings();
        mappings = handler.getFilterMappings();
        assertNotNull(mappings);
        assertEquals(5, mappings.length);
        assertThat(mappings[0].getFilterHolder(), is(fh4)); //isMatchAfter = false;
        assertThat(mappings[1].getFilterHolder(), is(fh5)); //isMatchAfter = false;
        assertThat(mappings[2].getFilterHolder(), is(fh1)); //ordinary
        assertThat(mappings[3].getFilterHolder(), is(f));  //ordinary
        assertThat(mappings[4].getFilterHolder(), is(fh3)); //isMatchAfter = true;

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
        assertThat(mappings[0].getFilterHolder(), is(fh4)); //isMatchAfter = false;
        assertThat(mappings[1].getFilterHolder(), is(fh5)); //isMatchAfter = false;
        assertThat(mappings[2].getFilterHolder(), is(fh1)); //ordinary
        assertThat(mappings[3].getFilterHolder(), is(f));  //ordinary
        assertThat(mappings[4].getFilterHolder(), is(fh3)); //isMatchAfter = true;
        assertThat(mappings[5].getFilterHolder(), is(pf)); //isMatchAfter = true;

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
        assertThat(mappings[0].getFilterHolder(), is(fh4)); //isMatchAfter = false;
        assertThat(mappings[1].getFilterHolder(), is(fh5)); //isMatchAfter = false;
        assertThat(mappings[2].getFilterHolder(), is(pf2)); //isMatchAfter = false;
        assertThat(mappings[3].getFilterHolder(), is(fh1)); //ordinary
        assertThat(mappings[4].getFilterHolder(), is(f));  //ordinary
        assertThat(mappings[5].getFilterHolder(), is(fh3)); //isMatchAfter = true;
        assertThat(mappings[6].getFilterHolder(), is(pf)); //isMatchAfter = true;
    }
    
    @Test
    public void testFiltersServletsListenersAsBeans()
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

        assertTrue(dump.contains("+> listeners"));
        assertTrue(dump.contains("+> filters"));
        assertTrue(dump.contains("+> servlets"));
        assertTrue(dump.contains("+> filterMappings"));
        assertTrue(dump.contains("+> servletMappings"));

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
        ServletContextHandler context = new ServletContextHandler("/");
        server.setHandler(context);
        ServletHandler handler = new ServletHandler();
        context.setHandler(handler);
        for (final String mapping : new String[] {"/", "/foo", "/bar/*", "*.bob"})
        {
            handler.addServletWithMapping(new ServletHolder(new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
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
        server.dumpStdErr();

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
        ServletContextHandler context = new ServletContextHandler("/");
        server.setHandler(context);
        ServletHandler handler = context.getServletHandler();

        ServletHolder foo = new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.getOutputStream().println("FOO");
            }
        });
        foo.setName("foo");
        handler.addServletWithMapping(foo, "/foo/*");

        ServletHolder def = new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
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

    @Test
    public void testDurable() throws Exception
    {
        Server server = new Server();
        ServletContextHandler context = new ServletContextHandler();
        server.setHandler(context);
        ServletHandler handler = new ServletHandler();
        context.setHandler(handler);
        ListenerHolder lh1 = new ListenerHolder(HSListener.class);
        ListenerHolder lh2 = new ListenerHolder(SCListener.class);

        fh1.setFilter(new SomeFilter());
        fm1.setPathSpec("/sm1");
        fm1.setFilterHolder(fh1);
        fh2.setFilter(new SomeFilter(){});
        fm2.setPathSpec("/sm2");
        fm2.setFilterHolder(fh2);
        sh1.setServlet(new SomeServlet());
        sm1.setPathSpec("/sm1");
        sm1.setServletName(sh1.getName());
        sh2.setServlet(new SomeServlet());
        sm2.setPathSpec("/sm2");
        sm2.setServletName(sh2.getName());

        handler.setListeners(new ListenerHolder[] {lh1});
        handler.setFilters(new FilterHolder[] {fh1});
        handler.setFilterMappings(new FilterMapping[] {fm1});
        handler.setServlets(new ServletHolder[] {sh1});
        handler.setServletMappings(new ServletMapping[] {sm1});

        server.start();

        handler.setListeners(new ListenerHolder[] {lh1, lh2});
        handler.setFilters(new FilterHolder[] {fh1, fh2});
        handler.setFilterMappings(new FilterMapping[] {fm1, fm2});
        handler.setServlets(new ServletHolder[] {sh1, sh2});
        handler.setServletMappings(new ServletMapping[] {sm1, sm2});

        assertThat(Arrays.asList(handler.getListeners()), contains(lh1, lh2));
        assertThat(Arrays.asList(handler.getFilters()), contains(fh1, fh2));
        assertThat(Arrays.asList(handler.getFilterMappings()), contains(fm1, fm2));
        assertThat(Arrays.asList(handler.getServlets()), contains(sh1, sh2));
        assertThat(Arrays.asList(handler.getServletMappings()), contains(sm1, sm2));

        server.stop();

        assertThat(Arrays.asList(handler.getListeners()), contains(lh1));
        assertThat(Arrays.asList(handler.getFilters()), contains(fh1));
        assertThat(Arrays.asList(handler.getFilterMappings()), contains(fm1));
        assertThat(Arrays.asList(handler.getServlets()), contains(sh1));
        assertThat(Arrays.asList(handler.getServletMappings()), contains(sm1));
    }

    public static class HSListener implements HttpSessionListener
    {
    }

    public static class SCListener implements ServletContextListener
    {
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

    public static class SomeFilter implements TestFilter
    {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            chain.doFilter(request, response);
        }
    }

    public static class SomeServlet extends HttpServlet
    {
    }

}
