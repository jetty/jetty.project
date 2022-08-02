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

package org.eclipse.jetty.ee10.annotations;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.naming.Context;
import javax.naming.InitialContext;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class TestAnnotationInheritance
{
    public WorkDir workDir;

    List<String> classNames = new ArrayList<String>();

    class SampleHandler extends AnnotationParser.AbstractHandler
    {
        public final List<String> annotatedClassNames = new ArrayList<String>();
        public final List<String> annotatedMethods = new ArrayList<String>();
        public final List<String> annotatedFields = new ArrayList<String>();

        @Override
        public void handle(AnnotationParser.ClassInfo info, String annotation)
        {
            if (annotation == null || !"org.eclipse.jetty.ee10.annotations.Sample".equals(annotation))
                return;

            annotatedClassNames.add(info.getClassName());
        }

        @Override
        public void handle(AnnotationParser.FieldInfo info, String annotation)
        {
            if (annotation == null || !"org.eclipse.jetty.ee10.annotations.Sample".equals(annotation))
                return;
            annotatedFields.add(info.getClassInfo().getClassName() + "." + info.getFieldName());
        }

        @Override
        public void handle(AnnotationParser.MethodInfo info, String annotation)
        {
            if (annotation == null || !"org.eclipse.jetty.ee10.annotations.Sample".equals(annotation))
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
        Path root = workDir.getEmptyPathDir();
        copyClass(ClassA.class, root);
        copyClass(ClassB.class, root);

        SampleHandler handler = new SampleHandler();
        AnnotationParser parser = new AnnotationParser();
        Resource rootResource = Resource.newResource(root);
        parser.parse(Collections.singleton(handler), rootResource);

        //check we got  2 class annotations
        assertEquals(2, handler.annotatedClassNames.size());

        //check we got all annotated methods on each class
        assertEquals(7, handler.annotatedMethods.size());
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.ee10.annotations.ClassA.a"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.ee10.annotations.ClassA.b"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.ee10.annotations.ClassA.c"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.ee10.annotations.ClassA.d"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.ee10.annotations.ClassA.l"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.ee10.annotations.ClassB.a"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.ee10.annotations.ClassB.c"));

        //check we got all annotated fields on each class
        assertEquals(1, handler.annotatedFields.size());
        assertEquals("org.eclipse.jetty.ee10.annotations.ClassA.m", handler.annotatedFields.get(0));
    }

    @Test
    public void testParseClass() throws Exception
    {
        Path root = workDir.getEmptyPathDir();
        copyClass(ClassA.class, root);
        copyClass(ClassB.class, root);

        SampleHandler handler = new SampleHandler();
        AnnotationParser parser = new AnnotationParser();

        parser.parse(Collections.singleton(handler), Resource.newResource(root));

        //check we got  2 class annotations
        assertEquals(2, handler.annotatedClassNames.size());

        //check we got all annotated methods on each class
        assertEquals(7, handler.annotatedMethods.size());
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.ee10.annotations.ClassA.a"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.ee10.annotations.ClassA.b"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.ee10.annotations.ClassA.c"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.ee10.annotations.ClassA.d"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.ee10.annotations.ClassA.l"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.ee10.annotations.ClassB.a"));
        assertTrue(handler.annotatedMethods.contains("org.eclipse.jetty.ee10.annotations.ClassB.c"));

        //check we got all annotated fields on each class
        assertEquals(1, handler.annotatedFields.size());
        assertEquals("org.eclipse.jetty.ee10.annotations.ClassA.m", handler.annotatedFields.get(0));
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

        Path root = workDir.getEmptyPathDir();
        copyClass(ClassA.class, root);
        copyClass(ClassB.class, root);
        copyClass(InterfaceD.class, root);
        copyClass(Foo.class, root);

        Resource rootResource = Resource.newResource(root);
        parser.parse(Collections.singleton(handler), rootResource);

        assertNotNull(map);
        assertFalse(map.isEmpty());
        assertEquals(2, map.size());

        assertThat(map, hasKey("org.eclipse.jetty.ee10.annotations.ClassA"));
        assertThat(map, hasKey("org.eclipse.jetty.ee10.annotations.InterfaceD"));
        Set<String> classes = map.get("org.eclipse.jetty.ee10.annotations.ClassA");
        assertThat(classes, contains("org.eclipse.jetty.ee10.annotations.ClassB"));

        classes = map.get("org.eclipse.jetty.ee10.annotations.InterfaceD");
        assertThat(classes, containsInAnyOrder("org.eclipse.jetty.ee10.annotations.ClassB",
            Foo.class.getName()));
    }

    private void copyClass(Class<?> clazz, Path outputDir) throws IOException, URISyntaxException
    {
        String classRef = TypeUtil.toClassReference(clazz);
        URL url = this.getClass().getResource('/' + classRef);
        assertThat("URL for: " + classRef, url, notNullValue());

        Path srcClass = Paths.get(url.toURI());
        Path dest = outputDir.resolve(classRef);
        FS.ensureDirExists(dest.getParent());
        Files.copy(srcClass, dest);
    }
}
