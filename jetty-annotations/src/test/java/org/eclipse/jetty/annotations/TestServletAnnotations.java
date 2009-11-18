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
import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.annotations.AnnotationParser.ListValue;
import org.eclipse.jetty.annotations.AnnotationParser.SimpleValue;


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
                                    List<Value> values)
            {
                    assertEquals(3, values.size());
            }
            public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                                    List<Value> values)
            {}
            
            public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                                     List<Value> values)
            {}
        }
        
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

        class ServletAnnotationHandler implements AnnotationHandler
        {

            public void print (Value anv)
            {
                System.err.print(anv.toString());
            }
            
            public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                                    List<Value> values)
            {
                assertNotNull(annotation);
                assertTrue(annotation.endsWith("WebServlet"));
                System.err.println(annotation+": ");
                assertEquals(5, values.size());
                for (Value anv: values)
                {
                    if (anv.getName().equals("name"))
                        assertEquals("CServlet", anv.getValue());
                    else if (anv.getName().equals("urlPatterns"))
                    {
                        assertTrue (anv instanceof ListValue);
                        assertEquals (2, ((ListValue)anv).size());
                        List<Value> urlPatterns = (List<Value>)((ListValue)anv).getValue();
                        assertNull(urlPatterns.get(0).getName());
                        assertEquals("/foo/*", urlPatterns.get(0).getValue());
                    }
                    else if (anv.getName().equals("initParams"))
                    {
                        assertTrue(anv instanceof ListValue);
                    }
                    System.err.println(anv);
                }       
            }
        
            public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                                    List<Value> values)
            {}
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
        
<<<<<<< .working
        class RolesAllowedHandler implements AnnotationHandler
        {

            @Override
            public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                                    List<Value> values)
            {
                
                assertTrue(annotation.endsWith("RolesAllowed"));
                assertNotNull(values);
                assertEquals(1,values.size());
                Value anv  = values.get(0);
                assertEquals("value", anv.getName());
                assertTrue (anv instanceof ListValue);
                ListValue listval = (ListValue)anv;
                assertEquals(3, listval.size());
                ArrayList<String> roles = new ArrayList<String>(3);
                for (Value n : listval.getList())
                {
                    roles.add((String)n.getValue());
                }
                assertTrue(roles.contains("fred"));
                assertTrue(roles.contains("bill"));
                assertTrue(roles.contains("dorothy"));
                System.err.print(annotation+": ");
                System.err.println(anv);            
            }

            @Override
            public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                                    List<Value> values)
            {}

            @Override
            public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                                     List<Value> values)
            {
                assertTrue(annotation.endsWith("RolesAllowed"));
                assertEquals("doGet", methodName);
                assertNotNull(values);
                assertEquals(1,values.size());
                Value anv  = values.get(0);
                assertEquals("value", anv.getName());
                assertTrue (anv instanceof ListValue);
                ListValue listval = (ListValue)anv;
                assertEquals(3, listval.size());
                ArrayList<String> roles = new ArrayList<String>(3);
                for (Value n : listval.getList())
                {
                    roles.add((String)n.getValue());
                }
                assertTrue(roles.contains("bob"));
                assertTrue(roles.contains("carol"));
                assertTrue(roles.contains("ted"));
                System.err.print(annotation+": ");
                System.err.println(anv);             
            }
        }
        
      class TransportProtectedHandler implements AnnotationHandler
      {

        public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                                List<Value> values)
        {
           assertNotNull (values);
           assertEquals(1, values.size());
           Value v = values.get(0);
           assertTrue(v instanceof SimpleValue);
           Object o = v.getValue();
           assertTrue (o instanceof Boolean);
           assertFalse((Boolean)o);
           System.err.println(annotation+": "+v);
        }

        public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                                List<Value> values)
        {}

        
        public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                                 List<Value> values)
        {}
          
      }
        parser.registerAnnotationHandler("javax.servlet.annotation.WebServlet", new ServletAnnotationHandler());
        parser.registerAnnotationHandler("javax.servlet.annotation.MultipartConfig", new MultipartAnnotationHandler ());
=======
        
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
>>>>>>> .merge-right.r1059
        parser.registerAnnotationHandler("javax.annotation.Resource", new ResourceAnnotationHandler ());
        parser.registerAnnotationHandler("javax.annotation.PostConstruct", new CallbackAnnotationHandler());
        parser.registerAnnotationHandler("javax.annotation.PreDestroy", new CallbackAnnotationHandler());
        parser.registerAnnotationHandler("javax.annotation.security.RunAs", new RunAsAnnotationHandler());
        parser.registerAnnotationHandler("javax.annotation.security.RolesAllowed", new RolesAllowedHandler());
        parser.registerAnnotationHandler("javax.annotation.security.TransportProtected", new TransportProtectedHandler());

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
