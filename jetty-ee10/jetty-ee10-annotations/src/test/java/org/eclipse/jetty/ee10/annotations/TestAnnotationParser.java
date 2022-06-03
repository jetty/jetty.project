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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class TestAnnotationParser
{
    public static class TrackingAnnotationHandler extends AnnotationParser.AbstractHandler
    {
        private final String annotationName;
        public final Set<String> foundClasses;

        public TrackingAnnotationHandler(String annotationName)
        {
            this.annotationName = annotationName;
            this.foundClasses = new HashSet<>();
        }

        @Override
        public void handle(AnnotationParser.ClassInfo info, String annotation)
        {
            if (annotation == null || !annotationName.equals(annotation))
                return;
            foundClasses.add(info.getClassName());
        }
    }

    public static class DuplicateClassScanHandler extends AnnotationParser.AbstractHandler
    {
        private Map<String, List<String>> _classMap = new ConcurrentHashMap();

        @Override
        public void handle(AnnotationParser.ClassInfo info)
        {
            List<String> list = new CopyOnWriteArrayList<>();
            Resource r = info.getContainingResource();
            list.add((r == null ? "" : r.toString()));

            List<String> existing = _classMap.putIfAbsent(info.getClassName(), list);
            if (existing != null)
            {
                existing.addAll(list);
            }
        }

        public List<String> getParsedList(String classname)
        {
            return _classMap.get(classname);
        }
    }

    public WorkDir testdir;

    @Test
    public void testSampleAnnotation() throws Exception
    {
        String[] classNames = new String[]{"org.eclipse.jetty.annotations.ClassA"};
        AnnotationParser parser = new AnnotationParser();

        class SampleAnnotationHandler extends AnnotationParser.AbstractHandler
        {
            private List<String> methods = Arrays.asList("a", "b", "c", "d", "l");

            @Override
            public void handle(AnnotationParser.ClassInfo info, String annotation)
            {
                if (annotation == null || !"org.eclipse.jetty.annotations.Sample".equals(annotation))
                    return;

                assertEquals("org.eclipse.jetty.annotations.ClassA", info.getClassName());
            }

            @Override
            public void handle(AnnotationParser.FieldInfo info, String annotation)
            {
                if (annotation == null || !"org.eclipse.jetty.annotations.Sample".equals(annotation))
                    return;
                assertEquals("m", info.getFieldName());
                assertEquals(org.objectweb.asm.Type.OBJECT, org.objectweb.asm.Type.getType(info.getFieldType()).getSort());
            }

            @Override
            public void handle(AnnotationParser.MethodInfo info, String annotation)
            {
                if (annotation == null || !"org.eclipse.jetty.annotations.Sample".equals(annotation))
                    return;
                assertEquals("org.eclipse.jetty.annotations.ClassA", info.getClassInfo().getClassName());
                assertThat(info.getMethodName(), is(in(methods)));
                assertEquals("org.eclipse.jetty.annotations.Sample", annotation);
            }
        }

        //long start = System.currentTimeMillis();
        parser.parse(Collections.singleton(new SampleAnnotationHandler()), classNames);
        //long end = System.currentTimeMillis();

        //System.err.println("Time to parse class: " + ((end - start)));
    }

    @Test
    public void testMultiAnnotation() throws Exception
    {
        String[] classNames = new String[]{"org.eclipse.jetty.annotations.ClassB"};
        AnnotationParser parser = new AnnotationParser();

        class MultiAnnotationHandler extends AnnotationParser.AbstractHandler
        {
            @Override
            public void handle(AnnotationParser.ClassInfo info, String annotation)
            {
                if (annotation == null || !"org.eclipse.jetty.annotations.Multi".equals(annotation))
                    return;
                assertTrue("org.eclipse.jetty.annotations.ClassB".equals(info.getClassName()));
            }

            @Override
            public void handle(AnnotationParser.FieldInfo info, String annotation)
            {
                assertTrue(annotation == null || !"org.eclipse.jetty.annotations.Multi".equals(annotation),
                    "There should not be any");
            }

            @Override
            public void handle(AnnotationParser.MethodInfo info, String annotation)
            {
                if (annotation == null || !"org.eclipse.jetty.annotations.Multi".equals(annotation))
                    return;
                assertTrue("org.eclipse.jetty.annotations.ClassB".equals(info.getClassInfo().getClassName()));
                assertTrue("a".equals(info.getMethodName()));
            }
        }

        parser.parse(Collections.singleton(new MultiAnnotationHandler()), classNames);
    }

    @Test
    public void testHiddenFilesInJar() throws Exception
    {
        File badClassesJar = MavenTestingUtils.getTestResourceFile("bad-classes.jar");
        AnnotationParser parser = new AnnotationParser();
        Set<AnnotationParser.Handler> emptySet = Collections.emptySet();
        parser.parse(emptySet, badClassesJar.toURI());
        // only the valid classes inside bad-classes.jar should be parsed. If any invalid classes are parsed and exception would be thrown here
    }

    @Test
    public void testModuleInfoClassInJar() throws Exception
    {
        File badClassesJar = MavenTestingUtils.getTestResourceFile("jdk9/slf4j-api-1.8.0-alpha2.jar");
        AnnotationParser parser = new AnnotationParser();
        Set<AnnotationParser.Handler> emptySet = Collections.emptySet();
        parser.parse(emptySet, badClassesJar.toURI());
        // Should throw no exceptions, and happily skip the module-info.class files
    }

    @Test
    public void testJep238MultiReleaseInJar() throws Exception
    {
        File badClassesJar = MavenTestingUtils.getTestResourceFile("jdk9/log4j-api-2.9.0.jar");
        AnnotationParser parser = new AnnotationParser();
        Set<AnnotationParser.Handler> emptySet = Collections.emptySet();
        parser.parse(emptySet, badClassesJar.toURI());
        // Should throw no exceptions, and skip the META-INF/versions/9/* files
    }

    @Test
    public void testJep238MultiReleaseInJarJDK10() throws Exception
    {
        File jdk10Jar = MavenTestingUtils.getTestResourceFile("jdk10/multirelease-10.jar");
        AnnotationParser parser = new AnnotationParser();
        DuplicateClassScanHandler handler = new DuplicateClassScanHandler();
        Set<AnnotationParser.Handler> handlers = Collections.singleton(handler);
        parser.parse(handlers, Resource.newResource(jdk10Jar));
        // Should throw no exceptions
    }

    @Test
    public void testBasedirExclusion() throws Exception
    {
        // Build up basedir, which itself has a path segment that violates java package and classnaming.
        // The basedir should have no effect on annotation scanning.
        // Intentionally using a base director name that starts with a "."
        // This mimics what you see in jenkins, hudson, hadoop, solr, camel, and selenium for their 
        // installed and/or managed webapps
        File basedir = testdir.getPathFile(".base/workspace/classes").toFile();
        FS.ensureEmpty(basedir);

        // Copy in class that is known to have annotations.
        copyClass(ClassA.class, basedir);

        // Setup Tracker
        TrackingAnnotationHandler tracker = new TrackingAnnotationHandler(Sample.class.getName());

        // Setup annotation scanning
        AnnotationParser parser = new AnnotationParser();

        // Parse
        parser.parse(Collections.singleton(tracker), basedir.toURI());

        // Validate
        assertThat("Found Class", tracker.foundClasses, contains(ClassA.class.getName()));
    }

    @Test
    public void testScanDuplicateClassesInJars() throws Exception
    {
        Resource testJar = Resource.newResource(MavenTestingUtils.getTestResourceFile("tinytest.jar"));
        Resource testJar2 = Resource.newResource(MavenTestingUtils.getTestResourceFile("tinytest_copy.jar"));
        AnnotationParser parser = new AnnotationParser();
        DuplicateClassScanHandler handler = new DuplicateClassScanHandler();
        Set<AnnotationParser.Handler> handlers = Collections.singleton(handler);
        parser.parse(handlers, testJar);
        parser.parse(handlers, testJar2);
        List<String> locations = handler.getParsedList("org.acme.ClassOne");
        assertNotNull(locations);
        assertEquals(2, locations.size());
        assertTrue(!(locations.get(0).equals(locations.get(1))));
    }

    @Test
    public void testScanDuplicateClasses() throws Exception
    {
        Resource testJar = Resource.newResource(MavenTestingUtils.getTestResourceFile("tinytest.jar"));
        File testClasses = new File(MavenTestingUtils.getTargetDir(), "test-classes");
        AnnotationParser parser = new AnnotationParser();
        DuplicateClassScanHandler handler = new DuplicateClassScanHandler();
        Set<AnnotationParser.Handler> handlers = Collections.singleton(handler);
        parser.parse(handlers, testJar);
        parser.parse(handlers, Resource.newResource(testClasses));
        List<String> locations = handler.getParsedList("org.acme.ClassOne");
        assertNotNull(locations);
        assertEquals(2, locations.size());
        assertTrue(!(locations.get(0).equals(locations.get(1))));
    }

    private void copyClass(Class<?> clazz, File basedir) throws IOException
    {
        String classRef = TypeUtil.toClassReference(clazz);
        URL url = this.getClass().getResource('/' + classRef);
        assertThat("URL for: " + classRef, url, notNullValue());

        Path outputFile = basedir.toPath().resolve(classRef);
        FS.ensureDirExists(outputFile.getParent());

        try (InputStream in = url.openStream();
             OutputStream out = Files.newOutputStream(outputFile))
        {
            IO.copy(in, out);
        }
    }
}
