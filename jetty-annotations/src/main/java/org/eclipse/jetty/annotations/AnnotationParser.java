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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import org.eclipse.jetty.util.ConcurrentHashSet;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.MultiException;
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
 * <p>
 * Use asm to scan classes for annotations. A SAX-style parsing is done.
 * Handlers are registered which will be called back when various types of
 * entity are encountered, eg a class, a method, a field. 
 * <p> 
 * Handlers are not called back in any particular order and are assumed
 * to be order-independent.
 * <p>
 * As a registered Handler will be called back for each annotation discovered
 * on a class, a method, a field, the Handler should test to see if the annotation
 * is one that it is interested in.
 * <p>
 * For the servlet spec, we are only interested in annotations on classes, methods and fields,
 * so the callbacks for handling finding a class, a method a field are themselves
 * not fully implemented.
 */
public class AnnotationParser
{
    private static final Logger LOG = Log.getLogger(AnnotationParser.class);

    protected Set<String> _parsedClassNames = new ConcurrentHashSet<String>();
    
    protected static int ASM_OPCODE_VERSION = Opcodes.ASM5; //compatibility of api
   

    /**
     * Convert internal name to simple name
     * 
     * @param name the internal name
     * @return the simple name
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
     * @param list the list of internal names
     * @return the list of simple names
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
        final Resource _containingResource;
        final String _className;
        final int _version;
        final int _access;
        final String _signature;
        final String _superName; 
        final String[] _interfaces;
        
        public ClassInfo(Resource resource, String className, int version, int access, String signature, String superName, String[] interfaces)
        {
            super();
            _containingResource = resource;
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

        public Resource getContainingResource()
        {
            return _containingResource;
        }
    }

    
    /**
     * MethodInfo
     * 
     * Immutable information gathered by parsing a method on a class.
     */
    public class MethodInfo
    {
        final ClassInfo _classInfo;
        final String _methodName; 
        final int _access;
        final String _desc; 
        final String _signature;
        final String[] _exceptions;
        
        public MethodInfo(ClassInfo classInfo, String methodName, int access, String desc, String signature, String[] exceptions)
        {
            super();
            _classInfo = classInfo;
            _methodName = methodName;
            _access = access;
            _desc = desc;
            _signature = signature;
            _exceptions = exceptions;
        }

        public ClassInfo getClassInfo()
        {
            return _classInfo;
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
        final ClassInfo _classInfo;
        final String _fieldName;
        final int _access;
        final String _fieldType;
        final String _signature;
        final Object _value;
        
        public FieldInfo(ClassInfo classInfo, String fieldName, int access, String fieldType, String signature, Object value)
        {
            super();
            _classInfo = classInfo;
            _fieldName = fieldName;
            _access = access;
            _fieldType = fieldType;
            _signature = signature;
            _value = value;
        }

        public ClassInfo getClassInfo()
        {
            return _classInfo;
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
        final Set<? extends Handler> _handlers;
        
        public MyMethodVisitor(final Set<? extends Handler> handlers,
                               final ClassInfo classInfo,
                               final int access,
                               final String name,
                               final String methodDesc,
                               final String signature,
                               final String[] exceptions)
        {
            super(ASM_OPCODE_VERSION);
            _handlers = handlers;
            _mi = new MethodInfo(classInfo, name, access, methodDesc,signature, exceptions);
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
        final Set<? extends Handler> _handlers;
        
    
        public MyFieldVisitor(final Set<? extends Handler> handlers,
                              final ClassInfo classInfo,
                              final int access,
                              final String fieldName,
                              final String fieldType,
                              final String signature,
                              final Object value)
        {
            super(ASM_OPCODE_VERSION);
            _handlers = handlers;
            _fieldInfo = new FieldInfo(classInfo, fieldName, access, fieldType, signature, value);
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

        final Resource _containingResource;
        final Set<? extends Handler> _handlers;
        ClassInfo _ci;
        
        public MyClassVisitor(Set<? extends Handler> handlers, Resource containingResource)
        {
            super(ASM_OPCODE_VERSION);
            _handlers = handlers;
            _containingResource = containingResource;
        }


        @Override
        public void visit (final int version,
                           final int access,
                           final String name,
                           final String signature,
                           final String superName,
                           final String[] interfaces)
        {           
            _ci = new ClassInfo(_containingResource, normalize(name), version, access, signature, normalize(superName), normalize(interfaces));
            
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

            return new MyMethodVisitor(_handlers, _ci, access, name, methodDesc, signature, exceptions);
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
            return new MyFieldVisitor(_handlers, _ci, access, fieldName, fieldType, signature, value);
        }
    }

 

    /**
     * True if the class has already been processed, false otherwise
     * @param className the classname
     * @return true if class was parsed, false if not
     */
    public boolean isParsed (String className)
    {
        return _parsedClassNames.contains(className);
    }

    
    
    /**
     * Parse a given class
     * 
     * @param handlers the set of handlers to find class
     * @param className the class name to parse
     * @throws Exception if unable to parse
     */
    public void parse (Set<? extends Handler> handlers, String className)
    throws Exception
    {
        if (className == null)
            return;

        if (!isParsed(className))
        {
            className = className.replace('.', '/')+".class";
            URL resource = Loader.getResource(className);
            if (resource!= null)
            {
                Resource r = Resource.newResource(resource);
                try (InputStream is = r.getInputStream())
                {
                    scanClass(handlers, null, is);
                }
            }
        }
    }

    
    
    /**
     * Parse the given class, optionally walking its inheritance hierarchy
     * 
     * @param handlers the handlers to look for class in 
     * @param clazz the class to look for
     * @param visitSuperClasses if true, also visit super classes for parse 
     * @throws Exception if unable to parse class
     */
    public void parse (Set<? extends Handler> handlers, Class<?> clazz, boolean visitSuperClasses)
    throws Exception
    {
        Class<?> cz = clazz;
        while (cz != null)
        {
            if (!isParsed(cz.getName()))
            {
                String nameAsResource = cz.getName().replace('.', '/')+".class";
                URL resource = Loader.getResource(nameAsResource);
                if (resource!= null)
                {
                    Resource r = Resource.newResource(resource);
                    try (InputStream is =  r.getInputStream())
                    {
                        scanClass(handlers, null, is);
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
     * @param handlers the set of handlers to look for class in 
     * @param classNames the class name
     * @throws Exception if unable to parse
     */
    public void parse (Set<? extends Handler> handlers, String[] classNames)
    throws Exception
    {
        if (classNames == null)
            return;

        parse(handlers, Arrays.asList(classNames));
    }

    
    /**
     * Parse the given classes
     * 
     * @param handlers the set of handlers to look for class in 
     * @param classNames the class names
     * @throws Exception if unable to parse
     */
    public void parse (Set<? extends Handler> handlers, List<String> classNames)
    throws Exception
    {
        MultiException me = new MultiException();
        
        for (String s:classNames)
        {
            try
            {
                if (!isParsed(s))
                {
                    s = s.replace('.', '/')+".class";
                    URL resource = Loader.getResource(s);
                    if (resource!= null)
                    {
                        Resource r = Resource.newResource(resource);
                        try (InputStream is = r.getInputStream())
                        {
                            scanClass(handlers, null, is);
                        }
                    }
                }
            }
            catch (Exception e)
            {
                me.add(new RuntimeException("Error scanning class "+s, e));
            }
        }
        me.ifExceptionThrow();
    }

    
    /**
     * Parse all classes in a directory
     * 
     * @param handlers the set of handlers to look for classes in 
     * @param dir the resource directory to look for classes
     * @throws Exception if unable to parse
     */
    protected void parseDir (Set<? extends Handler> handlers, Resource dir)
    throws Exception
    {
        // skip dirs whose name start with . (ie hidden)
        if (!dir.isDirectory() || !dir.exists() || dir.getName().startsWith("."))
            return;

        if (LOG.isDebugEnabled()) {LOG.debug("Scanning dir {}", dir);};

        MultiException me = new MultiException();
        
        String[] files=dir.list();
        for (int f=0;files!=null && f<files.length;f++)
        {
            Resource res = dir.addPath(files[f]);
            if (res.isDirectory())
                parseDir(handlers, res);
            else
            {
                //we've already verified the directories, so just verify the class file name
                File file = res.getFile();
                if (isValidClassFileName((file==null?null:file.getName())))
                {
                    try
                    {
                        String name = res.getName();
                        if (!isParsed(name))
                        {
                            Resource r = Resource.newResource(res.getURL());
                            if (LOG.isDebugEnabled()) {LOG.debug("Scanning class {}", r);};
                            try (InputStream is=r.getInputStream())
                            {
                                scanClass(handlers, dir, is);
                            }
                        }
                    }                  
                    catch (Exception ex)
                    {
                        if (LOG.isDebugEnabled()) LOG.debug("Error scanning file "+files[f], ex);
                        me.add(new RuntimeException("Error scanning file "+files[f],ex));
                    }
                }
                else
                {
                   if (LOG.isDebugEnabled()) LOG.debug("Skipping scan on invalid file {}", res);
                }
            }
        }

        me.ifExceptionThrow();
    }


    /**
     * Parse classes in the supplied classloader. 
     * Only class files in jar files will be scanned.
     * 
     * @param handlers the handlers to look for classes in 
     * @param loader the classloader for the classes
     * @param visitParents if true, visit parent classloaders too
     * @param nullInclusive if true, an empty pattern means all names match, if false, none match
     * @throws Exception if unable to parse
     */
    public void parse (final Set<? extends Handler> handlers, ClassLoader loader, boolean visitParents, boolean nullInclusive)
    throws Exception
    {
        if (loader==null)
            return;

        if (!(loader instanceof URLClassLoader))
            return; //can't extract classes?

        final MultiException me = new MultiException();
        
        JarScanner scanner = new JarScanner()
        {
            @Override
            public void processEntry(URI jarUri, JarEntry entry)
            {
                try
                {
                    parseJarEntry(handlers, Resource.newResource(jarUri), entry);
                }
                catch (Exception e)
                {
                    me.add(new RuntimeException("Error parsing entry "+entry.getName()+" from jar "+ jarUri, e));
                }
            }

        };

        scanner.scan(null, loader, nullInclusive, visitParents);
        me.ifExceptionThrow();
    }


    /**
     * Parse classes in the supplied uris.
     * 
     * @param handlers the handlers to look for classes in  
     * @param uris the uris for the jars
     * @throws Exception if unable to parse
     */
    public void parse (final Set<? extends Handler> handlers, final URI[] uris)
    throws Exception
    {
        if (uris==null)
            return;

        MultiException me = new MultiException();
        
        for (URI uri:uris)
        {
            try
            {
                parse(handlers, uri);
            }
            catch (Exception e)
            {
                me.add(new RuntimeException("Problem parsing classes from "+ uri, e));
            }
        }
        me.ifExceptionThrow();
    }

    /**
     * Parse a particular uri
     * 
     * @param handlers the handlers to look for classes in 
     * @param uri the uri for the jar 
     * @throws Exception if unable to parse
     */
    public void parse (final Set<? extends Handler> handlers, URI uri)
    throws Exception
    {
        if (uri == null)
            return;

        parse (handlers, Resource.newResource(uri));
    }

    
    /**
     * Parse a resource
     * 
     * @param handlers the handlers to look for classes in  
     * @param r the resource to parse
     * @throws Exception if unable to parse
     */
    public void parse (final Set<? extends Handler> handlers, Resource r)
    throws Exception
    {
        if (r == null)
            return;
        
        if (r.exists() && r.isDirectory())
        {
            parseDir(handlers, r);
            return;
        }

        String fullname = r.toString();
        if (fullname.endsWith(".jar"))
        {
            parseJar(handlers, r);
            return;
        }

        if (fullname.endsWith(".class"))
        {
            try (InputStream is=r.getInputStream())
            {
                scanClass(handlers, null, is);
                return;
            }
        }
        
        if (LOG.isDebugEnabled()) LOG.warn("Resource not scannable for classes: {}", r);
    }

    
  

    /**
     * Parse a resource that is a jar file.
     * 
     * @param handlers the handlers to look for classes in  
     * @param jarResource the jar resource to parse
     * @throws Exception if unable to parse
     */
    protected void parseJar (Set<? extends Handler> handlers, Resource jarResource)
    throws Exception
    {
        if (jarResource == null)
            return;
       
        if (jarResource.toString().endsWith(".jar"))
        {
            if (LOG.isDebugEnabled()) {LOG.debug("Scanning jar {}", jarResource);};

            //treat it as a jar that we need to open and scan all entries from  
            InputStream in = jarResource.getInputStream();
            if (in==null)
                return;

            MultiException me = new MultiException();
            JarInputStream jar_in = new JarInputStream(in);
            try
            { 
                JarEntry entry = jar_in.getNextJarEntry();
                while (entry!=null)
                {      
                    try
                    {
                        parseJarEntry(handlers, jarResource, entry);                        
                    }
                    catch (Exception e)
                    {
                        me.add(new RuntimeException("Error scanning entry "+entry.getName()+" from jar "+jarResource, e));
                    }
                    entry = jar_in.getNextJarEntry();
                }
            }
            catch (Exception e)
            {
                me.add(new RuntimeException("Error scanning jar "+jarResource, e));
            }
            finally
            {
                jar_in.close();
            }
            
            me.ifExceptionThrow();
        }        
    }

    /**
     * Parse a single entry in a jar file
     * 
     * @param handlers the handlers to look for classes in  
     * @param jar the jar resource to parse
     * @param entry the entry in the jar resource to parse
     * @throws Exception if unable to parse
     */
    protected void parseJarEntry (Set<? extends Handler> handlers, Resource jar, JarEntry entry)
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

            if (!isParsed(shortName))
            {
                Resource clazz = Resource.newResource("jar:"+jar.getURI()+"!/"+name);
                if (LOG.isDebugEnabled()) {LOG.debug("Scanning class from jar {}", clazz);};
                try (InputStream is = clazz.getInputStream())
                {
                    scanClass(handlers, jar, is);
                }
            }
        }
    }
    
    

    /**
     * Use ASM on a class
     * 
     * @param handlers the handlers to look for classes in  
     * @param containingResource the dir or jar that the class is contained within, can be null if not known
     * @param is the input stream to parse
     * @throws IOException if unable to parse
     */
    protected void scanClass (Set<? extends Handler> handlers, Resource containingResource, InputStream is)
    throws IOException
    {
        ClassReader reader = new ClassReader(is);
        reader.accept(new MyClassVisitor(handlers, containingResource), ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
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

