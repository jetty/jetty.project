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

package org.eclipse.jetty.ee9.annotations.resources;

import java.lang.reflect.Field;
import java.util.Set;
import javax.naming.Context;
import javax.naming.InitialContext;

import org.eclipse.jetty.ee9.annotations.AnnotationIntrospector;
import org.eclipse.jetty.ee9.annotations.ResourceAnnotationHandler;
import org.eclipse.jetty.ee9.annotations.ResourcesAnnotationHandler;
import org.eclipse.jetty.ee9.plus.annotation.Injection;
import org.eclipse.jetty.ee9.plus.annotation.InjectionCollection;
import org.eclipse.jetty.ee9.plus.jndi.EnvEntry;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestResourceAnnotations
{
    private Server server;
    private WebAppContext wac;
    private InjectionCollection injections;
    private Context comp;
    private Context env;
    private Object objA = 1000;
    private Object objB = 2000;

    @BeforeEach
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

    @AfterEach
    public void destroy() throws Exception
    {
        comp.destroySubcontext("env");
    }

    @Test
    public void testResourceAnnotations()
        throws Exception
    {
        new EnvEntry(server, "resA", objA, false);
        new EnvEntry(server, "resB", objB, false);

        AnnotationIntrospector parser = new AnnotationIntrospector(wac);
        ResourceAnnotationHandler handler = new ResourceAnnotationHandler(wac);
        parser.registerHandler(handler);

        ResourceA resourceA = new ResourceA();
        ResourceB resourceB = new ResourceB();
        parser.introspect(resourceA, null);
        parser.introspect(resourceB, null);

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

        Set<Injection> resBInjections = injections.getInjections(ResourceB.class.getName());
        assertNotNull(resBInjections);

        //only 1 field injection because the other has no Resource mapping
        assertEquals(1, resBInjections.size());
        Injection fi = resBInjections.iterator().next();
        assertEquals("f", fi.getTarget().getName());

        //3 method injections on class ResourceA, 4 field injections
        Set<Injection> resAInjections = injections.getInjections(ResourceA.class.getName());
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
        Field f = ResourceB.class.getDeclaredField("f");
        f.setAccessible(true);
        assertEquals(objB, f.get(binst));

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
    public void testResourcesAnnotation()
        throws Exception
    {
        new EnvEntry(server, "resA", objA, false);
        new EnvEntry(server, "resB", objB, false);

        AnnotationIntrospector introspector = new AnnotationIntrospector(wac);
        ResourcesAnnotationHandler handler = new ResourcesAnnotationHandler(wac);
        introspector.registerHandler(handler);
        ResourceA resourceA = new ResourceA();
        ResourceB resourceB = new ResourceB();
        introspector.introspect(resourceA, null);
        introspector.introspect(resourceB, null);

        assertEquals(objA, env.lookup("peach"));
        assertEquals(objB, env.lookup("pear"));
    }
}
