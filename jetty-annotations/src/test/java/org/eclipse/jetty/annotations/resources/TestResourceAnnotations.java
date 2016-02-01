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

package org.eclipse.jetty.annotations.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.lang.reflect.Field;
import java.util.List;

import javax.naming.Context;
import javax.naming.InitialContext;

import org.eclipse.jetty.annotations.AnnotationIntrospector;
import org.eclipse.jetty.annotations.ResourceAnnotationHandler;
import org.eclipse.jetty.annotations.ResourcesAnnotationHandler;
import org.eclipse.jetty.plus.annotation.Injection;
import org.eclipse.jetty.plus.annotation.InjectionCollection;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestResourceAnnotations
{
    private Server server;
    private WebAppContext wac;
    private InjectionCollection injections;
    private Context comp;
    private Context env;
    private Object objA = 1000;
    private Object objB = 2000;

    @Before
    public void init() throws Exception
    {
        server = new Server();
        wac = new WebAppContext();
        wac.setServer(server);
        injections = new InjectionCollection();
        wac.setAttribute(InjectionCollection.INJECTION_COLLECTION, injections);
        InitialContext ic = new InitialContext();
        comp = (Context)ic.lookup("java:comp");
        env = comp.createSubcontext("env");
    }

    @After
    public void destroy() throws Exception
    {
        comp.destroySubcontext("env");
    }

    @Test
    public void testResourceAnnotations ()
    throws Exception
    {
        new org.eclipse.jetty.plus.jndi.EnvEntry(server, "resA", objA, false);
        new org.eclipse.jetty.plus.jndi.EnvEntry(server, "resB", objB, false);

        AnnotationIntrospector parser = new AnnotationIntrospector();
        ResourceAnnotationHandler handler = new ResourceAnnotationHandler(wac);
        parser.registerHandler(handler);
        parser.introspect(ResourceA.class);
        parser.introspect(ResourceB.class);

        //processing classA should give us these jndi name bindings:
        // java:comp/env/myf
        // java:comp/env/org.eclipse.jetty.annotations.resources.ResourceA/g
        // java:comp/env/mye
        // java:comp/env/org.eclipse.jetty.annotations.resources.ResourceA/h
        // java:comp/env/resA
        // java:comp/env/org.eclipse.jetty.annotations.resources.ResourceB/f
        // java:comp/env/org.eclipse.jetty.annotations.resources.ResourceA/n
        //
        assertEquals(objB, env.lookup("myf"));
        assertEquals(objA, env.lookup("mye"));
        assertEquals(objA, env.lookup("resA"));
        assertEquals(objA, env.lookup("org.eclipse.jetty.annotations.resources.ResourceA/g"));
        assertEquals(objA, env.lookup("org.eclipse.jetty.annotations.resources.ResourceA/h"));
        assertEquals(objB, env.lookup("org.eclipse.jetty.annotations.resources.ResourceB/f"));
        assertEquals(objB, env.lookup("org.eclipse.jetty.annotations.resources.ResourceA/n"));

        //we should have Injections
        assertNotNull(injections);

        List<Injection> resBInjections = injections.getInjections(ResourceB.class.getCanonicalName());
        assertNotNull(resBInjections);

        //only 1 field injection because the other has no Resource mapping
        assertEquals(1, resBInjections.size());
        Injection fi = resBInjections.get(0);
        assertEquals ("f", fi.getTarget().getName());

        //3 method injections on class ResourceA, 4 field injections
        List<Injection> resAInjections = injections.getInjections(ResourceA.class.getCanonicalName());
        assertNotNull(resAInjections);
        assertEquals(7, resAInjections.size());
        int fieldCount = 0;
        int methodCount = 0;
        for (Injection x : resAInjections)
        {
            if (x.isField())
                fieldCount++;
            else
                methodCount++;
        }
        assertEquals(4, fieldCount);
        assertEquals(3, methodCount);

        //test injection
        ResourceB binst = new ResourceB();
        injections.inject(binst);

        //check injected values
        Field f = ResourceB.class.getDeclaredField ("f");
        f.setAccessible(true);
        assertEquals(objB , f.get(binst));

        //@Resource(mappedName="resA") //test the default naming scheme but using a mapped name from the environment
        f = ResourceA.class.getDeclaredField("g");
        f.setAccessible(true);
        assertEquals(objA, f.get(binst));

        //@Resource(name="resA") //test using the given name as the name from the environment
        f = ResourceA.class.getDeclaredField("j");
        f.setAccessible(true);
        assertEquals(objA, f.get(binst));

        //@Resource(mappedName="resB") //test using the default name on an inherited field
        f = ResourceA.class.getDeclaredField("n");
        f.setAccessible(true);
        assertEquals(objB, f.get(binst));
    }

    @Test
    public void testResourcesAnnotation ()
    throws Exception
    {
        new org.eclipse.jetty.plus.jndi.EnvEntry(server, "resA", objA, false);
        new org.eclipse.jetty.plus.jndi.EnvEntry(server, "resB", objB, false);

        AnnotationIntrospector introspector = new AnnotationIntrospector();
        ResourcesAnnotationHandler handler = new ResourcesAnnotationHandler(wac);
        introspector.registerHandler(handler);
        introspector.introspect(ResourceA.class);
        introspector.introspect(ResourceB.class);

        assertEquals(objA, env.lookup("peach"));
        assertEquals(objB, env.lookup("pear"));
    }
}
