//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.annotations;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.util.JavaVersion;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.MultiReleaseJarFile;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOG = LoggerFactory.getLogger(AnnotationParser.class);
    private static final int ASM_VERSION = asmVersion();

    /**
     * Map of classnames scanned and the first location from which scan occurred
     */
    protected Map<String, Resource> _parsedClassNames = new ConcurrentHashMap<>();
    private final int _javaPlatform;
    private final int _asmVersion;

    /**
     * Determine the runtime version of asm.
     *
     * @return the {@link org.objectweb.asm.Opcodes} ASM value matching the runtime version of asm.
     */
    private static int asmVersion()
    {
        // Find the highest available ASM version on the runtime/classpath, because
        // if we run with a lower than available ASM version, against a class with
        // new language features we'll get an UnsupportedOperationException, even if
        // the ASM version supports the new language features.
        // Also, if we run with a higher than available ASM version, we'll get
        // an IllegalArgumentException from org.objectweb.asm.ClassVisitor.
        // So must find exactly the maximum ASM api version available.

        Optional<Integer> asmVersion = Arrays.stream(Opcodes.class.getFields()).sequential()
            .filter((f) -> f.getName().matches("ASM[0-9]+"))
            .map((f) -> f.getName().substring(3))
            .map(Integer::parseInt)
            .max(Integer::compareTo);

        if (!asmVersion.isPresent())
            throw new IllegalStateException("Invalid " + Opcodes.class.getName());

        int asmFieldId = asmVersion.get();
        try
        {
            String fieldName = "ASM" + asmFieldId;
            if (LOG.isDebugEnabled())
                LOG.debug("Using ASM API from {}.{}", Opcodes.class.getName(), fieldName);
            return (int)Opcodes.class.getField(fieldName).get(null);
        }
        catch (Throwable e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Convert internal name to simple name
     *
     * @param name the internal name
     * @return the simple name
     */
    public static String normalize(String name)
    {
        if (name == null)
            return null;

        if (name.startsWith("L") && name.endsWith(";"))
            name = name.substring(1, name.length() - 1);

        if (name.endsWith(".class"))
            name = name.substring(0, name.length() - ".class".length());

        return StringUtil.replace(name, '/', '.');
    }

    /**
     * Convert internal names to simple names.
     *
     * @param list the list of internal names
     * @return the list of simple names
     */
    public static String[] normalize(String[] list)
    {
        if (list == null)
            return null;
        String[] normalList = new String[list.length];
        int i = 0;
        for (String s : list)
        {
            normalList[i++] = normalize(s);
        }
        return normalList;
    }

    /**
     * Immutable information gathered by parsing class header.
     */
    public static class ClassInfo
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
     * Immutable information gathered by parsing a method on a class.
     */
    public static class MethodInfo
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
     * Immutable information gathered by parsing a field on a class.
     */
    public static class FieldInfo
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
     * Signature for all handlers that respond to parsing class files.
     */
    public static interface Handler
    {
        public void handle(ClassInfo classInfo);

        public void handle(MethodInfo methodInfo);

        public void handle(FieldInfo fieldInfo);

        public void handle(ClassInfo info, String annotationName);

        public void handle(MethodInfo info, String annotationName);

        public void handle(FieldInfo info, String annotationName);
    }

    /**
     * Convenience base class to provide no-ops for all Handler methods.
     */
    public abstract static class AbstractHandler implements Handler
    {
        @Override
        public void handle(ClassInfo classInfo)
        {
        }

        @Override
        public void handle(MethodInfo methodInfo)
        {
        }

        @Override
        public void handle(FieldInfo fieldInfo)
        {
        }

        @Override
        public void handle(ClassInfo info, String annotationName)
        {
        }

        @Override
        public void handle(MethodInfo info, String annotationName)
        {
        }

        @Override
        public void handle(FieldInfo info, String annotationName)
        {
        }
    }

    /**
     * ASM Visitor for parsing a method. We are only interested in the annotations on methods.
     */
    public static class MyMethodVisitor extends MethodVisitor
    {
        final MethodInfo _mi;
        final Set<? extends Handler> _handlers;

        public MyMethodVisitor(final Set<? extends Handler> handlers,
                               final ClassInfo classInfo,
                               final int access,
                               final String name,
                               final String methodDesc,
                               final String signature,
                               final String[] exceptions,
                               final int asmVersion)
        {
            super(asmVersion);
            _handlers = handlers;
            _mi = new MethodInfo(classInfo, name, access, methodDesc, signature, exceptions);
        }

        /**
         * We are only interested in finding the annotations on methods.
         */
        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible)
        {
            String annotationName = normalize(desc);
            for (Handler h : _handlers)
            {
                h.handle(_mi, annotationName);
            }
            return null;
        }
    }

    /**
     * An ASM visitor for parsing Fields.
     * We are only interested in visiting annotations on Fields.
     */
    public static class MyFieldVisitor extends FieldVisitor
    {
        final FieldInfo _fieldInfo;
        final Set<? extends Handler> _handlers;

        public MyFieldVisitor(final Set<? extends Handler> handlers,
                              final ClassInfo classInfo,
                              final int access,
                              final String fieldName,
                              final String fieldType,
                              final String signature,
                              final Object value,
                              final int asmVersion)
        {
            super(asmVersion);
            _handlers = handlers;
            _fieldInfo = new FieldInfo(classInfo, fieldName, access, fieldType, signature, value);
        }

        /**
         * Parse an annotation found on a Field.
         */
        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible)
        {
            String annotationName = normalize(desc);
            for (Handler h : _handlers)
            {
                h.handle(_fieldInfo, annotationName);
            }

            return null;
        }
    }

    /**
     * ASM visitor for a class.
     */
    public static class MyClassVisitor extends ClassVisitor
    {
        final int _asmVersion;
        final Resource _containingResource;
        final Set<? extends Handler> _handlers;
        ClassInfo _ci;

        public MyClassVisitor(Set<? extends Handler> handlers, Resource containingResource, int asmVersion)
        {
            super(asmVersion);
            _asmVersion = asmVersion;
            _handlers = handlers;
            _containingResource = containingResource;
        }

        @Override
        public void visit(final int version,
                          final int access,
                          final String name,
                          final String signature,
                          final String superName,
                          final String[] interfaces)
        {
            _ci = new ClassInfo(_containingResource, normalize(name), version, access, signature, normalize(superName), normalize(interfaces));
            for (Handler h : _handlers)
            {
                h.handle(_ci);
            }
        }

        /**
         * Visit an annotation on a Class
         */
        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible)
        {
            String annotationName = normalize(desc);
            for (Handler h : _handlers)
            {
                h.handle(_ci, annotationName);
            }
            return null;
        }

        /**
         * Visit a method to extract its annotations
         */
        @Override
        public MethodVisitor visitMethod(final int access,
                                         final String name,
                                         final String methodDesc,
                                         final String signature,
                                         final String[] exceptions)
        {
            return new MyMethodVisitor(_handlers, _ci, access, name, methodDesc, signature, exceptions, _asmVersion);
        }

        /**
         * Visit a field to extract its annotations
         */
        @Override
        public FieldVisitor visitField(final int access,
                                       final String fieldName,
                                       final String fieldType,
                                       final String signature,
                                       final Object value)
        {
            return new MyFieldVisitor(_handlers, _ci, access, fieldName, fieldType, signature, value, _asmVersion);
        }
    }

    public AnnotationParser()
    {
        this(JavaVersion.VERSION.getPlatform());
    }

    /**
     * @param javaPlatform The target java version or 0 for the current runtime.
     */
    public AnnotationParser(int javaPlatform)
    {
        _asmVersion = ASM_VERSION;
        if (javaPlatform == 0)
            javaPlatform = JavaVersion.VERSION.getPlatform();
        _javaPlatform = javaPlatform;
    }

    public AnnotationParser(int javaPlatform, int asmVersion)
    {
        if (javaPlatform == 0)
            javaPlatform = JavaVersion.VERSION.getPlatform();
        _javaPlatform = javaPlatform;
        if (asmVersion == 0)
            asmVersion = ASM_VERSION;
        _asmVersion = asmVersion;
    }

    /**
     * Add a class as having been parsed.
     *
     * @param classname the name of the class
     * @param location the fully qualified location of the class
     */
    public void addParsedClass(String classname, Resource location)
    {
        Resource existing = _parsedClassNames.putIfAbsent(classname, location);
        if (existing != null)
            LOG.warn("{} scanned from multiple locations: {}, {}", classname, existing, location);
    }

    /**
     * Parse a given class
     *
     * @param handlers the set of handlers to find class
     * @param className the class name to parse
     * @throws Exception if unable to parse
     */
    public void parse(Set<? extends Handler> handlers, String className) throws Exception
    {
        if (className == null)
            return;

        String classRef = TypeUtil.toClassReference(className);
        URL resource = Loader.getResource(classRef);
        if (resource != null)
        {
            Resource r = Resource.newResource(resource);
            addParsedClass(className, r);
            try (InputStream is = r.getInputStream())
            {
                scanClass(handlers, null, is);
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
    public void parse(Set<? extends Handler> handlers, Class<?> clazz, boolean visitSuperClasses) throws Exception
    {
        Class<?> cz = clazz;
        while (cz != Object.class)
        {
            String nameAsResource = TypeUtil.toClassReference(cz);
            URL resource = Loader.getResource(nameAsResource);
            if (resource != null)
            {
                Resource r = Resource.newResource(resource);
                addParsedClass(clazz.getName(), r);
                try (InputStream is = r.getInputStream())
                {
                    scanClass(handlers, null, is);
                }
            }

            if (visitSuperClasses)
                cz = cz.getSuperclass();
            else
                break;
        }
    }

    /**
     * Parse the given classes
     *
     * @param handlers the set of handlers to look for class in
     * @param classNames the class name
     * @throws Exception if unable to parse
     */
    public void parse(Set<? extends Handler> handlers, String[] classNames) throws Exception
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
    public void parse(Set<? extends Handler> handlers, List<String> classNames) throws Exception
    {
        MultiException me = new MultiException();

        for (String className : classNames)
        {
            try
            {
                String classRef = TypeUtil.toClassReference(className);
                URL resource = Loader.getResource(classRef);
                if (resource != null)
                {
                    Resource r = Resource.newResource(resource);
                    addParsedClass(className, r);
                    try (InputStream is = r.getInputStream())
                    {
                        scanClass(handlers, null, is);
                    }
                }
            }
            catch (Exception e)
            {
                me.add(new RuntimeException("Error scanning class " + className, e));
            }
        }
        me.ifExceptionThrow();
    }

    /**
     * Parse classes in the supplied uris.
     *
     * @param handlers the handlers to look for classes in
     * @param uris the uris for the jars
     * @throws Exception if unable to parse
     */
    public void parse(final Set<? extends Handler> handlers, final URI[] uris) throws Exception
    {
        if (uris == null)
            return;

        MultiException me = new MultiException();

        for (URI uri : uris)
        {
            try
            {
                parse(handlers, uri);
            }
            catch (Exception e)
            {
                me.add(new RuntimeException("Problem parsing classes from " + uri, e));
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
    public void parse(final Set<? extends Handler> handlers, URI uri) throws Exception
    {
        if (uri == null)
            return;

        parse(handlers, Resource.newResource(uri));
    }

    /**
     * Parse a resource
     *
     * @param handlers the handlers to look for classes in
     * @param r the resource to parse
     * @throws Exception if unable to parse
     */
    public void parse(final Set<? extends Handler> handlers, Resource r) throws Exception
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
            try (InputStream is = r.getInputStream())
            {
                scanClass(handlers, null, is);
                return;
            }
        }

        if (LOG.isDebugEnabled())
            LOG.warn("Resource not scannable for classes: {}", r);
    }

    /**
     * Parse all classes in a directory
     *
     * @param handlers the set of handlers to look for classes in
     * @param root the resource directory to look for classes
     * @throws Exception if unable to parse
     */
    protected void parseDir(Set<? extends Handler> handlers, Resource root) throws Exception
    {
        if (!root.isDirectory() || !root.exists() || root.getName().startsWith("."))
            return;

        if (LOG.isDebugEnabled())
            LOG.debug("Scanning dir {}", root);

        File rootFile = root.getFile();

        MultiException me = new MultiException();
        Collection<Resource> resources = root.getAllResources();
        if (resources != null)
        {
            for (Resource r : resources)
            {
                if (r.isDirectory())
                    continue;

                File file = r.getFile();
                if (isValidClassFileName((file == null ? null : file.getName())))
                {
                    Path classpath = rootFile.toPath().relativize(file.toPath());
                    String str = classpath.toString();
                    str = str.substring(0, str.lastIndexOf(".class"));
                    str = StringUtil.replace(str, File.separatorChar, '.');

                    try
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Scanning class {}", r);
                        addParsedClass(str, r);
                        try (InputStream is = r.getInputStream())
                        {
                            scanClass(handlers, Resource.newResource(file.getParentFile()), is);
                        }
                    }
                    catch (Exception ex)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Error scanning file {}", file, ex);
                        me.add(new RuntimeException("Error scanning file " + file, ex));
                    }
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Skipping scan on invalid file {}", file);
                }
            }
        }

        me.ifExceptionThrow();
    }

    /**
     * Parse a resource that is a jar file.
     *
     * @param handlers the handlers to look for classes in
     * @param jarResource the jar resource to parse
     * @throws Exception if unable to parse
     */
    protected void parseJar(Set<? extends Handler> handlers, Resource jarResource) throws Exception
    {
        if (jarResource == null)
            return;

        if (jarResource.toString().endsWith(".jar"))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Scanning jar {}", jarResource);

            MultiException me = new MultiException();
            try (MultiReleaseJarFile jarFile = new MultiReleaseJarFile(jarResource.getFile(), _javaPlatform, false))
            {
                jarFile.stream().forEach(e ->
                {
                    try
                    {
                        parseJarEntry(handlers, jarResource, e);
                    }
                    catch (Exception ex)
                    {
                        me.add(new RuntimeException("Error scanning entry " + e.getName() + " from jar " + jarResource, ex));
                    }
                });
            }
            me.ifExceptionThrow();
        }
    }

    /**
     * Parse a single entry in a jar file
     *
     * @param handlers the handlers to look for classes in
     * @param entry the entry in the potentially MultiRelease jar resource to parse
     * @param jar the jar file
     * @throws Exception if unable to parse
     */
    protected void parseJarEntry(Set<? extends Handler> handlers, Resource jar, MultiReleaseJarFile.VersionedJarEntry entry)
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
            String shortName = StringUtil.replace(name, '/', '.').substring(0, name.length() - 6);
            addParsedClass(shortName, Resource.newResource("jar:" + jar.getURI() + "!/" + entry.getNameInJar()));
            if (LOG.isDebugEnabled())
                LOG.debug("Scanning class from jar {}!/{}", jar, entry);
            try (InputStream is = entry.getInputStream())
            {
                scanClass(handlers, jar, is);
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
    protected void scanClass(Set<? extends Handler> handlers, Resource containingResource, InputStream is) throws IOException
    {
        ClassReader reader = new ClassReader(is);
        reader.accept(new MyClassVisitor(handlers, containingResource, _asmVersion), ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    /**
     * Remove any parsed class names.
     */
    public void resetParsedClasses()
    {
        _parsedClassNames.clear();
    }

    /**
     * Check that the given path represents a valid class file name.
     * The check is fairly cursory, checking that:
     * <ul>
     * <li> the name ends with .class</li>
     * <li> it isn't a dot file or in a hidden directory </li>
     * <li> the name of the class at least begins with a valid identifier for a class name </li>
     * </ul>
     *
     * @param name the class file name
     * @return whether the class file name is valid
     */
    public boolean isValidClassFileName(String name)
    {
        //no name cannot be valid
        if (name == null || name.length() == 0)
            return false;

        //skip anything that is not a class file
        String lc = name.toLowerCase(Locale.ENGLISH);
        if (!lc.endsWith(".class"))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Not a class: {}", name);
            return false;
        }

        if (lc.equals("module-info.class"))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Skipping module-info.class");
            return false;
        }

        //skip any classfiles that are not a valid java identifier
        int c0 = 0;
        int ldir = name.lastIndexOf('/', name.length() - 6);
        c0 = (ldir > -1 ? ldir + 1 : c0);
        if (!Character.isJavaIdentifierStart(name.charAt(c0)))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Not a java identifier: {}", name);
            return false;
        }

        return true;
    }

    /**
     * Check that the given path does not contain hidden directories
     *
     * @param path the class file path
     * @return whether the class file path is valid
     */
    public boolean isValidClassFilePath(String path)
    {
        //no path is not valid
        if (path == null || path.length() == 0)
            return false;

        // skip any classfiles that are in a hidden directory
        if (path.startsWith(".") || path.contains("/."))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Contains hidden dirs: {}", path);
            return false;
        }

        return true;
    }
}
