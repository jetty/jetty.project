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

package org.eclipse.jetty.ee9.annotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.naming.Context;
import javax.naming.InitialContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *
 */
public class TestAnnotationInheritance
{
    List<String> classNames = new ArrayList<String>();

    class SampleHandler extends AnnotationParser.AbstractHandler
    {
        public final List<String> annotatedClassNames = new ArrayList<String>();
        public final List<String> annotatedMethods = new ArrayList<String>();
        public final List<String> annotatedFields = new ArrayList<String>();

        @Override
        public void handle(AnnotationParser.ClassInfo info, String annotation)
        {
            if (annotation == null || !"org.eclipse.jetty.annotations.Sample".equals(annotation))
                return;

            annotatedClassNames.add(info.getClassName());
        }

        @Override
        public void handle(AnnotationParser.FieldInfo info, String annotation)
        {
            if (annotation == null || !"org.eclipse.jetty.annotations.Sample".equals(annotation))
                return;
            annotatedFields.add(info.getClassInfo().getClassName() + "." + info.getFieldName());
        }

        @Override
        public void handle(AnnotationParser.MethodInfo info, String annotation)
        {
            if (annotation == null || !"org.eclipse.jetty.annotations.Sample".equals(annotation))
                return;
            annotatedMethods.add(info.getClassInfo().getClassName() + "." + info.getMethodName());
        }

        @Override
        public String toString()
        {
            return annotatedClassNames.toString() + annotatedMethods + annotatedFields;
        }
    }

    @AfterEach
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
        assertEquals(7, handler.annotatedMethods.size());
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassA.a"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassA.b"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassA.c"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassA.d"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassA.l"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassB.a"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassB.c"));

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
        assertEquals(7, handler.annotatedMethods.size());
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassA.a"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassA.b"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassA.c"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassA.d"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassA.l"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassB.a"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.annotations.ClassB.c"));

        //check we got all annotated fields on each class
        assertEquals(1, handler.annotatedFields.size());
        assertEquals("org.eclipse.jetty.annotations.ClassA.m", handler.annotatedFields.get(0));
    }

    @Test
    public void testTypeInheritanceHandling() throws Exception
    {
        Map<String, Set<String>> map = new ConcurrentHashMap<>();

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

        assertThat(map, hasKey("org.eclipse.jetty.annotations.ClassA"));
        assertThat(map, hasKey("org.eclipse.jetty.annotations.InterfaceD"));
        Set<String> classes = map.get("org.eclipse.jetty.annotations.ClassA");
        assertThat(classes, contains("org.eclipse.jetty.annotations.ClassB"));

        classes = map.get("org.eclipse.jetty.annotations.InterfaceD");
        assertThat(classes, containsInAnyOrder("org.eclipse.jetty.annotations.ClassB",
            Foo.class.getName()));
    }
}
