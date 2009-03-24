// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import javax.annotation.Resource;
import javax.annotation.Resources;
import javax.naming.Context;
import javax.naming.InitialContext;

import junit.framework.TestCase;

import org.eclipse.jetty.annotations.resources.ResourceA;
import org.eclipse.jetty.annotations.resources.ResourceB;
import org.eclipse.jetty.plus.annotation.Injection;
import org.eclipse.jetty.plus.annotation.InjectionCollection;
import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.plus.annotation.RunAsCollection;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * TestAnnotationInheritance
 *
 *
 */
public class TestAnnotationInheritance extends TestCase
{
    List<String> classNames = new ArrayList<String>();
    
   
    public void tearDown () throws Exception
    {
        classNames.clear();
        InitialContext ic = new InitialContext();
        Context comp = (Context)ic.lookup("java:comp");
        comp.destroySubcontext("env");
    }
    
    public void testInheritance ()
    throws Exception
    {        
        classNames.add(ClassA.class.getName());
        classNames.add(ClassB.class.getName());
        
        AnnotationFinder finder = new AnnotationFinder();
        finder.find(classNames, new ClassNameResolver () 
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
       
        List<Class<?>> classes = finder.getClassesForAnnotation(Sample.class);
        assertEquals(2, classes.size());
        
        //check methods
        //List methods = collection.getMethods();
        List<Method> methods = finder.getMethodsForAnnotation(Sample.class);
        
        assertTrue(methods!=null);
        assertFalse(methods.isEmpty());
    }
    
    
    public void testExclusions()
    throws Exception
    {
        AnnotationFinder finder = new AnnotationFinder();
        finder.find(ClassA.class.getName(), new ClassNameResolver()
        {
            public boolean isExcluded(String name)
            {
                return true;
            }

            public boolean shouldOverride(String name)
            {
                return false;
            }       
        });
        assertTrue(finder.getClassesForAnnotation(Sample.class).isEmpty());
        
        finder.find (ClassA.class.getName(), new ClassNameResolver()
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
        assertEquals(1, finder.getClassesForAnnotation(Sample.class).size());
    }
    
    
    public void testResourceAnnotations ()
    throws Exception
    {
        Server server = new Server();
        WebAppContext wac = new WebAppContext();
        wac.setServer(server);
        
        InitialContext ic = new InitialContext();
        Context comp = (Context)ic.lookup("java:comp");
        Context env = comp.createSubcontext("env");
        
        org.eclipse.jetty.plus.jndi.EnvEntry resourceA = new org.eclipse.jetty.plus.jndi.EnvEntry(server, "resA", new Integer(1000), false);
        org.eclipse.jetty.plus.jndi.EnvEntry resourceB = new org.eclipse.jetty.plus.jndi.EnvEntry(server, "resB", new Integer(2000), false);
        

        classNames.add(ResourceA.class.getName());
        classNames.add(ResourceB.class.getName());
        AnnotationFinder finder = new AnnotationFinder();
        finder.find(classNames, new ClassNameResolver()
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
       
        List<Class<?>> resourcesClasses = finder.getClassesForAnnotation(Resources.class);
        assertNotNull(resourcesClasses);
        assertEquals(1, resourcesClasses.size());
        
        List<Class<?>> annotatedClasses = finder.getClassesForAnnotation(Resource.class);      
        List<Method> annotatedMethods = finder.getMethodsForAnnotation(Resource.class);
        List<Field>  annotatedFields = finder.getFieldsForAnnotation(Resource.class);
        assertNotNull(annotatedClasses);
        assertEquals(0, annotatedClasses.size());
        assertEquals(3, annotatedMethods.size());
        assertEquals(6, annotatedFields.size());
        
        InjectionCollection injections = new InjectionCollection();
        LifeCycleCallbackCollection callbacks = new LifeCycleCallbackCollection();
        RunAsCollection runAses = new RunAsCollection();
        AnnotationProcessor processor = new AnnotationProcessor(wac, finder, runAses, injections, callbacks, 
                Collections.EMPTY_LIST, Collections.EMPTY_LIST, Collections.EMPTY_LIST, Collections.EMPTY_LIST, Collections.EMPTY_LIST);
        //process with all the specific annotations turned into injections, callbacks etc
        processor.process();
        
        //processing classA should give us these jndi name bindings:
        // java:comp/env/myf
        // java:comp/env/org.eclipse.jetty.annotations.resources.ResourceA/g
        // java:comp/env/mye
        // java:comp/env/org.eclipse.jetty.annotations.resources.ResourceA/h
        // java:comp/env/resA
        // java:comp/env/org.eclipse.jetty.annotations.resources.ResourceB/f
        // java:comp/env/org.eclipse.jetty.annotations.resources.ResourceA/n
        // 
        assertEquals(resourceB.getObjectToBind(), env.lookup("myf"));
        assertEquals(resourceA.getObjectToBind(), env.lookup("mye"));
        assertEquals(resourceA.getObjectToBind(), env.lookup("resA"));
        assertEquals(resourceA.getObjectToBind(), env.lookup("org.eclipse.jetty.annotations.resources.ResourceA/g")); 
        assertEquals(resourceA.getObjectToBind(), env.lookup("org.eclipse.jetty.annotations.resources.ResourceA/h"));
        assertEquals(resourceB.getObjectToBind(), env.lookup("org.eclipse.jetty.annotations.resources.ResourceB/f"));
        assertEquals(resourceB.getObjectToBind(), env.lookup("org.eclipse.jetty.annotations.resources.ResourceA/n"));
        
        //we should have Injections
        assertNotNull(injections);
        
        List<Injection> fieldInjections = injections.getFieldInjections(ResourceB.class);
        assertNotNull(fieldInjections);
        
        Iterator itor = fieldInjections.iterator();
        System.err.println("Field injections:");
        while (itor.hasNext())
        {
            System.err.println(itor.next());
        }
        //only 1 field injection because the other has no Resource mapping
        assertEquals(1, fieldInjections.size());
        
        fieldInjections = injections.getFieldInjections(ResourceA.class);
        assertNotNull(fieldInjections);
        assertEquals(4, fieldInjections.size());
        
        
        List<Injection> methodInjections = injections.getMethodInjections(ResourceB.class);
        itor = methodInjections.iterator();
        System.err.println("Method injections:");
        while (itor.hasNext())
            System.err.println(itor.next());
        
        assertNotNull(methodInjections);
        assertEquals(0, methodInjections.size());
        
        methodInjections = injections.getMethodInjections(ResourceA.class);
        assertNotNull(methodInjections);
        assertEquals(3, methodInjections.size());
        
        //test injection
        ResourceB binst = new ResourceB();
        injections.inject(binst);
        
        //check injected values
        Field f = ResourceB.class.getDeclaredField ("f");
        f.setAccessible(true);
        assertEquals(resourceB.getObjectToBind() , f.get(binst));
        
        //@Resource(mappedName="resA") //test the default naming scheme but using a mapped name from the environment
        f = ResourceA.class.getDeclaredField("g"); 
        f.setAccessible(true);
        assertEquals(resourceA.getObjectToBind(), f.get(binst));
        
        //@Resource(name="resA") //test using the given name as the name from the environment
        f = ResourceA.class.getDeclaredField("j");
        f.setAccessible(true);
        assertEquals(resourceA.getObjectToBind(), f.get(binst));
        
        //@Resource(mappedName="resB") //test using the default name on an inherited field
        f = ResourceA.class.getDeclaredField("n"); 
        f.setAccessible(true);
        assertEquals(resourceB.getObjectToBind(), f.get(binst));
    }

}
