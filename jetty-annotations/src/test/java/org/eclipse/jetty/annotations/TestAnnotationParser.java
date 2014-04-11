//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.annotations.AnnotationParser.DiscoverableAnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestAnnotationParser
{
   
    @Rule
    public TestingDir testdir = new TestingDir();
    
    
    
    public static class TrackingAnnotationHandler implements DiscoverableAnnotationHandler
    {

        private final String annotationName;
        public final Set<String> foundClasses;

        public TrackingAnnotationHandler(String annotationName)
        {
            this.annotationName = annotationName;
            this.foundClasses = new HashSet<String>();
        }

       
        public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                List<Value> values)
        {
            foundClasses.add(className);
        }

      
        public void handleMethod(String className, String methodName, int access, String desc, String signature, String[] exceptions, String annotation,
                List<Value> values)
        {
            /* ignore */
        }

       
        public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                List<Value> values)
        {
            /* ignore */
        }


        @Override
        public String getAnnotationName()
        {
           return this.annotationName;
        }
    }



    
    @Test
    public void testSampleAnnotation() throws Exception
    {
        String[] classNames = new String[]{"org.eclipse.jetty.annotations.ClassA"};
        AnnotationParser parser = new AnnotationParser();

        class SampleAnnotationHandler implements DiscoverableAnnotationHandler
        {
            private List<String> methods = Arrays.asList("a", "b", "c", "d", "l");

           
            
            
            public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                                    List<Value> values)
            {
                assertEquals ("org.eclipse.jetty.annotations.ClassA", className);
            }

            public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                                    List<Value> values)
            {
                assertEquals ("m", fieldName);
                assertEquals (org.objectweb.asm.Type.OBJECT, org.objectweb.asm.Type.getType(fieldType).getSort());
                assertEquals (1, values.size());
                Value anv1 = values.get(0);
                assertEquals ("value", anv1.getName());
                assertEquals (7, anv1.getValue());

            }

            public void handleMethod(String className, String methodName, int access, String desc, String signature, String[] exceptions, String annotation,
                                     List<Value> values)
            {
                assertEquals("org.eclipse.jetty.annotations.ClassA", className);
                assertTrue(methods.contains(methodName));
                assertEquals("org.eclipse.jetty.annotations.Sample", annotation);
            }

            @Override
            public String getAnnotationName()
            {
                return "org.eclipse.jetty.annotations.Sample";
            }
        }

        parser.registerHandler(new SampleAnnotationHandler());

        long start = System.currentTimeMillis();
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
        long end = System.currentTimeMillis();
        //System.err.println("Time to parse class: "+((end-start)));
    }

    @Test
    public void testMultiAnnotation() throws Exception
    {
        String[] classNames = new String[]{"org.eclipse.jetty.annotations.ClassB"};
        AnnotationParser parser = new AnnotationParser();

        class MultiAnnotationHandler implements DiscoverableAnnotationHandler
        {
            public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                                    List<Value> values)
            {
                assertTrue("org.eclipse.jetty.annotations.ClassB".equals(className));
            }

            public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                                    List<Value> values)
            {
                //there should not be any
                fail();
            }

            public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                                     List<Value> values)
            {
                assertTrue("org.eclipse.jetty.annotations.ClassB".equals(className));
                assertTrue("a".equals(methodName));
            }

            @Override
            public String getAnnotationName()
            {
                return "org.eclipse.jetty.annotations.Multi";
            }
            
            
        }

        parser.registerHandler(new MultiAnnotationHandler());
        parser.parse(classNames, null);
    }


    @Test
    public void testHiddenFilesInJar () throws Exception
    {
        File badClassesJar = MavenTestingUtils.getTestResourceFile("bad-classes.jar");
        AnnotationParser parser = new AnnotationParser();
        parser.parse(badClassesJar.toURI(), null);
        //only the valid classes inside bad-classes.jar should be parsed. If any invalid classes are parsed and exception would be thrown here
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
        parser.registerHandler(tracker);

        // Parse
        parser.parse(Resource.newResource(basedir),null);

        // Validate
        assertTrue(tracker.foundClasses.contains(ClassA.class.getName()));
    }

    private void copyClass(Class<?> clazz, File basedir) throws IOException
    {
        String classname = clazz.getName().replace('.',File.separatorChar) + ".class";
        URL url = this.getClass().getResource('/'+classname);
        assertTrue(url != null);

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
