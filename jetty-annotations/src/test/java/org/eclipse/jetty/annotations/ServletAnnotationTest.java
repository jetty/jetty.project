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
import org.eclipse.jetty.plus.annotation.PojoFilter;
import org.eclipse.jetty.plus.annotation.PojoServlet;
import org.eclipse.jetty.plus.annotation.RunAs;
import org.eclipse.jetty.plus.annotation.RunAsCollection;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
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
        List servlets = new ArrayList();
        List filters = new ArrayList();
        List listeners = new ArrayList();
        List servletMappings = new ArrayList();
        List filterMappings = new ArrayList();
        
        List classes = new ArrayList();
        classes.add("org.eclipse.jetty.annotations.ClassC");
       
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
        
        AnnotationProcessor processor = new AnnotationProcessor (wac, finder, runAs, injections, callbacks, 
                servlets, filters, listeners, servletMappings, filterMappings);
        processor.process();
        
        
        assertEquals(1, servlets.size());
        ServletHolder sholder = (ServletHolder)servlets.get(0);
        assertEquals("CServlet", sholder.getName());
        assertTrue(sholder.getServlet() instanceof PojoServlet);
        PojoServlet ps  = (PojoServlet)sholder.getServlet();
        assertEquals("anything", ps.getGetMethodName());
        assertEquals("anything", ps.getPostMethodName());
        Map sinitparams = sholder.getInitParameters();
        assertEquals(1, sinitparams.size());
        assertTrue(sinitparams.containsKey("x"));
        assertTrue(sinitparams.containsValue("y"));
        assertEquals(1, filters.size());
        FilterHolder fholder = (FilterHolder)filters.get(0);
        assertTrue(fholder.getFilter() instanceof PojoFilter);
        
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
        
        List<Injection> fieldInjections = injections.getFieldInjections(ClassC.class);
        assertNotNull(fieldInjections);
        assertEquals(1, fieldInjections.size());  
        
        RunAs ra = runAs.getRunAs(sholder);
        assertNotNull(ra);
        assertEquals("admin", ra.getRoleName());
        
        List predestroys = callbacks.getPreDestroyCallbacks(sholder.getServlet());
        assertNotNull(predestroys);
        assertEquals(1, predestroys.size());
        LifeCycleCallback cb = (LifeCycleCallback)predestroys.get(0);
        assertTrue(cb.getTarget().equals(ClassC.class.getDeclaredMethod("pre", new Class[]{})));
        
        List postconstructs = callbacks.getPostConstructCallbacks(sholder.getServlet());
        assertNotNull(postconstructs);
        assertEquals(1, postconstructs.size());
        cb = (LifeCycleCallback)postconstructs.get(0);
        assertTrue(cb.getTarget().equals(ClassC.class.getDeclaredMethod("post", new Class[]{})));
    }

}
