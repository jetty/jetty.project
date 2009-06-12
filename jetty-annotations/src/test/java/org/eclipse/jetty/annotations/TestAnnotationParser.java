// ========================================================================
// Copyright (c) 2009 Mort Bay Consulting Pty. Ltd.
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
import java.util.Arrays;
import java.util.List;

import junit.framework.TestCase;

import org.eclipse.jetty.annotations.AnnotationParser.AnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.AnnotationNameValue;
import org.eclipse.jetty.annotations.AnnotationParser.MultiValue;
import org.eclipse.jetty.annotations.AnnotationParser.SimpleValue;
import org.eclipse.jetty.annotations.AnnotationParser.Value;


public class TestAnnotationParser extends TestCase
{
    
    public void testSampleAnnotation ()
    throws Exception
    {      
        
        
        String[] classNames = new String[]{"org.eclipse.jetty.annotations.ClassA"};
        AnnotationParser parser = new AnnotationParser();
        
        
        class SampleAnnotationHandler implements AnnotationHandler
        {
            List<String> methods = Arrays.asList("a", "b", "c", "d", "l");
            
            

            public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                                    List<AnnotationNameValue> values)
            {
                assertEquals ("org.eclipse.jetty.annotations.ClassA", className);
            }

            public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                                   List<AnnotationNameValue> values)
            {
              assertEquals ("m", fieldName);
              assertEquals (org.objectweb.asm.Type.OBJECT, org.objectweb.asm.Type.getType(fieldType).getSort());
              assertEquals (1, values.size());
              AnnotationNameValue anv1 = values.get(0);
              assertEquals ("value", anv1.getName());
              assertEquals (7, anv1.getValue().getValue());

            }

            public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                                     List<AnnotationNameValue> values)
            {
               assertEquals("org.eclipse.jetty.annotations.ClassA", className);
               assertTrue(methods.contains(methodName));
               assertEquals("org.eclipse.jetty.annotations.Sample", annotation);
            }
            
        }
        
        parser.registerAnnotationHandler("org.eclipse.jetty.annotations.Sample", new SampleAnnotationHandler());
        
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

        System.err.println("Time to parse class: "+((end-start)));
    } 


    public void testServletAnnotation()
    throws Exception
    {
        List<String> classes = new ArrayList<String>();
        classes.add("org.eclipse.jetty.annotations.ServletC");
        AnnotationParser parser = new AnnotationParser();

        class MultipartAnnotationHandler implements AnnotationHandler
        {
            public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                                    List<AnnotationNameValue> values)
            {
                    assertEquals(3, values.size());
            }
            public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                                    List<AnnotationNameValue> values)
            {}
            
            public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                                     List<AnnotationNameValue> values)
            {}
        }
        
        class ResourceAnnotationHandler implements AnnotationHandler
        {
            public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                                    List<AnnotationNameValue> values)
            {}
            
            public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                                    List<AnnotationNameValue> values)
            {
                assertEquals ("org.eclipse.jetty.annotations.ServletC", className);
            
            assertEquals ("foo",fieldName);
            assertNotNull (values);
            assertNotNull (annotation);
            assertTrue (annotation.endsWith("Resource"));
            assertEquals (1, values.size());
            AnnotationNameValue anv = values.get(0);
            assertEquals ("mappedName", anv.getName());
            assertEquals ("foo", anv.getValue().getValue());
            }
            
            public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                                     List<AnnotationNameValue> values)
            {}
            
        }

        class ServletAnnotationHandler implements AnnotationHandler
        {

            public void print (AnnotationNameValue anv)
            {
                System.err.print(anv.getName()+":");
                Value v = anv.getValue();
                if (v instanceof SimpleValue)
                    System.err.println(v.getValue());
                else if (v instanceof MultiValue)
                {
                    List<AnnotationNameValue> list = (List<AnnotationNameValue>)v.getValue();
                    System.err.println();
                    for (AnnotationNameValue anv2 : list)
                    {
                        System.err.print("\t");
                        print (anv2);
                    }
                     
                }
            }
            
            public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                                    List<AnnotationNameValue> values)
            {
                assertNotNull(annotation);
                if (annotation.endsWith("WebServlet"))
                {
                    assertEquals(5, values.size());
                    for (AnnotationNameValue anv: values)
                    {
                        if (anv.getName().equals("name"))
                            assertEquals("CServlet", anv.getValue().getValue());
                        else if (anv.getName().equals("urlPatterns"))
                        {
                            Value v = anv.getValue();
                            assertTrue (v instanceof MultiValue);
                            assertEquals (2, ((MultiValue)v).size());
                            List<AnnotationNameValue> urlPatterns = (List<AnnotationNameValue>)((MultiValue)v).getValue();
                            assertNull(urlPatterns.get(0).getName());
                            assertEquals("/foo/*", urlPatterns.get(0).getValue().getValue());
                        }
                    }
                }
                else
                    fail("Unknown annotation: "+annotation);
            }
        
            public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                                    List<AnnotationNameValue> values)
            {}
            public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                                     List<AnnotationNameValue> values)
            {}
        }

        
        class CallbackAnnotationHandler implements AnnotationHandler
        {
            public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                                    List<AnnotationNameValue> values)
            {}

            public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                                     List<AnnotationNameValue> values)
            {
                assertEquals ("org.eclipse.jetty.annotations.ServletC", className);
                assertNotNull(methodName);
                if (methodName.endsWith("pre"))
                {
                    assertTrue(annotation.endsWith("PreDestroy"));
                    assertTrue(values.isEmpty());
                }
                else if (methodName.endsWith("post"))
                {
                    assertTrue(annotation.endsWith("PostConstruct"));
                    assertTrue(values.isEmpty());
                }
            }
            public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                                    List<AnnotationNameValue> values)
            {}
        }
        
        class RunAsAnnotationHandler implements AnnotationHandler
        {
            public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                                    List<AnnotationNameValue> values)
            {
                assertNotNull (values);
                assertEquals(1, values.size());
                AnnotationNameValue anv = values.get(0);
                assertEquals("value", anv.getName());
                assertEquals("admin", anv.getValue().getValue());
            }
            
            public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                                     List<AnnotationNameValue> values)
            {}
            public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                                    List<AnnotationNameValue> values)
            {}
        }
        parser.registerAnnotationHandler("javax.servlet.annotation.WebServlet", new ServletAnnotationHandler());
        parser.registerAnnotationHandler("javax.servlet.annotation.MultipartConfig", new MultipartAnnotationHandler ());
        parser.registerAnnotationHandler("javax.annotation.Resource", new ResourceAnnotationHandler ());
        parser.registerAnnotationHandler("javax.annotation.PostConstruct", new CallbackAnnotationHandler());
        parser.registerAnnotationHandler("javax.annotation.PreDestroy", new CallbackAnnotationHandler());
        parser.registerAnnotationHandler("javax.annotation.security.RunAs", new RunAsAnnotationHandler());

        long start = System.currentTimeMillis();
        parser.parse(classes, new ClassNameResolver () 
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

        System.err.println("Time to parse class: "+((end-start)));
    }
}
