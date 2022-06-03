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

package org.eclipse.jetty.ee9.webapp;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.toolchain.test.ExtraMatchers.ordered;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class WebAppClassLoaderTest
{
    private Path testWebappDir;
    private WebAppContext _context;
    protected WebAppClassLoader _loader;

    @BeforeEach
    public void init() throws Exception
    {
        this.testWebappDir = MavenTestingUtils.getTargetPath("test-classes/webapp");
        Resource webapp = Resource.newResource(testWebappDir);

        _context = new WebAppContext();
        _context.setBaseResource(webapp);
        _context.setContextPath("/test");
        _context.setExtraClasspath("target/test-classes/ext/*");

        _loader = new WebAppClassLoader(_context);
        _loader.addJars(webapp.resolve("WEB-INF/lib"));
        _loader.addClassPath(webapp.resolve("WEB-INF/classes"));
        _loader.setName("test");

        _context.setServer(new Server());
    }

    public void assertCanLoadClass(String clazz) throws ClassNotFoundException
    {
        assertThat("Can Load Class [" + clazz + "]", _loader.loadClass(clazz), notNullValue());
    }

    public void assertCanLoadResource(String res)
    {
        assertThat("Can Load Resource [" + res + "]", _loader.getResource(res), notNullValue());
    }

    public void assertCantLoadClass(String clazz)
    {
        try
        {
            assertThat("Can't Load Class [" + clazz + "]", _loader.loadClass(clazz), nullValue());
        }
        catch (ClassNotFoundException e)
        {
            // Valid path
        }
    }

    @Test
    public void testParentLoad() throws Exception
    {
        _context.setParentLoaderPriority(true);

        assertCanLoadClass("org.acme.webapp.ClassInJarA");
        assertCanLoadClass("org.acme.webapp.ClassInJarB");
        assertCanLoadClass("org.acme.other.ClassInClassesC");

        assertCanLoadClass("org.acme.extone.Main");
        assertCanLoadClass("org.acme.exttwo.Main");
        assertCantLoadClass("org.acme.extthree.Main");

        assertCantLoadClass("org.eclipse.jetty.ee9.webapp.Configuration");

        Class<?> clazzA = _loader.loadClass("org.acme.webapp.ClassInJarA");
        assertNotNull(clazzA.getField("FROM_PARENT"));
    }

    @Test
    public void testWebAppLoad() throws Exception
    {
        _context.setParentLoaderPriority(false);
        assertCanLoadClass("org.acme.webapp.ClassInJarA");
        assertCanLoadClass("org.acme.webapp.ClassInJarB");
        assertCanLoadClass("org.acme.other.ClassInClassesC");

        assertCanLoadClass("org.acme.extone.Main");
        assertCanLoadClass("org.acme.exttwo.Main");
        assertCantLoadClass("org.acme.extthree.Main");

        assertCantLoadClass("org.eclipse.jetty.ee9.webapp.Configuration");

        Class<?> clazzA = _loader.loadClass("org.acme.webapp.ClassInJarA");
        assertThrows(NoSuchFieldException.class, () ->
            clazzA.getField("FROM_PARENT"));
    }

    @Test
    public void testClassFileTranslations() throws Exception
    {
        final List<Object> results = new ArrayList<>();

        _loader.addTransformer(new ClassFileTransformer()
        {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer)
            {
                results.add(loader);
                byte[] b = new byte[classfileBuffer.length];
                for (int i = 0; i < classfileBuffer.length; i++)
                {
                    b[i] = (byte)(classfileBuffer[i] ^ 0xff);
                }
                return b;
            }
        });
        _loader.addTransformer(new ClassFileTransformer()
        {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer)
            {
                results.add(className);
                byte[] b = new byte[classfileBuffer.length];
                for (int i = 0; i < classfileBuffer.length; i++)
                {
                    b[i] = (byte)(classfileBuffer[i] ^ 0xff);
                }
                return b;
            }
        });

        _context.setParentLoaderPriority(false);

        assertCanLoadClass("org.acme.webapp.ClassInJarA");
        assertCanLoadClass("org.acme.webapp.ClassInJarB");
        assertCanLoadClass("org.acme.other.ClassInClassesC");
        assertCanLoadClass("java.lang.String");
        assertCantLoadClass("org.eclipse.jetty.ee9.webapp.Configuration");

        assertThat("Classname Results", results, contains(
            _loader,
            "org.acme.webapp.ClassInJarA",
            _loader,
            "org.acme.webapp.ClassInJarB",
            _loader,
            "org.acme.other.ClassInClassesC"));
    }

    @Test
    public void testNullClassFileTransformer() throws Exception
    {
        _loader.addTransformer(new ClassFileTransformer()
        {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer)
            {
                return null;
            }
        });

        assertCanLoadClass("org.acme.webapp.ClassInJarA");
    }

    @Test
    public void testExposedClassDeprecated() throws Exception
    {
        String[] oldSC = _context.getServerClasses();
        String[] newSC = new String[oldSC.length + 1];
        newSC[0] = "-org.eclipse.jetty.ee9.webapp.Configuration";
        System.arraycopy(oldSC, 0, newSC, 1, oldSC.length);
        _context.setServerClassMatcher(new ClassMatcher(newSC));

        assertCanLoadClass("org.acme.webapp.ClassInJarA");
        assertCanLoadClass("org.acme.webapp.ClassInJarB");
        assertCanLoadClass("org.acme.other.ClassInClassesC");

        assertCanLoadClass("org.eclipse.jetty.ee9.webapp.Configuration");
        assertCantLoadClass("org.eclipse.jetty.ee9.webapp.JarScanner");
    }

    @Test
    public void testExposedClass() throws Exception
    {
        _context.getServerClassMatcher().exclude("org.eclipse.jetty.ee9.webapp.Configuration");

        assertCanLoadClass("org.acme.webapp.ClassInJarA");
        assertCanLoadClass("org.acme.webapp.ClassInJarB");
        assertCanLoadClass("org.acme.other.ClassInClassesC");

        assertCanLoadClass("org.eclipse.jetty.ee9.webapp.Configuration");
        assertCantLoadClass("org.eclipse.jetty.ee9.webapp.JarScanner");
    }

    @Test
    public void testSystemServerClassDeprecated() throws Exception
    {
        String[] oldServC = _context.getServerClasses();
        String[] newServC = new String[oldServC.length + 1];
        newServC[0] = "org.eclipse.jetty.ee9.webapp.Configuration";
        System.arraycopy(oldServC, 0, newServC, 1, oldServC.length);

        _context.setServerClassMatcher(new ClassMatcher(newServC));

        String[] oldSysC = _context.getSystemClasses();
        String[] newSysC = new String[oldSysC.length + 1];
        newSysC[0] = "org.eclipse.jetty.ee9.webapp.";
        System.arraycopy(oldSysC, 0, newSysC, 1, oldSysC.length);
        _context.setSystemClassMatcher(new ClassMatcher(newSysC));

        assertCanLoadClass("org.acme.webapp.ClassInJarA");
        assertCanLoadClass("org.acme.webapp.ClassInJarB");
        assertCanLoadClass("org.acme.other.ClassInClassesC");
        assertCantLoadClass("org.eclipse.jetty.ee9.webapp.Configuration");
        assertCantLoadClass("org.eclipse.jetty.ee9.webapp.JarScanner");

        oldSysC = _context.getSystemClasses();
        newSysC = new String[oldSysC.length + 1];
        newSysC[0] = "org.acme.webapp.ClassInJarA";
        System.arraycopy(oldSysC, 0, newSysC, 1, oldSysC.length);
        _context.setSystemClassMatcher(new ClassMatcher(newSysC));

        assertCanLoadResource("org/acme/webapp/ClassInJarA.class");
        _context.setSystemClassMatcher(new ClassMatcher(oldSysC));

        oldServC = _context.getServerClasses();
        newServC = new String[oldServC.length + 1];
        newServC[0] = "org.acme.webapp.ClassInJarA";
        System.arraycopy(oldServC, 0, newServC, 1, oldServC.length);
        _context.setServerClassMatcher(new ClassMatcher(newServC));
        assertCanLoadResource("org/acme/webapp/ClassInJarA.class");
    }

    @Test
    public void testSystemServerClass() throws Exception
    {
        _context.getServerClassMatcher().add("org.eclipse.jetty.ee9.webapp.Configuration");
        _context.getSystemClassMatcher().add("org.eclipse.jetty.ee9.webapp.");

        assertCanLoadClass("org.acme.webapp.ClassInJarA");
        assertCanLoadClass("org.acme.webapp.ClassInJarB");
        assertCanLoadClass("org.acme.other.ClassInClassesC");
        assertCantLoadClass("org.eclipse.jetty.ee9.webapp.Configuration");
        assertCantLoadClass("org.eclipse.jetty.ee9.webapp.JarScanner");

        _context.getSystemClassMatcher().add("org.acme.webapp.ClassInJarA");
        assertCanLoadResource("org/acme/webapp/ClassInJarA.class");
        _context.getSystemClassMatcher().remove("org.acme.webapp.ClassInJarA");

        _context.getServerClassMatcher().add("org.acme.webapp.ClassInJarA");
        assertCanLoadResource("org/acme/webapp/ClassInJarA.class");
    }

    @Test
    public void testResources() throws Exception
    {
        // The existence of a URLStreamHandler changes the behavior
        assumeTrue(URLStreamHandlerUtil.getFactory() == null, "URLStreamHandler changes behavior, skip test");

        List<URL> expected = new ArrayList<>();
        List<URL> resources;

        // Expected Locations
        URL webappWebInfLibAcme = new URI("jar:" + testWebappDir.resolve("WEB-INF/lib/acme.jar").toUri().toASCIIString() + "!/org/acme/resource.txt").toURL();
        URL webappWebInfClasses = testWebappDir.resolve("WEB-INF/classes/org/acme/resource.txt").toUri().toURL();
        // (from parent classloader)
        URL targetTestClasses = this.getClass().getClassLoader().getResource("org/acme/resource.txt");

        _context.setParentLoaderPriority(false);

        resources = Collections.list(_loader.getResources("org/acme/resource.txt"));

        expected.clear();
        expected.add(webappWebInfLibAcme);
        expected.add(webappWebInfClasses);
        expected.add(targetTestClasses);

        assertThat("Resources Found (Parent Loader Priority == false)", resources, ordered(expected));

        _context.setParentLoaderPriority(true);
        // dump(_context);
        resources = Collections.list(_loader.getResources("org/acme/resource.txt"));

        expected.clear();
        expected.add(targetTestClasses);
        expected.add(webappWebInfLibAcme);
        expected.add(webappWebInfClasses);

        assertThat("Resources Found (Parent Loader Priority == true)", resources, ordered(expected));

//        dump(resources);
//        assertEquals(3,resources.size());
//        assertEquals(0,resources.get(0).toString().indexOf("file:"));
//        assertEquals(0,resources.get(1).toString().indexOf("jar:file:"));
//        assertEquals(-1,resources.get(2).toString().indexOf("test-classes"));

        String[] oldServC = _context.getServerClasses();
        String[] newServC = new String[oldServC.length + 1];
        newServC[0] = "org.acme.";
        System.arraycopy(oldServC, 0, newServC, 1, oldServC.length);
        _context.setServerClassMatcher(new ClassMatcher(newServC));

        _context.setParentLoaderPriority(true);
        // dump(_context);
        resources = Collections.list(_loader.getResources("org/acme/resource.txt"));

        expected.clear();
        expected.add(webappWebInfLibAcme);
        expected.add(webappWebInfClasses);

        assertThat("Resources Found (Parent Loader Priority == true) (with serverClasses filtering)", resources, ordered(expected));

//        dump(resources);
//        assertEquals(2,resources.size());
//        assertEquals(0,resources.get(0).toString().indexOf("jar:file:"));
//        assertEquals(0,resources.get(1).toString().indexOf("file:"));

        _context.setServerClassMatcher(new ClassMatcher(oldServC));
        String[] oldSysC = _context.getSystemClasses();
        String[] newSysC = new String[oldSysC.length + 1];
        newSysC[0] = "org.acme.";
        System.arraycopy(oldSysC, 0, newSysC, 1, oldSysC.length);
        _context.setSystemClassMatcher(new ClassMatcher(newSysC));

        _context.setParentLoaderPriority(true);
        // dump(_context);
        resources = Collections.list(_loader.getResources("org/acme/resource.txt"));

        expected.clear();
        expected.add(targetTestClasses);

        assertThat("Resources Found (Parent Loader Priority == true) (with systemClasses filtering)", resources, ordered(expected));
    }

    @Test
    public void testClashingResource() throws Exception
    {
        // The existence of a URLStreamHandler changes the behavior
        assumeTrue(URLStreamHandlerUtil.getFactory() == null, "URLStreamHandler changes behavior, skip test");

        Enumeration<URL> resources = _loader.getResources("org/acme/clashing.txt");
        assertTrue(resources.hasMoreElements());
        URL resource = resources.nextElement();
        try (InputStream data = resource.openStream())
        {
            assertThat("correct contents of " + resource, IO.toString(data), is("alpha"));
        }
        assertTrue(resources.hasMoreElements());
        resource = resources.nextElement();
        try (InputStream data = resource.openStream())
        {
            assertThat("correct contents of " + resource, IO.toString(data), is("omega"));
        }
        assertFalse(resources.hasMoreElements());
    }
}
