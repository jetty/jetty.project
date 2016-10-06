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

package org.eclipse.jetty.annotations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.naming.Context;
import javax.naming.InitialContext;

import org.eclipse.jetty.annotations.AnnotationParser.AbstractHandler;
import org.eclipse.jetty.annotations.AnnotationParser.ClassInfo;
import org.eclipse.jetty.annotations.AnnotationParser.FieldInfo;
import org.eclipse.jetty.annotations.AnnotationParser.MethodInfo;
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.junit.After;
import org.junit.Test;

/**
 *
 */
public class TestAnnotationInheritance
{
    List<String> classNames = new ArrayList<String>();
  
    
    class SampleHandler extends AbstractHandler
    {
        public final List<String> annotatedClassNames = new ArrayList<String>();
        public final List<String> annotatedMethods = new ArrayList<String>();
        public final List<String> annotatedFields = new ArrayList<String>();

        public void handle(ClassInfo info, String annotation)
        {
            if (annotation == null || !"org.eclipse.jetty.annotations.Sample".equals(annotation))
                return;
            
            annotatedClassNames.add(info.getClassName());
        }

        public void handle(FieldInfo info, String annotation)
        {   
            if (annotation == null || !"org.eclipse.jetty.annotations.Sample".equals(annotation))
                return;
            annotatedFields.add(info.getClassInfo().getClassName()+"."+info.getFieldName());
        }

        public void handle(MethodInfo info, String annotation)
        {
            if (annotation == null || !"org.eclipse.jetty.annotations.Sample".equals(annotation))
                return;
            annotatedMethods.add(info.getClassInfo().getClassName()+"."+info.getMethodName());
        }
        
        @Override
        public String toString()
        {
            return annotatedClassNames.toString()+annotatedMethods+annotatedFields;
        }
    }

    @After
    public void destroy() throws Exception
    {
        classNames.clear();
        InitialContext ic = new InitialContext();
        Context comp = (Context)ic.lookup("java:comp");
        comp.destroySubcontext("env");
    }

    @Test
    public void testParseClassNames() throws Exception
    {
        classNames.add(ClassA.class.getName());
        classNames.add(ClassB.class.getName());

        SampleHandler handler = new SampleHandler();
        AnnotationParser parser = new AnnotationParser();
        parser.parse(Collections.singleton(handler), classNames);

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

    @Test
    public void testParseClass() throws Exception
    {
        SampleHandler handler = new SampleHandler();
        AnnotationParser parser = new AnnotationParser();
        parser.parse(Collections.singleton(handler), ClassB.class, true);

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

    @Test
    public void testTypeInheritanceHandling() throws Exception
    {
       ConcurrentHashMap<String, ConcurrentHashSet<String>> map = new ConcurrentHashMap<String, ConcurrentHashSet<String>>();
        
        AnnotationParser parser = new AnnotationParser();
        ClassInheritanceHandler handler = new ClassInheritanceHandler(map);

        class Foo implements InterfaceD
        {
        }

        classNames.clear();
        classNames.add(ClassA.class.getName());
        classNames.add(ClassB.class.getName());
        classNames.add(InterfaceD.class.getName());
        classNames.add(Foo.class.getName());

        parser.parse(Collections.singleton(handler), classNames);

        assertNotNull(map);
        assertFalse(map.isEmpty());
        assertEquals(2, map.size());
      
        
        assertTrue (map.keySet().contains("org.eclipse.jetty.annotations.ClassA"));
        assertTrue (map.keySet().contains("org.eclipse.jetty.annotations.InterfaceD"));
        ConcurrentHashSet<String> classes = map.get("org.eclipse.jetty.annotations.ClassA");
        assertEquals(1, classes.size());
        assertEquals ("org.eclipse.jetty.annotations.ClassB", classes.iterator().next());

        classes = map.get("org.eclipse.jetty.annotations.InterfaceD");
        assertEquals(2, classes.size());
        assertTrue(classes.contains("org.eclipse.jetty.annotations.ClassB"));
        assertTrue(classes.contains(Foo.class.getName()));
    }
}
