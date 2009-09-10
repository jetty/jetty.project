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

import junit.framework.TestCase;

import org.eclipse.jetty.annotations.AnnotationParser.AnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.Value;

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

      
        
        class ResourceAnnotationHandler implements AnnotationHandler
        {
            public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                                    List<Value> values)
            {}

            public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                                    List<Value> values)
            {
                assertEquals ("org.eclipse.jetty.annotations.ServletC", className);

                assertEquals ("foo",fieldName);
                assertNotNull (values);
                assertNotNull (annotation);
                assertTrue (annotation.endsWith("Resource"));
                
                for (Value v :values)
                {
                    if (v.getName().equals("mappedName"))
                        assertEquals ("foo", v.getValue());
                    else if (v.getName().equals("type"))
                    {
                        try
                        { 
                            assertEquals(fieldType, ((org.objectweb.asm.Type)v.getValue()).getDescriptor());
                        }
                        catch (Exception e)
                        {
                            fail(e.getMessage());
                        }
                    }
                }
            }

            public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                                     List<Value> values)
            {}

        }

     
        
        class CallbackAnnotationHandler implements AnnotationHandler
        {
            public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                                    List<Value> values)
            {}

            public void handleMethod(String className, String methodName, int access, String desc, String signature, String[] exceptions, String annotation,
                                     List<Value> values)
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
                
                assertEquals (org.objectweb.asm.Type.VOID_TYPE, org.objectweb.asm.Type.getReturnType(desc));
                assertEquals(0,  org.objectweb.asm.Type.getArgumentTypes(desc).length);
                int isstatic = access & org.objectweb.asm.Opcodes.ACC_STATIC;
               
                assertTrue (isstatic == 0);  
            }
            public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                                    List<Value> values)
            {}
        }
        
        class RunAsAnnotationHandler implements AnnotationHandler
        {
            public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                                    List<Value> values)
            {
                assertNotNull (values);
                assertEquals(1, values.size());
                Value anv = values.get(0);
                assertEquals("value", anv.getName());
                assertEquals("admin", anv.getValue());    
            }
            
            public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                                     List<Value> values)
            {}
            public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                                    List<Value> values)
            {}
        }
        
        
        class ResourcesAnnotationHandler implements AnnotationHandler
        {
            public void handleClass (String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                    List<Value> values)
            {
                assertNotNull (values);
                for (Value v : values)
                {
                    List list = (List)(v.getValue());
                    for (Object o : list)
                    {
                        AnnotationParser.ListValue lv = (AnnotationParser.ListValue)o;
                        List<Value> theValues = lv.getList();
                        for (Value vv : theValues)
                        {
                            if ("name".equals((String)vv.getName()))
                            {
                                if (!"apple".equals((String)vv.getValue()) && !"banana".equals((String)vv.getValue()))
                                    fail("Wrong name "+vv.getName());
                            }
                            else if ("mappedName".equals((String)vv.getName()))
                                assertEquals("foo", (String)vv.getValue());
                                    
                        }           
                    }
                }  
            }
            public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                    List<Value> values)
            {}
            public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                    List<Value> values)
            {}
        }
        
      
     
        parser.registerAnnotationHandler("javax.annotation.Resources", new ResourcesAnnotationHandler());
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
