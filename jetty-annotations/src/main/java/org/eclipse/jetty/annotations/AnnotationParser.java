//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import org.eclipse.jetty.util.ConcurrentHashSet;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.JarScanner;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;


/**
 * AnnotationParser
 *
 * Use asm to scan classes for annotations. A SAX-style parsing is done.
 * Handlers are registered which will be called back when various types of
 * entity are encountered, eg a class, a method, a field. 
 * 
 * Handlers are not called back in any particular order and are assumed
 * to be order-independent.
 * 
 * As a registered Handler will be called back for each annotation discovered
 * on a class, a method, a field, the Handler should test to see if the annotation
 * is one that it is interested in.
 * 
 * For the servlet spec, we are only interested in annotations on classes, methods and fields,
 * so the callbacks for handling finding a class, a method a field are themselves
 * not fully implemented.
 */
public class AnnotationParser
{
    private static final Logger LOG = Log.getLogger(AnnotationParser.class);

    protected Set<String> _parsedClassNames = new ConcurrentHashSet<String>();
    protected Set<Handler> _handlers = new ConcurrentHashSet<Handler>();

    /**
     * Convert internal name to simple name
     * 
     * @param name
     * @return
     */
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
    
    /**
     * Convert internal names to simple names.
     * 
     * @param list
     * @return
     */
    public static String[] normalize (String[] list)
    {
        if (list == null)
            return null;       
        String[] normalList = new String[list.length];
        int i=0;
        for (String s : list)
            normalList[i++] = normalize(s);
        return normalList;
    }

    
    /**
     * ClassInfo
     * 
     * Immutable information gathered by parsing class header.
     * 
     */
    public class ClassInfo 
    {
        final String _className;
        final int _version;
        final int _access;
        final String _signature;
        final String _superName; 
        final String[] _interfaces;
        
        public ClassInfo(String className, int version, int access, String signature, String superName, String[] interfaces)
        {
            super();
            _className = className;
            _version = version;
            _access = access;
            _signature = signature;
            _superName = superName;
            _interfaces = interfaces;
        }

        public String getClassName()
        {
            return _className;
        }

        public int getVersion()
        {
            return _version;
        }

        public int getAccess()
        {
            return _access;
        }

        public String getSignature()
        {
            return _signature;
        }

        public String getSuperName()
        {
            return _superName;
        }

        public String[] getInterfaces()
        {
            return _interfaces;
        }
    }

    
    /**
     * MethodInfo
     * 
     * Immutable information gathered by parsing a method on a class.
     */
    public class MethodInfo
    {
        final String _className;
        final String _methodName; 
        final int _access;
        final String _desc; 
        final String _signature;
        final String[] _exceptions;
        
        public MethodInfo(String className, String methodName, int access, String desc, String signature, String[] exceptions)
        {
            super();
            _className = className;
            _methodName = methodName;
            _access = access;
            _desc = desc;
            _signature = signature;
            _exceptions = exceptions;
        }

        public String getClassName()
        {
            return _className;
        }

        public String getMethodName()
        {
            return _methodName;
        }

        public int getAccess()
        {
            return _access;
        }

        public String getDesc()
        {
            return _desc;
        }

        public String getSignature()
        {
            return _signature;
        }

        public String[] getExceptions()
        {
            return _exceptions;
        } 
    }
    
    
    
    /**
     * FieldInfo
     *
     * Immutable information gathered by parsing a field on a class.
     * 
     */
    public class FieldInfo
    {
        final String _className;
        final String _fieldName;
        final int _access;
        final String _fieldType;
        final String _signature;
        final Object _value;
        
        public FieldInfo(String className, String fieldName, int access, String fieldType, String signature, Object value)
        {
            super();
            _className = className;
            _fieldName = fieldName;
            _access = access;
            _fieldType = fieldType;
            _signature = signature;
            _value = value;
        }

        public String getClassName()
        {
            return _className;
        }

        public String getFieldName()
        {
            return _fieldName;
        }

        public int getAccess()
        {
            return _access;
        }

        public String getFieldType()
        {
            return _fieldType;
        }

        public String getSignature()
        {
            return _signature;
        }

        public Object getValue()
        {
            return _value;
        }
    }
    
    
    /**
     * Handler
     *
     * Signature for all handlers that respond to parsing class files.
     */
    public static interface Handler
    {
        public void handle(ClassInfo classInfo);
        public void handle(MethodInfo methodInfo);
        public void handle (FieldInfo fieldInfo);
        public void handle (ClassInfo info, String annotationName);
        public void handle (MethodInfo info, String annotationName);
        public void handle (FieldInfo info, String annotationName);
    }
    
    
    
    /**
     * AbstractHandler
     *
     * Convenience base class to provide no-ops for all Handler methods.
     * 
     */
    public static abstract class AbstractHandler implements Handler
    {

        @Override
        public void handle(ClassInfo classInfo)
        {
           //no-op
        }

        @Override
        public void handle(MethodInfo methodInfo)
        {
            // no-op           
        }

        @Override
        public void handle(FieldInfo fieldInfo)
        {
            // no-op 
        }

        @Override
        public void handle(ClassInfo info, String annotationName)
        {
            // no-op 
        }

        @Override
        public void handle(MethodInfo info, String annotationName)
        {
            // no-op            
        }

        @Override
        public void handle(FieldInfo info, String annotationName)
        {
           // no-op
        }        
    }

    
    
    /**
     * MyMethodVisitor
     * 
     * ASM Visitor for parsing a method. We are only interested in the annotations on methods.
     */
    public class MyMethodVisitor extends MethodVisitor
    {
        final MethodInfo _mi;
            
        /**
         * @param classname
         * @param access
         * @param name
         * @param methodDesc
         * @param signature
         * @param exceptions
         */
        public MyMethodVisitor(final String className,
                               final int access,
                               final String name,
                               final String methodDesc,
                               final String signature,
                               final String[] exceptions)
        {
            super(Opcodes.ASM4);
            _mi = new MethodInfo(className, name, access, methodDesc,signature, exceptions);
        }

        
        /**
         * We are only interested in finding the annotations on methods.
         * 
         * @see org.objectweb.asm.MethodVisitor#visitAnnotation(java.lang.String, boolean)
         */
        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible)
        {
            String annotationName = normalize(desc);
            for (Handler h:_handlers)
                h.handle(_mi, annotationName);
            return null;
        }
    }


    
    /**
     * MyFieldVisitor
     * 
     * An ASM visitor for parsing Fields. 
     * We are only interested in visiting annotations on Fields.
     *
     */
    public class MyFieldVisitor extends FieldVisitor
    {   
        final FieldInfo _fieldInfo;
        
    
        /**
         * @param classname
         */
        public MyFieldVisitor(final String className, 
                              final int access,
                              final String fieldName,
                              final String fieldType,
                              final String signature,
                              final Object value)
        {
            super(Opcodes.ASM4);
            _fieldInfo = new FieldInfo(className, fieldName, access, fieldType, signature, value);
        }


        /**
         * Parse an annotation found on a Field.
         * 
         * @see org.objectweb.asm.FieldVisitor#visitAnnotation(java.lang.String, boolean)
         */
        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible)
        {
            String annotationName = normalize(desc);
            for (Handler h : _handlers)
               h.handle(_fieldInfo, annotationName);

            return null;
        }
    }

  


    /**
     * MyClassVisitor
     *
     * ASM visitor for a class.
     */
    public class MyClassVisitor extends ClassVisitor
    {

        ClassInfo _ci;
        
        public MyClassVisitor()
        {
            super(Opcodes.ASM4);
        }


        @Override
        public void visit (final int version,
                           final int access,
                           final String name,
                           final String signature,
                           final String superName,
                           final String[] interfaces)
        {           
            _ci = new ClassInfo(normalize(name), version, access, signature, normalize(superName), normalize(interfaces));
            
            _parsedClassNames.add(_ci.getClassName());                 

            for (Handler h:_handlers)
               h.handle(_ci);
        }
        

        /**
         * Visit an annotation on a Class
         * 
         * @see org.objectweb.asm.ClassVisitor#visitAnnotation(java.lang.String, boolean)
         */
        @Override
        public AnnotationVisitor visitAnnotation (String desc, boolean visible)
        {
            String annotationName = normalize(desc);
            for (Handler h : _handlers)
                h.handle(_ci, annotationName);

            return null;
        }


        /**
         * Visit a method to extract its annotations
         * 
         * @see org.objectweb.asm.ClassVisitor#visitMethod(int, java.lang.String, java.lang.String, java.lang.String, java.lang.String[])
         */
        @Override
        public MethodVisitor visitMethod (final int access,
                                          final String name,
                                          final String methodDesc,
                                          final String signature,
                                          final String[] exceptions)
        {

            return new MyMethodVisitor(_ci.getClassName(), access, name, methodDesc, signature, exceptions);
        }

        /**
         * Visit a field to extract its annotations
         * 
         * @see org.objectweb.asm.ClassVisitor#visitField(int, java.lang.String, java.lang.String, java.lang.String, java.lang.Object)
         */
        @Override
        public FieldVisitor visitField (final int access,
                                        final String fieldName,
                                        final String fieldType,
                                        final String signature,
                                        final Object value)
        {
            return new MyFieldVisitor(_ci.getClassName(), access, fieldName, fieldType, signature, value);
        }
    }

 
    /**
     * Add a particular handler
     * 
     * @param h
     */
    public void registerHandler(Handler h)
    {
        if (h == null)
            return;
        
        _handlers.add(h);
    }
    
    
    /**
     * Add a list of handlers
     * 
     * @param handlers
     */
    public void registerHandlers(List<? extends Handler> handlers)
    {
        if (handlers == null)
            return;
        _handlers.addAll(handlers);
    }
    
    
    /**
     * Remove a particular handler
     * 
     * @param h
     */
    public boolean deregisterHandler(Handler h)
    {
        return _handlers.remove(h);
    }
    
    
    /**
     * Remove all registered handlers
     */
    public void clearHandlers()
    {
        _handlers.clear();
    }
    

    /**
     * True if the class has already been processed, false otherwise
     * @param className
     */
    public boolean isParsed (String className)
    {
        return _parsedClassNames.contains(className);
    }

    
    
    /**
     * Parse a given class
     * 
     * @param className
     * @param resolver
     * @throws Exception
     */
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
                URL resource = Loader.getResource(this.getClass(), className);
                if (resource!= null)
                {
                    Resource r = Resource.newResource(resource);
                    scanClass(r.getInputStream());
                }
            }
        }
    }

    
    
    /**
     * Parse the given class, optionally walking its inheritance hierarchy
     * 
     * @param clazz
     * @param resolver
     * @param visitSuperClasses
     * @throws Exception
     */
    public void parse (Class<?> clazz, ClassNameResolver resolver, boolean visitSuperClasses)
    throws Exception
    {
        Class<?> cz = clazz;
        while (cz != null)
        {
            if (!resolver.isExcluded(cz.getName()))
            {
                if (!isParsed(cz.getName()) || resolver.shouldOverride(cz.getName()))
                {
                    String nameAsResource = cz.getName().replace('.', '/')+".class";
                    URL resource = Loader.getResource(this.getClass(), nameAsResource);
                    if (resource!= null)
                    {
                        Resource r = Resource.newResource(resource);
                        scanClass(r.getInputStream());
                    }
                }
            }
            if (visitSuperClasses)
                cz = cz.getSuperclass();
            else
                cz = null;
        }
    }

    
    
    /**
     * Parse the given classes
     * 
     * @param classNames
     * @param resolver
     * @throws Exception
     */
    public void parse (String[] classNames, ClassNameResolver resolver)
    throws Exception
    {
        if (classNames == null)
            return;

        parse(Arrays.asList(classNames), resolver);
    }

    
    /**
     * Parse the given classes
     * 
     * @param classNames
     * @param resolver
     * @throws Exception
     */
    public void parse (List<String> classNames, ClassNameResolver resolver)
    throws Exception
    {
        for (String s:classNames)
        {
            if ((resolver == null) || (!resolver.isExcluded(s) &&  (!isParsed(s) || resolver.shouldOverride(s))))
            {
                s = s.replace('.', '/')+".class";
                URL resource = Loader.getResource(this.getClass(), s);
                if (resource!= null)
                {
                    Resource r = Resource.newResource(resource);
                    scanClass(r.getInputStream());
                }
            }
        }
    }

    
    /**
     * Parse all classes in a directory
     * 
     * @param dir
     * @param resolver
     * @throws Exception
     */
    public void parseDir (Resource dir, ClassNameResolver resolver)
    throws Exception
    {
        //skip dirs whose name start with . (ie hidden)
        if (!dir.isDirectory() || !dir.exists() || dir.getName().startsWith("."))
            return;

        if (LOG.isDebugEnabled()) {LOG.debug("Scanning dir {}", dir);};

        String[] files=dir.list();
        for (int f=0;files!=null && f<files.length;f++)
        {
            try
            {
                Resource res = dir.addPath(files[f]);
                if (res.isDirectory())
                    parseDir(res, resolver);
                else
                {
                    //we've already verified the directories, so just verify the class file name
                    String filename = res.getFile().getName();
                    if (isValidClassFileName(filename))
                    {
                        String name = res.getName();
                        if ((resolver == null)|| (!resolver.isExcluded(name) && (!isParsed(name) || resolver.shouldOverride(name))))
                        {
                            Resource r = Resource.newResource(res.getURL());
                            if (LOG.isDebugEnabled()) {LOG.debug("Scanning class {}", r);};
                            scanClass(r.getInputStream());
                        }

                    }
                }
            }
            catch (Exception ex)
            {
                LOG.warn(Log.EXCEPTION,ex);
            }
        }
    }


    /**
     * Parse classes in the supplied classloader. 
     * Only class files in jar files will be scanned.
     * 
     * @param loader
     * @param visitParents
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
            @Override
            public void processEntry(URI jarUri, JarEntry entry)
            {
                try
                {
                    parseJarEntry(jarUri, entry, resolver);
                }
                catch (Exception e)
                {
                    LOG.warn("Problem parsing jar entry: {}", entry.getName());
                }
            }

        };

        scanner.scan(null, loader, nullInclusive, visitParents);
    }


    /**
     * Parse classes in the supplied uris.
     * 
     * @param uris
     * @param resolver
     * @throws Exception
     */
    public void parse (URI[] uris, final ClassNameResolver resolver)
    throws Exception
    {
        if (uris==null)
            return;

        for (URI uri:uris)
        {
            try
            {
                parse(uri, resolver);
            }
            catch (Exception e)
            {
                LOG.warn("Problem parsing classes from {}", uri);
            }
        }

    }

    /**
     * Parse a particular uri
     * @param uri
     * @param resolver
     * @throws Exception
     */
    public void parse (URI uri, final ClassNameResolver resolver)
    throws Exception
    {
        if (uri == null)
            return;

        parse (Resource.newResource(uri), resolver);
        
     
    }

    
    /**
     * Parse a resource
     * @param r
     * @param resolver
     * @throws Exception
     */
    public void parse (Resource r, final ClassNameResolver resolver)
    throws Exception
    {
        if (r == null)
            return;
        
        if (r.exists() && r.isDirectory())
        {
            parseDir(r, resolver);
            return;
        }

        String fullname = r.toString();
        if (fullname.endsWith(".jar"))
        {
            parseJar(r, resolver);
            return;
        }

        if (fullname.endsWith(".class"))
        {
            scanClass(r.getInputStream());
            return;
        }
        
        if (LOG.isDebugEnabled()) LOG.warn("Resource not scannable for classes: {}", r);
    }


    /**
     * Parse a resource that is a jar file.
     * 
     * @param jarResource
     * @param resolver
     * @throws Exception
     */
    public void parseJar (Resource jarResource,  final ClassNameResolver resolver)
    throws Exception
    {
        if (jarResource == null)
            return;
        
        URI uri = jarResource.getURI();
        if (jarResource.toString().endsWith(".jar"))
        {
            if (LOG.isDebugEnabled()) {LOG.debug("Scanning jar {}", jarResource);};
            
            //treat it as a jar that we need to open and scan all entries from             
            InputStream in = jarResource.getInputStream();
            if (in==null)
                return;

            JarInputStream jar_in = new JarInputStream(in);
            try
            { 
                JarEntry entry = jar_in.getNextJarEntry();
                while (entry!=null)
                {      
                    parseJarEntry(uri, entry, resolver);
                    entry = jar_in.getNextJarEntry();
                }
            }
            finally
            {
                jar_in.close();
            } 
        }   
    }

    /**
     * Parse a single entry in a jar file
     * @param jar
     * @param entry
     * @param resolver
     * @throws Exception
     */
    protected void parseJarEntry (URI jar, JarEntry entry, final ClassNameResolver resolver)
    throws Exception
    {
        if (jar == null || entry == null)
            return;

        //skip directories
        if (entry.isDirectory())
            return;

        String name = entry.getName();

        //check file is a valid class file name
        if (isValidClassFileName(name) && isValidClassFilePath(name))
        {
            String shortName =  name.replace('/', '.').substring(0,name.length()-6);

            if ((resolver == null)
                    ||
                (!resolver.isExcluded(shortName) && (!isParsed(shortName) || resolver.shouldOverride(shortName))))
            {
                Resource clazz = Resource.newResource("jar:"+jar+"!/"+name);
                if (LOG.isDebugEnabled()) {LOG.debug("Scanning class from jar {}", clazz);};
                scanClass(clazz.getInputStream());
            }
        }
    }
    
    

    /**
     * Use ASM on a class
     * 
     * @param is
     * @throws IOException
     */
    protected void scanClass (InputStream is)
    throws IOException
    {
        ClassReader reader = new ClassReader(is);
        reader.accept(new MyClassVisitor(), ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
    }
    
    /**
     * Check that the given path represents a valid class file name.
     * The check is fairly cursory, checking that:
     * <ul>
     * <li> the name ends with .class</li>
     * <li> it isn't a dot file or in a hidden directory </li>
     * <li> the name of the class at least begins with a valid identifier for a class name </li>
     * </ul>
     * @param name
     * @return
     */
    private boolean isValidClassFileName (String name)
    {
        //no name cannot be valid
        if (name == null || name.length()==0)
            return false;

        //skip anything that is not a class file
        if (!name.toLowerCase(Locale.ENGLISH).endsWith(".class"))
        {
            if (LOG.isDebugEnabled()) LOG.debug("Not a class: {}",name);
            return false;
        }

        //skip any classfiles that are not a valid java identifier
        int c0 = 0;      
        int ldir = name.lastIndexOf('/', name.length()-6);
        c0 = (ldir > -1 ? ldir+1 : c0);
        if (!Character.isJavaIdentifierStart(name.charAt(c0)))
        {
            if (LOG.isDebugEnabled()) LOG.debug("Not a java identifier: {}"+name);
            return false;
        }
   
        return true;
    }
    
    
    /**
     * Check that the given path does not contain hidden directories
     *
     * @param path
     * @return
     */
    private boolean isValidClassFilePath (String path)
    {
        //no path is not valid
        if (path == null || path.length()==0)
            return false;

        //skip any classfiles that are in a hidden directory
        if (path.startsWith(".") || path.contains("/."))
        {
            if (LOG.isDebugEnabled()) LOG.debug("Contains hidden dirs: {}"+path);
            return false;
        }

        return true;
    }
}

