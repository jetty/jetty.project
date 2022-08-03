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
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;

import jakarta.servlet.ServletContainerInitializer;
import org.eclipse.jetty.ee10.webapp.RelativeOrdering;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.ee10.webapp.WebDescriptor;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.JAR;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestAnnotationConfiguration
{
    public class TestableAnnotationConfiguration extends AnnotationConfiguration
    {
        public void assertAnnotationDiscovery(boolean b)
        {
            if (!b)
                assertTrue(_discoverableAnnotationHandlers.isEmpty());
            else
                assertFalse(_discoverableAnnotationHandlers.isEmpty());
        }
    }    

    public File web25;

    public File web31false;

    public File web31true;

    public File jarDir;

    public File testSciJar;

    public File testContainerSciJar;

    public File testWebInfClassesJar;

    public File unpacked;

    public URLClassLoader containerLoader;

    public URLClassLoader webAppLoader;

    public List<Resource> classes;

    public Resource targetClasses;

    public Resource webInfClasses;

    @BeforeEach
    public void setup() throws Exception
    {
        web25 = MavenTestingUtils.getTestResourceFile("web25.xml");
        web31false = MavenTestingUtils.getTestResourceFile("web31false.xml");
        web31true = MavenTestingUtils.getTestResourceFile("web31true.xml");

        // prepare an sci that will be on the webapp's classpath
        jarDir = new File(MavenTestingUtils.getTestResourcesDir().getParentFile(), "jar");
        testSciJar = new File(jarDir, "test-sci.jar");
        assertTrue(testSciJar.exists());

        testContainerSciJar = new File(jarDir, "test-sci-for-container-path.jar");
        testWebInfClassesJar = new File(jarDir, "test-sci-for-webinf.jar");

        // unpack some classes to pretend that are in WEB-INF/classes
        unpacked = new File(MavenTestingUtils.getTargetTestingDir(), "test-sci-for-webinf");
        unpacked.mkdirs();
        FS.cleanDirectory(unpacked);
        JAR.unpack(testWebInfClassesJar, unpacked);
        webInfClasses = ResourceFactory.ROOT.newResource(unpacked.toPath());

        containerLoader = new URLClassLoader(new URL[]{
            testContainerSciJar.toURI().toURL()
        }, Thread.currentThread().getContextClassLoader());

        targetClasses = ResourceFactory.ROOT.newResource(MavenTestingUtils.getTargetDir().toURI()).resolve("/test-classes");

        classes = Arrays.asList(new Resource[]{webInfClasses, targetClasses});

        webAppLoader = new URLClassLoader(new URL[]{
            testSciJar.toURI().toURL(), targetClasses.getURI().toURL(), webInfClasses.getURI().toURL()
        },
            containerLoader);
    }

    @Test
    public void testAnnotationScanControl() throws Exception
    {
        //check that a 2.5 webapp with configurationDiscovered will discover annotations
        TestableAnnotationConfiguration config25 = new TestableAnnotationConfiguration();
        WebAppContext context25 = new WebAppContext();
        context25.setClassLoader(Thread.currentThread().getContextClassLoader());
        context25.setAttribute(AnnotationConfiguration.MULTI_THREADED, Boolean.FALSE);
        context25.setAttribute(AnnotationConfiguration.MAX_SCAN_WAIT, 0);
        context25.setConfigurationDiscovered(false);
        context25.getMetaData().setWebDescriptor(new WebDescriptor(ResourceFactory.ROOT.newResource(web25.toPath())));
        context25.getContext().getServletContext().setEffectiveMajorVersion(2);
        context25.getContext().getServletContext().setEffectiveMinorVersion(5);
        config25.configure(context25);
        config25.assertAnnotationDiscovery(false);

        //check that a 2.5 webapp discover annotations
        TestableAnnotationConfiguration config25b = new TestableAnnotationConfiguration();
        WebAppContext context25b = new WebAppContext();
        context25b.setClassLoader(Thread.currentThread().getContextClassLoader());
        context25b.setAttribute(AnnotationConfiguration.MULTI_THREADED, Boolean.FALSE);
        context25b.setAttribute(AnnotationConfiguration.MAX_SCAN_WAIT, 0);
        context25b.getMetaData().setWebDescriptor(new WebDescriptor(ResourceFactory.ROOT.newResource(web25.toPath())));
        context25b.getContext().getServletContext().setEffectiveMajorVersion(2);
        context25b.getContext().getServletContext().setEffectiveMinorVersion(5);
        config25b.configure(context25b);
        config25b.assertAnnotationDiscovery(true);

        //check that a 3.x webapp with metadata true won't discover annotations
        TestableAnnotationConfiguration config31 = new TestableAnnotationConfiguration();
        WebAppContext context31 = new WebAppContext();
        context31.setClassLoader(Thread.currentThread().getContextClassLoader());
        context31.setAttribute(AnnotationConfiguration.MULTI_THREADED, Boolean.FALSE);
        context31.setAttribute(AnnotationConfiguration.MAX_SCAN_WAIT, 0);
        context31.getMetaData().setWebDescriptor(new WebDescriptor(ResourceFactory.ROOT.newResource(web31true.toPath())));
        context31.getContext().getServletContext().setEffectiveMajorVersion(3);
        context31.getContext().getServletContext().setEffectiveMinorVersion(1);
        config31.configure(context31);
        config31.assertAnnotationDiscovery(false);

        //check that a 3.x webapp with metadata false will discover annotations
        TestableAnnotationConfiguration config31b = new TestableAnnotationConfiguration();
        WebAppContext context31b = new WebAppContext();
        context31b.setClassLoader(Thread.currentThread().getContextClassLoader());
        context31b.setAttribute(AnnotationConfiguration.MULTI_THREADED, Boolean.FALSE);
        context31b.setAttribute(AnnotationConfiguration.MAX_SCAN_WAIT, 0);
        context31b.getMetaData().setWebDescriptor(new WebDescriptor(ResourceFactory.ROOT.newResource(web31false.toPath())));
        context31b.getContext().getServletContext().setEffectiveMajorVersion(3);
        context31b.getContext().getServletContext().setEffectiveMinorVersion(1);
        config31b.configure(context31b);
        config31b.assertAnnotationDiscovery(true);
    }

    @Test
    public void testServerAndWebappSCIs() throws Exception
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(webAppLoader);

        try
        {
            AnnotationConfiguration config = new AnnotationConfiguration();
            WebAppContext context = new WebAppContext();
            List<ServletContainerInitializer> scis;

            //test 3.1 webapp loads both server and app scis
            context.setClassLoader(webAppLoader);
            context.getMetaData().addWebInfResource(ResourceFactory.ROOT.newResource(testSciJar.toURI().toURL()));
            context.getMetaData().setWebDescriptor(new WebDescriptor(ResourceFactory.ROOT.newResource(web31true.toPath())));
            context.getMetaData().setWebInfClassesResources(classes);
            context.getContext().getServletContext().setEffectiveMajorVersion(3);
            context.getContext().getServletContext().setEffectiveMinorVersion(1);
            scis = config.getNonExcludedInitializers(context);
            assertNotNull(scis);
            assertEquals(3, scis.size());
            assertEquals("com.acme.ServerServletContainerInitializer", scis.get(0).getClass().getName()); //container path
            assertEquals("com.acme.webinf.WebInfClassServletContainerInitializer", scis.get(1).getClass().getName()); // web-inf
            assertEquals("com.acme.initializer.FooInitializer", scis.get(2).getClass().getName()); //web-inf jar no web-fragment
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }
    
    @Test
    public void testClassScanHandlersForSCIs() throws Exception
    {
        //test that SCIs with a @HandlesTypes that is an annotation registers
        //handlers for the scanning phase that will capture the class hierarchy,
        //and also capture all classes that contain the annotation
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(webAppLoader);

        try
        {
            class MyAnnotationConfiguration extends AnnotationConfiguration
            {

                @Override
                public void createServletContainerInitializerAnnotationHandlers(WebAppContext context, List<ServletContainerInitializer> scis) throws Exception
                {
                    super.createServletContainerInitializerAnnotationHandlers(context, scis);
                    //check class hierarchy scanner handler is registered
                    assertNotNull(_classInheritanceHandler);
                    //check 
                    assertEquals(1, _containerInitializerAnnotationHandlers.size());
                    ContainerInitializerAnnotationHandler handler = _containerInitializerAnnotationHandlers.get(0);
                    assertThat(handler._holder.toString(), containsString("com.acme.initializer.FooInitializer"));
                    assertEquals("com.acme.initializer.Foo", handler._annotation.getName());
                }
            }
            
            MyAnnotationConfiguration config = new MyAnnotationConfiguration();
            
            WebAppContext context = new WebAppContext();
            List<ServletContainerInitializer> scis;

            context.setClassLoader(webAppLoader);
            context.getMetaData().addWebInfResource(ResourceFactory.ROOT.newResource(testSciJar.toURI().toURL()));
            context.getMetaData().setWebDescriptor(new WebDescriptor(ResourceFactory.ROOT.newResource(web31true.toPath())));
            context.getMetaData().setWebInfClassesResources(classes);
            context.getContext().getServletContext().setEffectiveMajorVersion(3);
            context.getContext().getServletContext().setEffectiveMinorVersion(1);
            scis = config.getNonExcludedInitializers(context);
            assertNotNull(scis);
            assertEquals(3, scis.size());
            
            config.createServletContainerInitializerAnnotationHandlers(context, scis); 
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    @Test
    public void testMetaDataCompleteSCIs() throws Exception
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(webAppLoader);

        try
        {
            AnnotationConfiguration config = new AnnotationConfiguration();
            WebAppContext context = new WebAppContext();
            List<ServletContainerInitializer> scis;
            // test a 3.1 webapp with metadata-complete=false loads both server
            // and webapp scis
            context.setClassLoader(webAppLoader);
            context.getMetaData().setWebDescriptor(new WebDescriptor(ResourceFactory.ROOT.newResource(web31false.toPath())));
            context.getMetaData().setWebInfClassesResources(classes);
            context.getMetaData().addWebInfResource(ResourceFactory.ROOT.newResource(testSciJar.toURI().toURL()));
            context.getContext().getServletContext().setEffectiveMajorVersion(3);
            context.getContext().getServletContext().setEffectiveMinorVersion(1);
            scis = config.getNonExcludedInitializers(context);
            assertNotNull(scis);
            assertEquals(3, scis.size());
            assertEquals("com.acme.ServerServletContainerInitializer", scis.get(0).getClass().getName()); // container
            // path
            assertEquals("com.acme.webinf.WebInfClassServletContainerInitializer", scis.get(1).getClass().getName()); // web-inf
            assertEquals("com.acme.initializer.FooInitializer", scis.get(2).getClass().getName()); // web-inf
            // jar
            // no
            // web-fragment
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    @Test
    public void testRelativeOrderingWithSCIs() throws Exception
    {
        // test a 3.1 webapp with RELATIVE ORDERING loads sci from
        // equivalent of WEB-INF/classes first as well as container path

        ClassLoader old = Thread.currentThread().getContextClassLoader();

        File orderedFragmentJar = new File(jarDir, "test-sci-with-ordering.jar");
        assertTrue(orderedFragmentJar.exists());
        URLClassLoader orderedLoader = new URLClassLoader(new URL[]{
            orderedFragmentJar.toURI().toURL(), testSciJar.toURI().toURL(),
            targetClasses.getURI().toURL(), webInfClasses.getURI().toURL()
        },
            containerLoader);
        Thread.currentThread().setContextClassLoader(orderedLoader);

        try
        {
            AnnotationConfiguration config = new AnnotationConfiguration();
            WebAppContext context = new WebAppContext();
            List<ServletContainerInitializer> scis;
            context.setClassLoader(orderedLoader);
            context.getMetaData().setWebDescriptor(new WebDescriptor(ResourceFactory.ROOT.newResource(web31true.toPath())));
            RelativeOrdering ordering = new RelativeOrdering(context.getMetaData());
            context.getMetaData().setOrdering(ordering);
            context.getMetaData().addWebInfResource(ResourceFactory.ROOT.newResource(orderedFragmentJar.toURI().toURL()));
            context.getMetaData().addWebInfResource(ResourceFactory.ROOT.newResource(testSciJar.toURI().toURL()));
            context.getMetaData().setWebInfClassesResources(classes);
            context.getMetaData().orderFragments();
            context.getContext().getServletContext().setEffectiveMajorVersion(3);
            context.getContext().getServletContext().setEffectiveMinorVersion(1);
            scis = config.getNonExcludedInitializers(context);
            assertNotNull(scis);
            assertEquals(4, scis.size());
            assertEquals("com.acme.ServerServletContainerInitializer", scis.get(0).getClass().getName()); //container path
            assertEquals("com.acme.webinf.WebInfClassServletContainerInitializer", scis.get(1).getClass().getName()); // web-inf
            assertEquals("com.acme.ordering.AcmeServletContainerInitializer", scis.get(2).getClass().getName()); // first
            assertEquals("com.acme.initializer.FooInitializer", scis.get(3).getClass().getName()); //other in ordering
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    @Test
    public void testDiscoveredFalseWithSCIs() throws Exception
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(webAppLoader);
        try
        {
            //test 2.5 webapp with configurationDiscovered=false loads only server scis
            AnnotationConfiguration config = new AnnotationConfiguration();
            WebAppContext context = new WebAppContext();
            List<ServletContainerInitializer> scis;
            context.setConfigurationDiscovered(false);
            context.setClassLoader(webAppLoader);
            context.getMetaData().setWebDescriptor(new WebDescriptor(ResourceFactory.ROOT.newResource(web25.toPath())));
            context.getMetaData().setWebInfClassesResources(classes);
            context.getMetaData().addWebInfResource(ResourceFactory.ROOT.newResource(testSciJar.toURI().toURL()));
            context.getContext().getServletContext().setEffectiveMajorVersion(2);
            context.getContext().getServletContext().setEffectiveMinorVersion(5);
            scis = config.getNonExcludedInitializers(context);
            assertNotNull(scis);
            for (ServletContainerInitializer s : scis)
            {
                //should not have any of the web-inf lib scis in here
                assertFalse(s.getClass().getName().equals("com.acme.ordering.AcmeServletContainerInitializer"));
                assertFalse(s.getClass().getName().equals("com.acme.initializer.FooInitializer"));
                //NOTE: should also not have the web-inf classes scis in here either, but due to the
                //way the test is set up, the sci we're pretending is in web-inf classes will actually
                //NOT be loaded by the webapp's classloader, but rather by the junit classloader, so
                //it looks as if it is a container class.
            }
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    @Test
    public void testDiscoveredTrueWithSCIs() throws Exception
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(webAppLoader);
        try
        {
            //test 2.5 webapp with configurationDiscovered=true loads both server and webapp scis
            AnnotationConfiguration config = new AnnotationConfiguration();
            WebAppContext context = new WebAppContext();
            List<ServletContainerInitializer> scis;
            context.setConfigurationDiscovered(true);
            context.setClassLoader(webAppLoader);
            context.getMetaData().setWebDescriptor(new WebDescriptor(ResourceFactory.ROOT.newResource(web25.toPath())));
            context.getMetaData().setWebInfClassesResources(classes);
            context.getMetaData().addWebInfResource(ResourceFactory.ROOT.newResource(testSciJar.toURI().toURL()));
            context.getContext().getServletContext().setEffectiveMajorVersion(2);
            context.getContext().getServletContext().setEffectiveMinorVersion(5);
            scis = config.getNonExcludedInitializers(context);
            assertNotNull(scis);
            assertEquals(3, scis.size(), () ->  scis.toString());
            assertEquals("com.acme.ServerServletContainerInitializer", scis.get(0).getClass().getName()); //container path
            assertEquals("com.acme.webinf.WebInfClassServletContainerInitializer", scis.get(1).getClass().getName()); // web-inf
            assertEquals("com.acme.initializer.FooInitializer", scis.get(2).getClass().getName()); //web-inf jar no web-fragment
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }
}
