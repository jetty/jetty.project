//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import jakarta.servlet.ServletContainerInitializer;
import org.eclipse.jetty.ee10.annotations.AnnotationConfiguration.State;
import org.eclipse.jetty.ee10.webapp.RelativeOrdering;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.ee10.webapp.WebDescriptor;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.JAR;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class TestAnnotationConfiguration
{
    public static class TestableAnnotationConfiguration extends AnnotationConfiguration
    {
        public void assertAnnotationDiscovery(WebAppContext context, boolean b)
        {
            State state = (State)context.getAttribute(STATE);
            if (!b)
                assertTrue(state._discoverableAnnotationHandlers.isEmpty());
            else
                assertFalse(state._discoverableAnnotationHandlers.isEmpty());
        }
    }

    public Path web25;
    public Path web31false;
    public Path web31true;
    public Path jarDir;
    public Path testSciJar;
    public Path testContainerSciJar;
    public Path testWebInfClassesJar;
    public WorkDir workDir;
    public URLClassLoader containerLoader;
    public URLClassLoader webAppLoader;
    public List<Resource> classes;
    public Resource targetClasses;
    public Resource webInfClasses;

    @BeforeEach
    public void setup() throws Exception
    {
        web25 = MavenTestingUtils.getTestResourcePathFile("web25.xml");
        web31false = MavenTestingUtils.getTestResourcePathFile("web31false.xml");
        web31true = MavenTestingUtils.getTestResourcePathFile("web31true.xml");

        // prepare an sci that will be on the webapp's classpath
        jarDir = MavenTestingUtils.getProjectDirPath("src/test/jar");
        testSciJar = jarDir.resolve("test-sci.jar");
        assertTrue(Files.exists(testSciJar));

        testContainerSciJar = jarDir.resolve("test-sci-for-container-path.jar");
        testWebInfClassesJar = jarDir.resolve("test-sci-for-webinf.jar");
        Path unpacked = workDir.getEmptyPathDir();
        // unpack some classes to pretend that are in WEB-INF/classes
        FS.cleanDirectory(unpacked);
        JAR.unpack(testWebInfClassesJar.toFile(), unpacked.toFile());
        webInfClasses = ResourceFactory.root().newResource(unpacked);

        containerLoader = new URLClassLoader(new URL[]{
            testContainerSciJar.toUri().toURL()
        }, Thread.currentThread().getContextClassLoader());

        targetClasses = ResourceFactory.root().newResource(MavenPaths.targetDir().resolve("test-classes"));

        classes = List.of(webInfClasses, targetClasses);

        webAppLoader = new URLClassLoader(new URL[]{
            testSciJar.toUri().toURL(), targetClasses.getURI().toURL(), webInfClasses.getURI().toURL()
        },
            containerLoader);
    }

    @Test
    public void testAnnotationScanControl() throws Exception
    {
        //check that a 2.5 webapp with configurationDiscovered will discover annotations
        TestableAnnotationConfiguration config25 = new TestableAnnotationConfiguration();
        WebAppContext context25 = new WebAppContext();
        config25.preConfigure(context25);
        context25.setClassLoader(Thread.currentThread().getContextClassLoader());
        context25.setAttribute(AnnotationConfiguration.MULTI_THREADED, Boolean.FALSE);
        context25.setAttribute(AnnotationConfiguration.MAX_SCAN_WAIT, 0);
        context25.setConfigurationDiscovered(false);
        context25.getMetaData().setWebDescriptor(new WebDescriptor(context25.getResourceFactory().newResource(web25)));
        context25.getContext().getServletContext().setEffectiveMajorVersion(2);
        context25.getContext().getServletContext().setEffectiveMinorVersion(5);
        config25.configure(context25);
        config25.assertAnnotationDiscovery(context25, false);

        //check that a 2.5 webapp discover annotations
        TestableAnnotationConfiguration config25b = new TestableAnnotationConfiguration();
        WebAppContext context25b = new WebAppContext();
        config25b.preConfigure(context25b);
        context25b.setClassLoader(Thread.currentThread().getContextClassLoader());
        context25b.setAttribute(AnnotationConfiguration.MULTI_THREADED, Boolean.FALSE);
        context25b.setAttribute(AnnotationConfiguration.MAX_SCAN_WAIT, 0);
        context25b.getMetaData().setWebDescriptor(new WebDescriptor(context25b.getResourceFactory().newResource(web25)));
        context25b.getContext().getServletContext().setEffectiveMajorVersion(2);
        context25b.getContext().getServletContext().setEffectiveMinorVersion(5);
        config25b.configure(context25b);
        config25b.assertAnnotationDiscovery(context25b, true);

        //check that a 3.x webapp with metadata true won't discover annotations
        TestableAnnotationConfiguration config31 = new TestableAnnotationConfiguration();
        WebAppContext context31 = new WebAppContext();
        config31.preConfigure(context31);
        context31.setClassLoader(Thread.currentThread().getContextClassLoader());
        context31.setAttribute(AnnotationConfiguration.MULTI_THREADED, Boolean.FALSE);
        context31.setAttribute(AnnotationConfiguration.MAX_SCAN_WAIT, 0);
        context31.getMetaData().setWebDescriptor(new WebDescriptor(context31.getResourceFactory().newResource(web31true)));
        context31.getContext().getServletContext().setEffectiveMajorVersion(3);
        context31.getContext().getServletContext().setEffectiveMinorVersion(1);
        config31.configure(context31);
        config31.assertAnnotationDiscovery(context31, false);

        //check that a 3.x webapp with metadata false will discover annotations
        TestableAnnotationConfiguration config31b = new TestableAnnotationConfiguration();
        WebAppContext context31b = new WebAppContext();
        config31b.preConfigure(context31b);
        context31b.setClassLoader(Thread.currentThread().getContextClassLoader());
        context31b.setAttribute(AnnotationConfiguration.MULTI_THREADED, Boolean.FALSE);
        context31b.setAttribute(AnnotationConfiguration.MAX_SCAN_WAIT, 0);
        context31b.getMetaData().setWebDescriptor(new WebDescriptor(context31b.getResourceFactory().newResource(web31false)));
        context31b.getContext().getServletContext().setEffectiveMajorVersion(3);
        context31b.getContext().getServletContext().setEffectiveMinorVersion(1);
        config31b.configure(context31b);
        config31b.assertAnnotationDiscovery(context31b, true);
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
            config.preConfigure(context);
            List<ServletContainerInitializer> scis;

            //test 3.1 webapp loads both server and app scis
            context.setClassLoader(webAppLoader);
            context.getMetaData().addWebInfResource(ResourceFactory.root().newResource(testSciJar));
            context.getMetaData().setWebDescriptor(new WebDescriptor(context.getResourceFactory().newResource(web31true)));
            context.getMetaData().setWebInfClassesResources(classes);
            context.getContext().getServletContext().setEffectiveMajorVersion(3);
            context.getContext().getServletContext().setEffectiveMinorVersion(1);
            config.preConfigure(context);
            State state = (State)context.getAttribute(AnnotationConfiguration.STATE);
            scis = config.getNonExcludedInitializers(state);
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
                    State state = (State)context.getAttribute(STATE);
                    super.createServletContainerInitializerAnnotationHandlers(context, scis);
                    //check class hierarchy scanner handler is registered
                    assertNotNull(state._classInheritanceHandler);
                    //check 
                    assertEquals(1, state._containerInitializerAnnotationHandlers.size());
                    ContainerInitializerAnnotationHandler handler = state._containerInitializerAnnotationHandlers.get(0);
                    assertThat(handler._holder.toString(), containsString("com.acme.initializer.FooInitializer"));
                    assertEquals("com.acme.initializer.Foo", handler._annotation.getName());
                }
            }

            MyAnnotationConfiguration config = new MyAnnotationConfiguration();

            WebAppContext context = new WebAppContext();
            config.preConfigure(context);
            List<ServletContainerInitializer> scis;

            context.setClassLoader(webAppLoader);
            context.getMetaData().addWebInfResource(ResourceFactory.root().newResource(testSciJar));
            context.getMetaData().setWebDescriptor(new WebDescriptor(context.getResourceFactory().newResource(web31true)));
            context.getMetaData().setWebInfClassesResources(classes);
            context.getContext().getServletContext().setEffectiveMajorVersion(3);
            context.getContext().getServletContext().setEffectiveMinorVersion(1);
            config.preConfigure(context);
            State state = (State)context.getAttribute(AnnotationConfiguration.STATE);
            scis = config.getNonExcludedInitializers(state);
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
            config.preConfigure(context);
            List<ServletContainerInitializer> scis;
            // test a 3.1 webapp with metadata-complete=false loads both server
            // and webapp scis
            context.setClassLoader(webAppLoader);
            context.getMetaData().setWebDescriptor(new WebDescriptor(context.getResourceFactory().newResource(web31false)));
            context.getMetaData().setWebInfClassesResources(classes);
            context.getMetaData().addWebInfResource(ResourceFactory.root().newResource(testSciJar));
            context.getContext().getServletContext().setEffectiveMajorVersion(3);
            context.getContext().getServletContext().setEffectiveMinorVersion(1);
            config.preConfigure(context);
            State state = (State)context.getAttribute(AnnotationConfiguration.STATE);
            scis = config.getNonExcludedInitializers(state);
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

        Path orderedFragmentJar = jarDir.resolve("test-sci-with-ordering.jar");
        assertTrue(Files.exists(orderedFragmentJar));
        URLClassLoader orderedLoader = new URLClassLoader(new URL[]{
            orderedFragmentJar.toUri().toURL(), testSciJar.toUri().toURL(),
            targetClasses.getURI().toURL(), webInfClasses.getURI().toURL()
        },
            containerLoader);
        Thread.currentThread().setContextClassLoader(orderedLoader);

        try
        {
            AnnotationConfiguration config = new AnnotationConfiguration();
            WebAppContext context = new WebAppContext();
            config.preConfigure(context);
            List<ServletContainerInitializer> scis;
            context.setClassLoader(orderedLoader);
            context.getMetaData().setWebDescriptor(new WebDescriptor(context.getResourceFactory().newResource(web31true)));
            RelativeOrdering ordering = new RelativeOrdering(context.getMetaData());
            context.getMetaData().setOrdering(ordering);
            context.getMetaData().addWebInfResource(ResourceFactory.root().newResource(orderedFragmentJar));
            context.getMetaData().addWebInfResource(ResourceFactory.root().newResource(testSciJar));
            context.getMetaData().setWebInfClassesResources(classes);
            context.getMetaData().orderFragments();
            context.getContext().getServletContext().setEffectiveMajorVersion(3);
            context.getContext().getServletContext().setEffectiveMinorVersion(1);
            config.preConfigure(context);
            State state = (State)context.getAttribute(AnnotationConfiguration.STATE);
            scis = config.getNonExcludedInitializers(state);
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
            config.preConfigure(context);
            List<ServletContainerInitializer> scis;
            context.setConfigurationDiscovered(false);
            context.setClassLoader(webAppLoader);
            context.getMetaData().setWebDescriptor(new WebDescriptor(context.getResourceFactory().newResource(web25)));
            context.getMetaData().setWebInfClassesResources(classes);
            context.getMetaData().addWebInfResource(ResourceFactory.root().newResource(testSciJar));
            context.getContext().getServletContext().setEffectiveMajorVersion(2);
            context.getContext().getServletContext().setEffectiveMinorVersion(5);
            config.preConfigure(context);
            State state = (State)context.getAttribute(AnnotationConfiguration.STATE);
            scis = config.getNonExcludedInitializers(state);
            assertNotNull(scis);
            for (ServletContainerInitializer s : scis)
            {
                //should not have any of the web-inf lib scis in here
                assertNotEquals("com.acme.ordering.AcmeServletContainerInitializer", s.getClass().getName());
                assertNotEquals("com.acme.initializer.FooInitializer", s.getClass().getName());
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
            config.preConfigure(context);
            List<ServletContainerInitializer> scis;
            context.setConfigurationDiscovered(true);
            context.setClassLoader(webAppLoader);
            context.getMetaData().setWebDescriptor(new WebDescriptor(context.getResourceFactory().newResource(web25)));
            context.getMetaData().setWebInfClassesResources(classes);
            context.getMetaData().addWebInfResource(ResourceFactory.root().newResource(testSciJar));
            context.getContext().getServletContext().setEffectiveMajorVersion(2);
            context.getContext().getServletContext().setEffectiveMinorVersion(5);
            config.preConfigure(context);
            State state = (State)context.getAttribute(AnnotationConfiguration.STATE);
            scis = config.getNonExcludedInitializers(state);
            assertNotNull(scis);
            assertEquals(3, scis.size(), scis::toString);
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
