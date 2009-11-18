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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.JarScanner;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.EmptyVisitor;

/**
 * AnnotationParser
 * 
 * Use asm to scan classes for annotations. A SAX-style parsing is done, with
 * a handler being able to be registered to handle each annotation type.
 */
public class AnnotationParser
{ 
    protected List<String> _parsedClassNames = new ArrayList<String>();
    protected Map<String, AnnotationHandler> _annotationHandlers = new HashMap<String, AnnotationHandler>();
    protected List<ClassHandler> _classHandlers = new ArrayList<ClassHandler>();
    protected List<MethodHandler> _methodHandlers = new ArrayList<MethodHandler>();
    protected List<FieldHandler> _fieldHandlers = new ArrayList<FieldHandler>();
    
    public static String normalize (String name)
    {
        if (name==null)
            return null;
        
        if (name.startsWith("L") && name.endsWith(";"))
            name = name.substring(1, name.length()-1);
        
        if (name.endsWith(".class"))
            name = name.substring(0, name.length()-".class".length());
        
        return name.replace('/', '.');
    }
    

    
    public abstract class Value
    {
        String _name;
        
        public Value (String name)
        {
            _name = name;
        }
        
        public String getName()
        {
            return _name;
        }
        
        public abstract Object getValue();
           
    }
    
   
 
    
    public class SimpleValue extends Value
    {
        Object _val;
        
        public SimpleValue(String name)
        {
            super(name);
        }
        
        public void setValue(Object val)
        {
            _val=val;
        } 
        public Object getValue()
        {
            return _val;
        } 
        
        public String toString()
        {
            return "("+getName()+":"+_val+")";
        }
    }
    
    public class ListValue extends Value
    {
        List<Value> _val;
        
        public ListValue (String name)
        {
            super(name);
            _val = new ArrayList<Value>();
        }
      
        public Object getValue()
        {
            return _val;
        }
        
        public List<Value> getList()
        {
            return _val;
        }
        
        public void addValue (Value v)
        {
            _val.add(v);
        }
        
        public int size ()
        {
            return _val.size();
        }
        
        public String toString()
        {
            StringBuffer buff = new StringBuffer();
            buff.append("(");
            buff.append(getName());
            buff.append(":");
            for (Value n: _val)
            {
                buff.append(" "+n.toString());
            }
            buff.append(")");
            
            return buff.toString();
        }
    }
    
    
    
    public interface AnnotationHandler
    {
        public void handleClass (String className, int version, int access, 
                                 String signature, String superName, String[] interfaces, 
                                 String annotation, List<Value>values);
        
        public void handleMethod (String className, String methodName, int access,  
                                  String desc, String signature,String[] exceptions, 
                                  String annotation, List<Value>values);
        
        public void handleField (String className, String fieldName,  int access, 
                                 String fieldType, String signature, Object value, 
                                 String annotation, List<Value>values);
    }
    
    
    public interface ClassHandler
    {
        public void handle (String className, int version, int access, String signature, String superName, String[] interfaces);
    }
    
    public interface MethodHandler
    {
        public void handle (String className, String methodName, int access,  String desc, String signature,String[] exceptions);
    }
    
    public interface FieldHandler
    {
        public void handle (String className, String fieldName, int access, String fieldType, String signature, Object value);
    }
    
    public class MyAnnotationVisitor implements AnnotationVisitor
    {
        List<Value> _annotationValues;
        String _annotationName;
        
        public MyAnnotationVisitor (String annotationName, List<Value> values)
        {
            _annotationValues = values;
            _annotationName = annotationName;
        }
        
        public List<Value> getAnnotationValues()
        {
            return _annotationValues;
        }

        /** 
         * Visit a single-valued (name,value) pair for this annotation
         * @see org.objectweb.asm.AnnotationVisitor#visit(java.lang.String, java.lang.Object)
         */
        public void visit(String aname, Object avalue)
        {
           SimpleValue v = new SimpleValue(aname);
           v.setValue(avalue);
           _annotationValues.add(v);
        }

        /** 
         * Visit a (name,value) pair whose value is another Annotation
         * @see org.objectweb.asm.AnnotationVisitor#visitAnnotation(java.lang.String, java.lang.String)
         */
        public AnnotationVisitor visitAnnotation(String name, String desc)
        {
            String s = normalize(desc);
            ListValue v = new ListValue(s);
            _annotationValues.add(v);
            MyAnnotationVisitor visitor = new MyAnnotationVisitor(s, v.getList());
            return visitor; 
        }

        /** 
         * Visit an array valued (name, value) pair for this annotation
         * @see org.objectweb.asm.AnnotationVisitor#visitArray(java.lang.String)
         */
        public AnnotationVisitor visitArray(String name)
        {
            ListValue v = new ListValue(name);
            _annotationValues.add(v);
            MyAnnotationVisitor visitor = new MyAnnotationVisitor(null, v.getList());
            return visitor; 
        }

        /** 
         * Visit a enum-valued (name,value) pair for this annotation
         * @see org.objectweb.asm.AnnotationVisitor#visitEnum(java.lang.String, java.lang.String, java.lang.String)
         */
        public void visitEnum(String name, String desc, String value)
        {
            //TODO
        }
        
        public void visitEnd()
        {   
        }
    }
    
    

    
    /**
     * MyClassVisitor
     *
     * ASM visitor for a class.
     */
    public class MyClassVisitor extends EmptyVisitor
    {
        String _className;
        int _access;
        String _signature;
        String _superName;
        String[] _interfaces;
        int _version;


        public void visit (int version,
                           final int access,
                           final String name,
                           final String signature,
                           final String superName,
                           final String[] interfaces)
        {     
            _className = normalize(name);
            _access = access;
            _signature = signature;
            _superName = superName;
            _interfaces = interfaces;
            _version = version;
            
            _parsedClassNames.add(_className);
            //call all registered ClassHandlers
            String[] normalizedInterfaces = null;
            if (interfaces!= null)
            {
                normalizedInterfaces = new String[interfaces.length];
                int i=0;
                for (String s : interfaces)
                    normalizedInterfaces[i++] = normalize(s);
            }
            
            for (ClassHandler h : AnnotationParser.this._classHandlers)
            {
                h.handle(_className, _version, _access, _signature, normalize(_superName), normalizedInterfaces);
            }
        }

        public AnnotationVisitor visitAnnotation (String desc, boolean visible)
        {                
            MyAnnotationVisitor visitor = new MyAnnotationVisitor(normalize(desc), new ArrayList<Value>())
            {
                public void visitEnd()
                {   
                    super.visitEnd();
                    
                    //call all AnnotationHandlers with classname, annotation name + values
                    AnnotationHandler handler = AnnotationParser.this._annotationHandlers.get(_annotationName);
                    if (handler != null)
                    {
                        handler.handleClass(_className, _version, _access, _signature, _superName, _interfaces, _annotationName, _annotationValues);
                    }
                }
            };
            
            return visitor;
        }

        public MethodVisitor visitMethod (final int access,
                                          final String name,
                                          final String methodDesc,
                                          final String signature,
                                          final String[] exceptions)
        {   

            return new EmptyVisitor ()
            {
                public AnnotationVisitor visitAnnotation(String desc, boolean visible)
                {
                    MyAnnotationVisitor visitor = new MyAnnotationVisitor (normalize(desc), new ArrayList<Value>())
                    {
                        public void visitEnd()
                        {   
                            super.visitEnd();
                            //call all AnnotationHandlers with classname, method, annotation name + values
                            AnnotationHandler handler = AnnotationParser.this._annotationHandlers.get(_annotationName);
                            if (handler != null)
                            {
                                handler.handleMethod(_className, name, access, methodDesc, signature, exceptions, _annotationName, _annotationValues);
                            }
                        }
                    };
                   
                    return visitor;
                }
            };
        }

        public FieldVisitor visitField (final int access,
                                        final String fieldName,
                                        final String fieldType,
                                        final String signature,
                                        final Object value)
        {

            return new EmptyVisitor ()
            {
                public AnnotationVisitor visitAnnotation(String desc, boolean visible)
                {
                    MyAnnotationVisitor visitor = new MyAnnotationVisitor(normalize(desc), new ArrayList<Value>())
                    {
                        public void visitEnd()
                        {
                            super.visitEnd();
                            AnnotationHandler handler = AnnotationParser.this._annotationHandlers.get(_annotationName);
                            if (handler != null)
                            {
                                handler.handleField(_className, fieldName, access, fieldType, signature, value, _annotationName, _annotationValues);
                            }
                        }
                    };
                    return visitor;
                }
            };
        }
    }
    
    
    public void registerAnnotationHandler (String annotationName, AnnotationHandler handler)
    {
        _annotationHandlers.put(annotationName, handler);
    }
    
    public void registerClassHandler (ClassHandler handler)
    {
        _classHandlers.add(handler);
    }

    public boolean isParsed (String className)
    {
        return _parsedClassNames.contains(className);
    }
    
    public void parse (String className, ClassNameResolver resolver) 
    throws Exception
    {
        if (className == null)
            return;
        
        if (!resolver.isExcluded(className))
        {
            if (!isParsed(className) || resolver.shouldOverride(className))
            {
                className = className.replace('.', '/')+".class";
                URL resource = Loader.getResource(this.getClass(), className, false);
                if (resource!= null)
                    scanClass(resource.openStream());
            }
        }
    }
    
    public void parse (Class clazz, ClassNameResolver resolver, boolean visitSuperClasses)
    throws Exception
    {
        Class cz = clazz;
        while (cz != null)
        {
            if (!resolver.isExcluded(cz.getName()))
            {
                if (!isParsed(cz.getName()) || resolver.shouldOverride(cz.getName()))
                {
                    String nameAsResource = cz.getName().replace('.', '/')+".class";
                    URL resource = Loader.getResource(this.getClass(), nameAsResource, false);
                    if (resource!= null)
                        scanClass(resource.openStream());
                }
            }
            if (visitSuperClasses)
                cz = cz.getSuperclass();
            else
                cz = null;
        }
    }
    
    public void parse (String[] classNames, ClassNameResolver resolver)
    throws Exception
    {
        if (classNames == null)
            return;
       
        parse(Arrays.asList(classNames), resolver); 
    }
    
    public void parse (List<String> classNames, ClassNameResolver resolver)
    throws Exception
    {
        for (String s:classNames)
        {
            if ((resolver == null) || (!resolver.isExcluded(s) &&  (!isParsed(s) || resolver.shouldOverride(s))))
            {            
                s = s.replace('.', '/')+".class"; 
                URL resource = Loader.getResource(this.getClass(), s, false);
                if (resource!= null)
                    scanClass(resource.openStream());
            }
        }
    }
    
    public void parse (Resource dir, ClassNameResolver resolver)
    throws Exception
    {
        if (!dir.isDirectory() || !dir.exists())
            return;
        
        
        String[] files=dir.list();
        for (int f=0;files!=null && f<files.length;f++)
        {
            try 
            {
                Resource res = dir.addPath(files[f]);
                if (res.isDirectory())
                    parse(res, resolver);
                String name = res.getName();
                if (name.endsWith(".class"))
                {
                    if ((resolver == null)|| (!resolver.isExcluded(name) && (!isParsed(name) || resolver.shouldOverride(name))))
                        scanClass(res.getURL().openStream());

                }
            }
            catch (Exception ex)
            {
                Log.warn(Log.EXCEPTION,ex);
            }
        }
    }
    
    
    /**
     * Find annotations on classes in the supplied classloader. 
     * Only class files in jar files will be scanned.
     * @param loader
     * @param visitParents
     * @param jarNamePattern
     * @param nullInclusive
     * @param resolver
     * @throws Exception
     */
    public void parse (ClassLoader loader, boolean visitParents, boolean nullInclusive, final ClassNameResolver resolver)
    throws Exception
    {
        if (loader==null)
            return;
        
        if (!(loader instanceof URLClassLoader))
            return; //can't extract classes?
       
        JarScanner scanner = new JarScanner()
        {
            public void processEntry(URI jarUri, JarEntry entry)
            {   
                try
                {
                    String name = entry.getName();
                    if (name.toLowerCase().endsWith(".class"))
                    {
                        String shortName =  name.replace('/', '.').substring(0,name.length()-6);
                        if ((resolver == null)
                             ||
                            (!resolver.isExcluded(shortName) && (!isParsed(shortName) || resolver.shouldOverride(shortName))))
                        {

                            Resource clazz = Resource.newResource("jar:"+jarUri+"!/"+name);                     
                            scanClass(clazz.getInputStream());
                        }
                    }
                }
                catch (Exception e)
                {
                    Log.warn("Problem processing jar entry "+entry, e);
                }
            }
            
        };

        scanner.scan(null, loader, nullInclusive, visitParents);
    }
    
    
    /**
     * Find annotations in classes in the supplied url of jar files.
     * @param uris
     * @param resolver
     * @throws Exception
     */
    public void parse (URI[] uris, final ClassNameResolver resolver)
    throws Exception
    {
        if (uris==null)
            return;
        
        JarScanner scanner = new JarScanner()
        {
            public void processEntry(URI jarUri, JarEntry entry)
            {   
                try
                {
                    String name = entry.getName();
                    if (name.toLowerCase().endsWith(".class"))
                    {
                        String shortName =  name.replace('/', '.').substring(0,name.length()-6);

                        if ((resolver == null)
                             ||
                            (!resolver.isExcluded(shortName) && (!isParsed(shortName) || resolver.shouldOverride(shortName))))
                        {
                            Resource clazz = Resource.newResource("jar:"+jarUri+"!/"+name);                     
                            scanClass(clazz.getInputStream());

                        }
                    }
                }
                catch (Exception e)
                {
                    Log.warn("Problem processing jar entry "+entry, e);
                }
            }
            
        };        
        scanner.scan(null, uris, true);
    }
    

    private void scanClass (InputStream is)
    throws IOException
    {
        ClassReader reader = new ClassReader(is);
        reader.accept(new MyClassVisitor(), ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
    }
}
