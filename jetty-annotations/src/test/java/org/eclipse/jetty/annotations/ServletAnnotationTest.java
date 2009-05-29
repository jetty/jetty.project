//  ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.annotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;

import junit.framework.TestCase;

import org.eclipse.jetty.plus.annotation.Injection;
import org.eclipse.jetty.plus.annotation.InjectionCollection;
import org.eclipse.jetty.plus.annotation.LifeCycleCallback;
import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.plus.annotation.RunAs;
import org.eclipse.jetty.plus.annotation.RunAsCollection;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.webapp.WebAppContext;

public class ServletAnnotationTest extends TestCase
{ 
   
    public void tearDown()
    throws Exception
    {
        InitialContext ic = new InitialContext();
        Context comp = (Context)ic.lookup("java:comp");
        comp.destroySubcontext("env");
    }
    
    public void testAnnotations() throws Exception
    {
        Server server = new Server();
        WebAppContext wac = new WebAppContext();
        wac.setServer(server);
        
        InitialContext ic = new InitialContext();
        Context comp = (Context)ic.lookup("java:comp");
        Context env = null;
        try
        {
            env = (Context)comp.lookup("env");
        }
        catch (NameNotFoundException e)
        {
            env = comp.createSubcontext("env");
        }
          
        org.eclipse.jetty.plus.jndi.EnvEntry foo = new org.eclipse.jetty.plus.jndi.EnvEntry("foo", new Double(1000.00), false);
     
        
        List classes = new ArrayList();
        classes.add("org.eclipse.jetty.annotations.ServletC");
        classes.add("org.eclipse.jetty.annotations.FilterC");
       
        AnnotationFinder finder = new AnnotationFinder();
        finder.find (classes, 
                new ClassNameResolver()
        {
            public boolean isExcluded(String name)
            {
                return false;
            }

            public boolean shouldOverride(String name)
            {
                return true;
            }
        });
        
        RunAsCollection runAs = new RunAsCollection();
        InjectionCollection injections = new InjectionCollection();
        LifeCycleCallbackCollection callbacks = new LifeCycleCallbackCollection();
        wac.setAttribute(RunAsCollection.RUNAS_COLLECTION, runAs);
        wac.setAttribute(InjectionCollection.INJECTION_COLLECTION, injections);
        wac.setAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION, callbacks);
        
        AnnotationProcessor processor = new AnnotationProcessor (wac, finder);
        processor.process();
        
 
        List servlets = processor.getServlets();
        List filters = processor.getFilters();
        List servletMappings = processor.getServletMappings();
        List filterMappings = processor.getFilterMappings();
        List listeners = processor.getListeners();
        
        assertEquals(1, servlets.size());
        ServletHolder sholder = (ServletHolder)servlets.get(0);
        assertEquals("CServlet", sholder.getName());
        Map sinitparams = sholder.getInitParameters();
        assertEquals(1, sinitparams.size());
        assertTrue(sinitparams.containsKey("x"));
        assertTrue(sinitparams.containsValue("y"));
        assertEquals(2,sholder.getInitOrder());
        assertEquals(1, filters.size());
        FilterHolder fholder = (FilterHolder)filters.get(0);
        
        Map finitparams = fholder.getInitParameters();
        assertEquals(1, finitparams.size());
        assertTrue(finitparams.containsKey("a"));
        assertTrue(finitparams.containsValue("99"));
        assertEquals(1, servletMappings.size());
        ServletMapping smap = (ServletMapping)servletMappings.get(0);
        assertEquals("CServlet", smap.getServletName());
        assertEquals(2, smap.getPathSpecs().length);
        assertEquals(1, filterMappings.size());
        FilterMapping fmap = (FilterMapping)filterMappings.get(0);
        assertEquals("CFilter", fmap.getFilterName());
        assertEquals(1, fmap.getPathSpecs().length);
        
        List<Injection> fieldInjections = injections.getFieldInjections(ServletC.class);
        assertNotNull(fieldInjections);
        assertEquals(1, fieldInjections.size());  
        
        RunAs ra = runAs.getRunAs(sholder);
        assertNotNull(ra);
        assertEquals("admin", ra.getRoleName());
        
        List predestroys = callbacks.getPreDestroyCallbacks(new ServletC());
        assertNotNull(predestroys);
        assertEquals(1, predestroys.size());
        LifeCycleCallback cb = (LifeCycleCallback)predestroys.get(0);
        assertTrue(cb.getTarget().equals(ServletC.class.getDeclaredMethod("pre", new Class[]{})));
        
        List postconstructs = callbacks.getPostConstructCallbacks(new ServletC());
        assertNotNull(postconstructs);
        assertEquals(1, postconstructs.size());
        cb = (LifeCycleCallback)postconstructs.get(0);
        assertTrue(cb.getTarget().equals(ServletC.class.getDeclaredMethod("post", new Class[]{})));
    }

}
