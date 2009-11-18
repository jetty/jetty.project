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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.naming.Context;
import javax.naming.InitialContext;

import junit.framework.TestCase;

import org.eclipse.jetty.annotations.AnnotationParser.AnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.util.MultiMap;

/**
 * TestAnnotationInheritance
 *
 *
 */
public class TestAnnotationInheritance extends TestCase
{
    List<String> classNames = new ArrayList<String>();
  
    
    class SampleHandler implements AnnotationHandler
    {
        public final List<String> annotatedClassNames = new ArrayList<String>();
        public final List<String> annotatedMethods = new ArrayList<String>();
        public final List<String> annotatedFields = new ArrayList<String>();
        
        public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                                List<Value> values)
        {
            annotatedClassNames.add(className);
        }

        public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                                List<Value> values)
        {                
            annotatedFields.add(className+"."+fieldName);
        }

        public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                                 List<Value> values)
        {
            annotatedMethods.add(className+"."+methodName);
        }      
    }
    
   
    public void tearDown () throws Exception
    {
        classNames.clear();
        InitialContext ic = new InitialContext();
        Context comp = (Context)ic.lookup("java:comp");
        comp.destroySubcontext("env");
    }
    
  
    public void testParseClassNames ()
    throws Exception
    {        
        classNames.add(ClassA.class.getName());
        classNames.add(ClassB.class.getName());
        
        SampleHandler handler = new SampleHandler(); 
        AnnotationParser parser = new AnnotationParser();
        parser.registerAnnotationHandler("org.eclipse.jetty.annotations.Sample", handler);
        parser.parse(classNames, new ClassNameResolver () 
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
             
        //check we got  2 class annotations
        assertEquals(2, handler.annotatedClassNames.size());
        
        //check we got all annotated methods on each class
        assertEquals (7, handler.annotatedMethods.size());
        assertTrue (handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassA.a"));
        assertTrue (handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassA.b"));
        assertTrue (handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassA.c"));
        assertTrue (handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassA.d"));
        assertTrue (handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassA.l"));
        assertTrue (handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassB.a"));
        assertTrue (handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassB.c"));
     
        //check we got all annotated fields on each class
        assertEquals(1, handler.annotatedFields.size());
        assertEquals("org.eclipse.jetty.annotations.ClassA.m", handler.annotatedFields.get(0));     
    }
   
    
    
    
    public void testParseClass ()
    throws Exception
    {
        SampleHandler handler = new SampleHandler();
        AnnotationParser parser = new AnnotationParser();
        parser.registerAnnotationHandler("org.eclipse.jetty.annotations.Sample", handler);
        parser.parse(ClassB.class, new ClassNameResolver () 
        {
            public boolean isExcluded(String name)
            {
                return false;
            }

            public boolean shouldOverride(String name)
            {
                return false;
            }       
        }, true);   
        
        //check we got  2 class annotations
        assertEquals(2, handler.annotatedClassNames.size());
        
        //check we got all annotated methods on each class
        assertEquals (7, handler.annotatedMethods.size());
        assertTrue (handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassA.a"));
        assertTrue (handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassA.b"));
        assertTrue (handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassA.c"));
        assertTrue (handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassA.d"));
        assertTrue (handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassA.l"));
        assertTrue (handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassB.a"));
        assertTrue (handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassB.c"));
     
        //check we got all annotated fields on each class
        assertEquals(1, handler.annotatedFields.size());
        assertEquals("org.eclipse.jetty.annotations.ClassA.m", handler.annotatedFields.get(0));    
    }
    
 
    public void testExclusions()
    throws Exception
    {
        AnnotationParser parser = new AnnotationParser();
        SampleHandler handler = new SampleHandler();
        parser.registerAnnotationHandler("org.eclipse.jetty.annotations.Sample", handler);
        parser.parse(ClassA.class.getName(), new ClassNameResolver()
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
        assertEquals (0, handler.annotatedClassNames.size());
        assertEquals (0, handler.annotatedFields.size());
        assertEquals (0, handler.annotatedMethods.size());

        handler.annotatedClassNames.clear();
        handler.annotatedFields.clear();
        handler.annotatedMethods.clear();

        parser.parse (ClassA.class.getName(), new ClassNameResolver()
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
        assertEquals (1, handler.annotatedClassNames.size());
    }
 
    public void testTypeInheritanceHandling ()
    throws Exception
    {
        AnnotationParser parser = new AnnotationParser();
        ClassInheritanceHandler handler = new ClassInheritanceHandler();
        parser.registerClassHandler(handler);
        
        class Foo implements InterfaceD
        {
            
        }
        
        classNames.clear();
        classNames.add(ClassA.class.getName());
        classNames.add(ClassB.class.getName());
        classNames.add(InterfaceD.class.getName());
        classNames.add(Foo.class.getName());
        
        
        parser.parse(classNames, null);
        
        MultiMap map = handler.getMap();
        assertNotNull(map);
        assertFalse(map.isEmpty());
        assertEquals(2, map.size());
        Map stringArrayMap = map.toStringArrayMap();
        assertTrue (stringArrayMap.keySet().contains("org.eclipse.jetty.annotations.ClassA"));
        assertTrue (stringArrayMap.keySet().contains("org.eclipse.jetty.annotations.InterfaceD"));
        String[] classes = (String[])stringArrayMap.get("org.eclipse.jetty.annotations.ClassA");
        assertEquals(1, classes.length);
        assertEquals ("org.eclipse.jetty.annotations.ClassB", classes[0]);

        classes = (String[])stringArrayMap.get("org.eclipse.jetty.annotations.InterfaceD");
        assertEquals(2, classes.length);
        assertEquals ("org.eclipse.jetty.annotations.ClassB", classes[0]);
        assertEquals(Foo.class.getName(), classes[1]);
    }

}
