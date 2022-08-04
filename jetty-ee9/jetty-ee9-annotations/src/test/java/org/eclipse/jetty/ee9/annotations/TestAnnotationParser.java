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

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.toolchain.test.FS;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
            if (!annotationName.equals(annotation))
                return;
            foundClasses.add(info.getClassName());
        }
    }

    public static class DuplicateClassScanHandler extends AnnotationParser.AbstractHandler
    {
        private Map<String, List<String>> _classMap = new ConcurrentHashMap<>();

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
        Path root = testdir.getEmptyPathDir();
        copyClass(ClassA.class, root);

        AnnotationParser parser = new AnnotationParser();

        class SampleAnnotationHandler extends AnnotationParser.AbstractHandler
        {
            private List<String> methods = Arrays.asList("a", "b", "c", "d", "l");

            @Override
            public void handle(AnnotationParser.ClassInfo info, String annotation)
            {
                if (!Sample.class.getName().equals(annotation))
                    return;

                assertEquals(ClassA.class.getName(), info.getClassName());
            }

            @Override
            public void handle(AnnotationParser.FieldInfo info, String annotation)
            {
                if (!Sample.class.getName().equals(annotation))
                    return;
                assertEquals("m", info.getFieldName());
                assertEquals(org.objectweb.asm.Type.OBJECT, org.objectweb.asm.Type.getType(info.getFieldType()).getSort());
            }

            @Override
            public void handle(AnnotationParser.MethodInfo info, String annotation)
            {
                if (!Sample.class.getName().equals(annotation))
                    return;
                assertEquals(ClassA.class.getName(), info.getClassInfo().getClassName());
                assertThat(info.getMethodName(), is(in(methods)));
                assertEquals(Sample.class.getName(), annotation);
            }
        }

        parser.parse(Collections.singleton(new SampleAnnotationHandler()), Resource.newResource(root));
    }

    @Test
    public void testMultiAnnotation() throws Exception
    {
        Path root = testdir.getEmptyPathDir();
        copyClass(ClassB.class, root);
        AnnotationParser parser = new AnnotationParser();

        class MultiAnnotationHandler extends AnnotationParser.AbstractHandler
        {
            @Override
            public void handle(AnnotationParser.ClassInfo info, String annotation)
            {
                if (!Multi.class.getName().equals(annotation))
                    return;
                assertEquals(ClassB.class.getName(), info.getClassName());
            }

            @Override
            public void handle(AnnotationParser.FieldInfo info, String annotation)
            {
                assertNotEquals(Multi.class.getName(), annotation, "There should not be any");
            }

            @Override
            public void handle(AnnotationParser.MethodInfo info, String annotation)
            {
                if (!Multi.class.getName().equals(annotation))
                    return;
                assertEquals(ClassB.class.getName(), info.getClassInfo().getClassName());
                assertEquals("a", info.getMethodName());
            }
        }

        parser.parse(Collections.singleton(new MultiAnnotationHandler()), Resource.newResource(root));
    }

    @Test
    public void testHiddenFilesInJar() throws Exception
    {
        Path badClassesJar = MavenTestingUtils.getTargetPath("test-classes/bad-classes.jar");
        AnnotationParser parser = new AnnotationParser();
        Set<AnnotationParser.Handler> emptySet = Collections.emptySet();
        parser.parse(emptySet, Resource.newResource(badClassesJar));
        // only the valid classes inside bad-classes.jar should be parsed. If any invalid classes are parsed and exception would be thrown here
    }

    @Test
    public void testModuleInfoClassInJar() throws Exception
    {
        Path badClassesJar = MavenTestingUtils.getTargetPath("test-classes/jdk9/slf4j-api-1.8.0-alpha2.jar");
        AnnotationParser parser = new AnnotationParser();
        Set<AnnotationParser.Handler> emptySet = Collections.emptySet();
        parser.parse(emptySet, Resource.newResource(badClassesJar));
        // Should throw no exceptions, and happily skip the module-info.class files
    }

    @Test
    public void testJep238MultiReleaseInJar() throws Exception
    {
        Path badClassesJar = MavenTestingUtils.getTargetPath("test-classes/jdk9/log4j-api-2.9.0.jar");
        AnnotationParser parser = new AnnotationParser();
        Set<AnnotationParser.Handler> emptySet = Collections.emptySet();
        parser.parse(emptySet, Resource.newResource(badClassesJar));
        // Should throw no exceptions and work with the META-INF/versions without incident
    }

    @Test
    public void testJep238MultiReleaseInJarJDK10() throws Exception
    {
        Path jdk10Jar = MavenTestingUtils.getTargetPath("test-classes/jdk10/multirelease-10.jar");
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
        // Intentionally using a base directory name that starts with a "."
        // This mimics what you see in jenkins, hudson, hadoop, solr, camel, and selenium for their 
        // installed and/or managed webapps
        Path basedir = testdir.getPathFile(".base/workspace/classes");
        FS.ensureEmpty(basedir);

        // Copy in class that is known to have annotations.
        copyClass(ClassA.class, basedir);

        // Setup Tracker
        TrackingAnnotationHandler tracker = new TrackingAnnotationHandler(Sample.class.getName());

        // Setup annotation scanning
        AnnotationParser parser = new AnnotationParser();

        // Parse
        parser.parse(Collections.singleton(tracker), Resource.newResource(basedir));

        // Validate
        assertThat("Found Class", tracker.foundClasses, contains(ClassA.class.getName()));
    }

    @Test
    public void testScanDuplicateClassesInJars() throws Exception
    {
        Resource testJar = Resource.newResource(MavenTestingUtils.getTargetPath("test-classes/tinytest.jar"));
        Resource testJar2 = Resource.newResource(MavenTestingUtils.getTargetPath("test-classes/tinytest_copy.jar"));
        AnnotationParser parser = new AnnotationParser();
        DuplicateClassScanHandler handler = new DuplicateClassScanHandler();
        Set<AnnotationParser.Handler> handlers = Collections.singleton(handler);
        parser.parse(handlers, testJar);
        parser.parse(handlers, testJar2);
        List<String> locations = handler.getParsedList("org.acme.ClassOne");
        assertNotNull(locations);
        assertEquals(2, locations.size());
        assertFalse(locations.get(0).equals(locations.get(1)));
    }

    @Test
    public void testScanDuplicateClasses() throws Exception
    {
        Resource testJar = Resource.newResource(MavenTestingUtils.getTargetPath("test-classes/tinytest.jar"));
        File testClasses = new File(MavenTestingUtils.getTargetDir(), "test-classes");
        AnnotationParser parser = new AnnotationParser();
        DuplicateClassScanHandler handler = new DuplicateClassScanHandler();
        Set<AnnotationParser.Handler> handlers = Collections.singleton(handler);
        parser.parse(handlers, testJar);
        parser.parse(handlers, Resource.newResource(testClasses.toPath()));
        List<String> locations = handler.getParsedList("org.acme.ClassOne");
        assertNotNull(locations);
        assertEquals(2, locations.size());
        assertNotEquals(locations.get(0), locations.get(1));
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
