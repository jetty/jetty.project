// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
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
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.regex.Pattern;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.JarScanner;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.EmptyVisitor;


/**
 * AnnotationFinder
 *
 *
 * Scans class sources using asm to find annotations.
 *
 *
 */
public class AnnotationFinder
{
    private Map<String,ParsedClass> parsedClasses = new HashMap<String, ParsedClass>();
   
    
    public static String normalize (String name)
    {
        if (name==null)
            return null;
        
        if (name.startsWith("L") && name.endsWith(";"))
            name = name.substring(1, name.length()-1);
        
        if (name.endsWith(".class"))
            name = name.substring(0, name.length()-".class".length());
        
       name = name.replace('$', '.');
        
        return name.replace('/', '.');
    }
    
    public static Class convertType (org.objectweb.asm.Type t)
    throws Exception
    {
        if (t == null)
            return (Class)null;
        
        switch (t.getSort())
        {
            case Type.BOOLEAN:
            {
                return Boolean.TYPE;
            }
            case Type.ARRAY:
            {
                Class clazz = convertType(t.getElementType());
                return Array.newInstance(clazz, 0).getClass();
            }
            case Type.BYTE:
            {
                return Byte.TYPE;
            }
            case Type.CHAR:
            {
                return Character.TYPE;
            }
            case Type.DOUBLE:
            {
                return Double.TYPE;
            }
            case Type.FLOAT:
            {
                return Float.TYPE;
            }
            case Type.INT:
            {
                return Integer.TYPE;
            }
            case Type.LONG:
            {
                return Long.TYPE;
            }
            case Type.OBJECT:
            {
                return (Loader.loadClass(null, t.getClassName()));
            }
            case Type.SHORT:
            {
                return Short.TYPE;
            }
            case Type.VOID:
            {
                return null;
            }
            default:
                return null;
        }
        
    }
    
    public static Class[] convertTypes (Type[] types)
    throws Exception
    {
        if (types==null)
            return new Class[0];
        
        Class[] classArray = new Class[types.length];
        
        for (int i=0; i<types.length; i++)
        {
            classArray[i] = convertType(types[i]);
        }
        return classArray;
    }
  
    
    /**
     * AnnotatedStructure
     *
     * Annotations on an object such as a class, field or method.
     */
    public static class AnnotatedStructure  extends EmptyVisitor
    {
        Map<String, Map<String, Object>> annotations = new HashMap<String, Map<String,Object>>();
        
        
        public AnnotationVisitor addAnnotation (final String name)
        {
            final HashMap<String,Object> annotationValues = new HashMap<String,Object>();
            this.annotations.put(normalize(name), annotationValues);
            return new AnnotationVisitor()
            {
                public void visit(String name, Object value)
                {
                    annotationValues.put(name, value);
                }

                public AnnotationVisitor visitAnnotation(String name, String desc)
                {
                    return null; //ignore nested annotations
                }

                public AnnotationVisitor visitArray(String arg0)
                {
                    return null;//ignore array valued annotations
                }

                public void visitEnd()
                {     
                }

                public void visitEnum(String name, String desc, String value)
                {
                }
            };
        } 
        
        public Map<String, Map<String, Object>> getAnnotations ()
        {
            return annotations;
        }
        
        
        public String toString()
        {
            StringBuffer strbuff = new StringBuffer();
            
            for (Map.Entry<String, Map<String,Object>> e: annotations.entrySet())
            {
                strbuff.append(e.getKey()+"\n");
                for (Map.Entry<String,Object> v: e.getValue().entrySet())
                {
                    strbuff.append("\t"+v.getKey()+"="+v.getValue()+", ");
                }
            }
            return strbuff.toString();
        }
    }
    
    
    /**
     * ParsedClass
     *
     * A class that contains annotations.
     */
    public static class ParsedClass extends AnnotatedStructure
    {
        String className;  
        String superClassName;
        Class clazz;
        List<ParsedMethod> methods = new ArrayList<ParsedMethod>();
        List<ParsedField> fields = new ArrayList<ParsedField>();
        
      
        public ParsedClass (String className, String superClassName)
        {
            this.className = normalize(className);
            this.superClassName = normalize(superClassName);
        }
        
        public String getClassName()
        {
            return this.className;
        }
        
        public String getSuperClassName ()
        {
            return this.superClassName;
        }
        
        public Class toClass ()
        throws ClassNotFoundException
        {
            if (clazz==null)
                clazz = Loader.loadClass(null, className);
            return clazz;
        }
        
        public List<ParsedMethod> getMethods ()
        {
            return methods;
        }
        
        public ParsedMethod getMethod(String name, String paramString)
        {
            Iterator<ParsedMethod> itor = methods.iterator();
            ParsedMethod method = null;
            while (itor.hasNext() && method==null)
            {
                ParsedMethod m = itor.next();
                if (m.matches(name, paramString))
                    method = m;
            }
            
            return method;
        }
        
        public void addMethod (ParsedMethod m)
        {
            if (getMethod(m.methodName, m.paramString)!= null)
                return;
            methods.add(m);
        }
        
        public List<ParsedField> getFields()
        {
            return fields;
        }
        
        public ParsedField getField(String name)
        {
            Iterator<ParsedField> itor = fields.iterator();
            ParsedField field = null;
            while (itor.hasNext() && field==null)
            {
                ParsedField f = itor.next();
                if (f.matches(name))
                    field=f;
            }
            return field;
        }
        
        public void addField (ParsedField f)
        {
            if (getField(f.fieldName) != null)
                return;
            fields.add(f);
        }
        
        public String toString ()
        {
            StringBuffer strbuff = new StringBuffer();
            strbuff.append(this.className+"\n");
            strbuff.append("Class annotations\n"+super.toString());
            strbuff.append("\n");
            strbuff.append("Method annotations\n");
            for (ParsedMethod p:methods)
                strbuff.append(p+"\n");
            strbuff.append("\n");
            strbuff.append("Field annotations\n");
            for (ParsedField f:fields)
                strbuff.append(f+"\n");   
            strbuff.append("\n");
            return strbuff.toString();
        }
    }
    
    
    /**
     * ParsedMethod
     *
     * A class method that can contain annotations.
     */
    public static class ParsedMethod extends AnnotatedStructure
    {
        ParsedClass pclass;
        String methodName;
        String paramString;
        Method method;
        
       
        public ParsedMethod(ParsedClass pclass, String name, String paramString)
        {
            this.pclass=pclass;
            this.methodName=name;
            this.paramString=paramString;
        }
        
        
        
        public AnnotationVisitor visitAnnotation(String desc, boolean visible)
        {
            this.pclass.addMethod(this);
            return addAnnotation(desc);
        }
        
        public Method toMethod ()
        throws Exception
        {
            if (method == null)
            {
                Type[] types = null;
                if (paramString!=null)
                    types = Type.getArgumentTypes(paramString);

                Class[] args = convertTypes(types);       
                method = pclass.toClass().getDeclaredMethod(methodName, args);
            }
            
            return method;
        }
        
        public boolean matches (String name, String paramString)
        {
            if (!methodName.equals(name))
                return false;
            
            if (this.paramString!=null && this.paramString.equals(paramString))
                return true;
            
            return (this.paramString == paramString);
        }
        
        public String toString ()
        {
            return pclass.getClassName()+"."+methodName+"\n\t"+super.toString();
        }
    }
    
    /**
     * ParsedField
     *
     * A class field that can contain annotations. Also implements the 
     * asm visitor for Annotations.
     */
    public static class ParsedField extends AnnotatedStructure
    {
        ParsedClass pclass;
        String fieldName;
        Field field;
      
        public ParsedField (ParsedClass pclass, String name)
        {
            this.pclass=pclass;
            this.fieldName=name;
        }  
        
        public AnnotationVisitor visitAnnotation(String desc, boolean visible)
        { 
            this.pclass.addField(this);
            return addAnnotation(desc);
        }
        
        public Field toField ()
        throws Exception
        {
            if (field==null)
            {
                field=this.pclass.toClass().getDeclaredField(fieldName);
            }
            return field;
        }
        
        
        public boolean matches (String name)
        {
            if (fieldName.equals(name))
                return true;
            
            return false;
        }
        
        public String toString ()
        {
            return pclass.getClassName()+"."+fieldName+"\n\t"+super.toString();
        }
    }
    
    
    
    /**
     * MyClassVisitor
     *
     * ASM visitor for a class.
     */
    public class MyClassVisitor extends EmptyVisitor
    {
        ParsedClass pclass;
      

        public void visit (int version,
                int access,
                String name,
                String signature,
                String superName,
                String[] interfaces)
        {     
            pclass = new ParsedClass(name, superName);
        }

        public AnnotationVisitor visitAnnotation (String desc, boolean visible)
        {  
            if (!parsedClasses.containsKey(pclass.getClassName()))
                parsedClasses.put(pclass.getClassName(), pclass);

            return pclass.addAnnotation(desc);
        }

        public MethodVisitor visitMethod (int access,
                String name,
                String desc,
                String signature,
                String[] exceptions)
        {   
            if (!parsedClasses.values().contains(pclass))
                parsedClasses.put(pclass.getClassName(),pclass);

            ParsedMethod method = pclass.getMethod(name, desc);
            if (method==null)
                method = new ParsedMethod(pclass, name, desc);
            return method;
        }

        public FieldVisitor visitField (int access,
                String name,
                String desc,
                String signature,
                Object value)
        {
            if (!parsedClasses.values().contains(pclass))
                parsedClasses.put(pclass.getClassName(),pclass);
            
            ParsedField field = pclass.getField(name);
            if (field==null)
                field = new ParsedField(pclass, name);
            return field;
        }
    }
    
 
   
    
    
    
    public void find (String className, ClassNameResolver resolver) 
    throws Exception
    {
        if (className == null)
            return;
        
        if (!resolver.isExcluded(className))
        {
            if ((parsedClasses.get(className) == null) || (resolver.shouldOverride(className)))
            {
                parsedClasses.remove(className);
                className = className.replace('.', '/')+".class";
                URL resource = Loader.getResource(this.getClass(), className, false);
                if (resource!= null)
                    scanClass(resource.openStream());
            }
        }
    }
    
    public void find (String[] classNames, ClassNameResolver resolver)
    throws Exception
    {
        if (classNames == null)
            return;
        
        find(Arrays.asList(classNames), resolver); 
    }
    
    public void find (List<String> classNames, ClassNameResolver resolver)
    throws Exception
    {
        for (String s:classNames)
        {
            if (!resolver.isExcluded(s))
            {
                if ((parsedClasses.get(s) == null) || (resolver.shouldOverride(s)))
                {                
                    parsedClasses.remove(s);
                    s = s.replace('.', '/')+".class";
                    URL resource = Loader.getResource(this.getClass(), s, false);
                    if (resource!= null)
                        scanClass(resource.openStream());
                }
            }
        }
    }
    
    public void find (Resource dir, ClassNameResolver resolver)
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
                    find(res, resolver);
                String name = res.getName();
                if (name.endsWith(".class"))
                {
                    if (!resolver.isExcluded(name))
                    {
                        if ((parsedClasses.get(name) == null) || (resolver.shouldOverride(name)))
                        {
                            parsedClasses.remove(name);
                            scanClass(res.getURL().openStream());
                        }
                    }
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
    public void find (ClassLoader loader, boolean visitParents, boolean nullInclusive, final ClassNameResolver resolver)
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
                        if (!resolver.isExcluded(shortName))
                        {
                            if ((parsedClasses.get(shortName) == null) || (resolver.shouldOverride(shortName)))
                            {
                                parsedClasses.remove(shortName);
                                Resource clazz = Resource.newResource("jar:"+jarUri+"!/"+name);                     
                                scanClass(clazz.getInputStream());
                            }
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
    public void find (URI[] uris, final ClassNameResolver resolver)
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
                        if (!resolver.isExcluded(shortName))
                        {
                            if ((parsedClasses.get(shortName) == null) || (resolver.shouldOverride(shortName)))
                            {
                                parsedClasses.remove(shortName);
                                Resource clazz = Resource.newResource("jar:"+jarUri+"!/"+name);                     
                                scanClass(clazz.getInputStream());
                            }
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
    
    
    /** Exclude class by name
     * Instances of {@link AnnotationFinder} can implement this method to exclude
     * classes by name.
     * @param name
     * @return
     */
    protected boolean excludeClass (String name)
    {
        return false;
    }
    


    public List<Class<?>> getClassesForAnnotation(Class<?> annotationClass)
    throws Exception
    {
        List<Class<?>> classes = new ArrayList<Class<?>>();
        for (Map.Entry<String, ParsedClass> e: parsedClasses.entrySet())
        {
            ParsedClass pc = e.getValue();
            Map<String, Map<String,Object>> annotations = pc.getAnnotations();
            for (String key:annotations.keySet())
            {
                if (key.equals(annotationClass.getName()))
                {
                    classes.add(pc.toClass());
                }
            }
        }           
        return classes;

    }



    public List<Method>  getMethodsForAnnotation (Class<?> annotationClass)
    throws Exception
    {

        List<Method> methods = new ArrayList<Method>();

        for (Map.Entry<String, ParsedClass> e: parsedClasses.entrySet())
        {
            ParsedClass pc = e.getValue();

            List<ParsedMethod> pmethods = pc.getMethods();
            for (ParsedMethod p:pmethods)
            {
                for (String key:p.getAnnotations().keySet())
                {
                    if (key.equals(annotationClass.getName()))
                    {
                        methods.add(p.toMethod());
                    }
                }
            }
        }           
        return methods;

    }


    public List<Field> getFieldsForAnnotation (Class<?> annotation)
    throws Exception
    {

        List<Field> fields = new ArrayList<Field>();
        for (Map.Entry<String, ParsedClass> e: parsedClasses.entrySet())
        {
            ParsedClass pc = e.getValue();

            List<ParsedField> pfields = pc.getFields();
            for (ParsedField f:pfields)
            {
                for (String key:f.getAnnotations().keySet())
                {
                    if (key.equals(annotation.getName()))
                    {
                        fields.add(f.toField());
                    }
                }
            }
        }           
        return fields;
    }


    public String toString ()
    {
        StringBuffer strbuff = new StringBuffer();
        for (Map.Entry<String, ParsedClass> e:parsedClasses.entrySet())
        {
            strbuff.append(e.getValue());
            strbuff.append("\n");
        }
        return strbuff.toString();
    }
    

    private void scanClass (InputStream is)
    throws IOException
    {
        ClassReader reader = new ClassReader(is);
        reader.accept(new MyClassVisitor(), ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
    }
}
