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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.annotations.AnnotationParser.ClassInfo;
import org.eclipse.jetty.annotations.AnnotationParser.FieldInfo;
import org.eclipse.jetty.annotations.AnnotationParser.Handler;
import org.eclipse.jetty.annotations.AnnotationParser.MethodInfo;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

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
        public void handle(ClassInfo info, String annotation)
        {
            if (annotation == null || !annotationName.equals(annotation))
                return;
            foundClasses.add(info.getClassName());
        }
    }

    @Rule
    public TestingDir testdir = new TestingDir();

    @Test
    public void testSampleAnnotation() throws Exception
    {
        String[] classNames = new String[]
        { "org.eclipse.jetty.annotations.ClassA" };
        AnnotationParser parser = new AnnotationParser();

        class SampleAnnotationHandler extends AnnotationParser.AbstractHandler
        {
            private List<String> methods = Arrays.asList("a","b","c","d","l");

            public void handle(ClassInfo info, String annotation)
            {
                if (annotation == null || !"org.eclipse.jetty.annotations.Sample".equals(annotation))
                    return;

                assertEquals("org.eclipse.jetty.annotations.ClassA",info.getClassName());
            }

            public void handle(FieldInfo info, String annotation)
            {                
                if (annotation == null || !"org.eclipse.jetty.annotations.Sample".equals(annotation))
                    return;
                assertEquals("m",info.getFieldName());
                assertEquals(org.objectweb.asm.Type.OBJECT,org.objectweb.asm.Type.getType(info.getFieldType()).getSort());
            }

            public void handle(MethodInfo info, String annotation)
            {                
                if (annotation == null || !"org.eclipse.jetty.annotations.Sample".equals(annotation))
                    return;
                assertEquals("org.eclipse.jetty.annotations.ClassA",info.getClassInfo().getClassName());
                assertTrue(methods.contains(info.getMethodName()));
                assertEquals("org.eclipse.jetty.annotations.Sample",annotation);
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
        String[] classNames = new String[]
        { "org.eclipse.jetty.annotations.ClassB" };
        AnnotationParser parser = new AnnotationParser();

        class MultiAnnotationHandler extends AnnotationParser.AbstractHandler
        {
            public void handle(ClassInfo info, String annotation)
            {
                if (annotation == null || ! "org.eclipse.jetty.annotations.Multi".equals(annotation))
                    return;
                assertTrue("org.eclipse.jetty.annotations.ClassB".equals(info.getClassName()));
            }

            public void handle(FieldInfo info, String annotation)
            {                
                if (annotation == null || ! "org.eclipse.jetty.annotations.Multi".equals(annotation))
                    return;
                // there should not be any
                fail();
            }

            public void handle(MethodInfo info, String annotation)
            {  
                if (annotation == null || ! "org.eclipse.jetty.annotations.Multi".equals(annotation))
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
        Set<Handler> emptySet = Collections.emptySet();
        parser.parse(emptySet, badClassesJar.toURI());
        // only the valid classes inside bad-classes.jar should be parsed. If any invalid classes are parsed and exception would be thrown here
    }

    @Test
    public void testBasedirExclusion() throws Exception
    {
        // Build up basedir, which itself has a path segment that violates java package and classnaming.
        // The basedir should have no effect on annotation scanning.
        // Intentionally using a base director name that starts with a "."
        // This mimics what you see in jenkins, hudson, hadoop, solr, camel, and selenium for their 
        // installed and/or managed webapps
        File basedir = testdir.getFile(".base/workspace/classes");
        FS.ensureEmpty(basedir);

        // Copy in class that is known to have annotations.
        copyClass(ClassA.class,basedir);

        // Setup Tracker
        TrackingAnnotationHandler tracker = new TrackingAnnotationHandler(Sample.class.getName());

        // Setup annotation scanning
        AnnotationParser parser = new AnnotationParser();
        
        // Parse
        parser.parse(Collections.singleton(tracker), basedir.toURI());
        
        // Validate
        Assert.assertThat("Found Class", tracker.foundClasses, contains(ClassA.class.getName()));
    }

    private void copyClass(Class<?> clazz, File basedir) throws IOException
    {
        String classname = clazz.getName().replace('.',File.separatorChar) + ".class";
        URL url = this.getClass().getResource('/'+classname);
        Assert.assertThat("URL for: " + classname,url,notNullValue());

        String classpath = classname.substring(0,classname.lastIndexOf(File.separatorChar));
        FS.ensureDirExists(new File(basedir,classpath));

        InputStream in = null;
        OutputStream out = null;
        try
        {
            in = url.openStream();
            out = new FileOutputStream(new File(basedir,classname));
            IO.copy(in,out);
        }
        finally
        {
            IO.close(out);
            IO.close(in);
        }
    }
}
