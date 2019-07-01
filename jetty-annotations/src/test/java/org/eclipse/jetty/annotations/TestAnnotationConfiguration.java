//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.servlet.ServletContainerInitializer;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.JAR;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.FragmentDescriptor;
import org.eclipse.jetty.webapp.RelativeOrdering;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        webInfClasses = Resource.newResource(unpacked);

        containerLoader = new URLClassLoader(new URL[]{
            testContainerSciJar.toURI().toURL()
        }, Thread.currentThread().getContextClassLoader());

        targetClasses = Resource.newResource(MavenTestingUtils.getTargetDir().toURI()).addPath("/test-classes");

        classes = Arrays.asList(new Resource[]{webInfClasses, targetClasses});

        webAppLoader = new URLClassLoader(new URL[]{
            testSciJar.toURI().toURL(), targetClasses.getURI().toURL(), webInfClasses.getURI().toURL()
        },
            containerLoader);
    }

    @Test
    public void testAnnotationScanControl() throws Exception
    {
        //check that a 2.5 webapp won't discover annotations
        TestableAnnotationConfiguration config25 = new TestableAnnotationConfiguration();
        WebAppContext context25 = new WebAppContext();
        context25.setClassLoader(Thread.currentThread().getContextClassLoader());
        context25.setAttribute(AnnotationConfiguration.MULTI_THREADED, Boolean.FALSE);
        context25.setAttribute(AnnotationConfiguration.MAX_SCAN_WAIT, 0);
        context25.getMetaData().setWebXml(Resource.newResource(web25));
        context25.getServletContext().setEffectiveMajorVersion(2);
        context25.getServletContext().setEffectiveMinorVersion(5);
        config25.configure(context25);
        config25.assertAnnotationDiscovery(false);

        //check that a 2.5 webapp with configurationDiscovered will discover annotations
        TestableAnnotationConfiguration config25b = new TestableAnnotationConfiguration();
        WebAppContext context25b = new WebAppContext();
        context25b.setClassLoader(Thread.currentThread().getContextClassLoader());
        context25b.setAttribute(AnnotationConfiguration.MULTI_THREADED, Boolean.FALSE);
        context25b.setAttribute(AnnotationConfiguration.MAX_SCAN_WAIT, 0);
        context25b.setConfigurationDiscovered(true);
        context25b.getMetaData().setWebXml(Resource.newResource(web25));
        context25b.getServletContext().setEffectiveMajorVersion(2);
        context25b.getServletContext().setEffectiveMinorVersion(5);
        config25b.configure(context25b);
        config25b.assertAnnotationDiscovery(true);

        //check that a 3.x webapp with metadata true won't discover annotations
        TestableAnnotationConfiguration config31 = new TestableAnnotationConfiguration();
        WebAppContext context31 = new WebAppContext();
        context31.setClassLoader(Thread.currentThread().getContextClassLoader());
        context31.setAttribute(AnnotationConfiguration.MULTI_THREADED, Boolean.FALSE);
        context31.setAttribute(AnnotationConfiguration.MAX_SCAN_WAIT, 0);
        context31.getMetaData().setWebXml(Resource.newResource(web31true));
        context31.getServletContext().setEffectiveMajorVersion(3);
        context31.getServletContext().setEffectiveMinorVersion(1);
        config31.configure(context31);
        config31.assertAnnotationDiscovery(false);

        //check that a 3.x webapp with metadata false will discover annotations
        TestableAnnotationConfiguration config31b = new TestableAnnotationConfiguration();
        WebAppContext context31b = new WebAppContext();
        context31b.setClassLoader(Thread.currentThread().getContextClassLoader());
        context31b.setAttribute(AnnotationConfiguration.MULTI_THREADED, Boolean.FALSE);
        context31b.setAttribute(AnnotationConfiguration.MAX_SCAN_WAIT, 0);
        context31b.getMetaData().setWebXml(Resource.newResource(web31false));
        context31b.getServletContext().setEffectiveMajorVersion(3);
        context31b.getServletContext().setEffectiveMinorVersion(1);
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
            context.getMetaData().addWebInfJar(Resource.newResource(testSciJar.toURI().toURL()));
            context.getMetaData().setWebXml(Resource.newResource(web31true));
            context.getMetaData().setWebInfClassesDirs(classes);
            context.getServletContext().setEffectiveMajorVersion(3);
            context.getServletContext().setEffectiveMinorVersion(1);
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
            context.getMetaData().setWebXml(Resource.newResource(web31false));
            context.getMetaData().setWebInfClassesDirs(classes);
            context.getMetaData().addWebInfJar(Resource.newResource(testSciJar.toURI().toURL()));
            context.getServletContext().setEffectiveMajorVersion(3);
            context.getServletContext().setEffectiveMinorVersion(1);
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
            context.getMetaData().setWebXml(Resource.newResource(web31true));
            RelativeOrdering ordering = new RelativeOrdering(context.getMetaData());
            context.getMetaData().setOrdering(ordering);
            context.getMetaData().addWebInfJar(Resource.newResource(orderedFragmentJar.toURI().toURL()));
            context.getMetaData().addWebInfJar(Resource.newResource(testSciJar.toURI().toURL()));
            context.getMetaData().setWebInfClassesDirs(classes);
            context.getMetaData().orderFragments();
            context.getServletContext().setEffectiveMajorVersion(3);
            context.getServletContext().setEffectiveMinorVersion(1);
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
            context.setClassLoader(webAppLoader);
            context.getMetaData().setWebXml(Resource.newResource(web25));
            context.getMetaData().setWebInfClassesDirs(classes);
            context.getMetaData().addWebInfJar(Resource.newResource(testSciJar.toURI().toURL()));
            context.getServletContext().setEffectiveMajorVersion(2);
            context.getServletContext().setEffectiveMinorVersion(5);
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
            context.getMetaData().setWebXml(Resource.newResource(web25));
            context.getMetaData().setWebInfClassesDirs(classes);
            context.getMetaData().addWebInfJar(Resource.newResource(testSciJar.toURI().toURL()));
            context.getServletContext().setEffectiveMajorVersion(2);
            context.getServletContext().setEffectiveMinorVersion(5);
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
    public void testGetFragmentFromJar() throws Exception
    {
        String dir = MavenTestingUtils.getTargetTestingDir("getFragmentFromJar").getAbsolutePath();
        File file = new File(dir);
        file = new File(file.getCanonicalPath());
        URL url = file.toURI().toURL();

        Resource jar1 = Resource.newResource(url + "file.jar");

        AnnotationConfiguration config = new AnnotationConfiguration();
        WebAppContext wac = new WebAppContext();

        List<FragmentDescriptor> frags = new ArrayList<FragmentDescriptor>();
        frags.add(new FragmentDescriptor(Resource.newResource("jar:" + url + "file.jar!/fooa.props")));
        frags.add(new FragmentDescriptor(Resource.newResource("jar:" + url + "file2.jar!/foob.props")));

        assertNotNull(config.getFragmentFromJar(jar1, frags));
    }
}
