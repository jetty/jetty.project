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

import org.eclipse.jetty.annotations.AnnotationParser.AnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.AnnotationNameValue;
import org.eclipse.jetty.annotations.AnnotationParser.MultiValue;
import org.eclipse.jetty.annotations.AnnotationParser.SimpleValue;
import org.eclipse.jetty.annotations.AnnotationParser.Value;

import junit.framework.TestCase;

/**
 * TestServletAnnotations
 *
 *
 */
public class TestServletAnnotations extends TestCase
{


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
        
        class RolesAllowedHandler implements AnnotationHandler
        {

            @Override
            public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                                    List<AnnotationNameValue> values)
            {
                System.err.println("ROLESALLOWED: ");
                assertNotNull(values);
                assertEquals(1,values.size());
                AnnotationNameValue anv  = values.get(0);
                assertEquals("value", anv.getName());
                //assertTrue(anv.getValue().getValue().getClass().isArray()); 
                System.err.println("VALUE: "+anv.getValue().getValue());
            }

            @Override
            public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                                    List<AnnotationNameValue> values)
            {}

            @Override
            public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                                     List<AnnotationNameValue> values)
            {}
            
        }
        parser.registerAnnotationHandler("javax.servlet.annotation.WebServlet", new ServletAnnotationHandler());
        parser.registerAnnotationHandler("javax.servlet.annotation.MultipartConfig", new MultipartAnnotationHandler ());
        parser.registerAnnotationHandler("javax.annotation.Resource", new ResourceAnnotationHandler ());
        parser.registerAnnotationHandler("javax.annotation.PostConstruct", new CallbackAnnotationHandler());
        parser.registerAnnotationHandler("javax.annotation.PreDestroy", new CallbackAnnotationHandler());
        parser.registerAnnotationHandler("javax.annotation.security.RunAs", new RunAsAnnotationHandler());
        parser.registerAnnotationHandler("javax.annotation.security.RolesAllowed", new RolesAllowedHandler());

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
