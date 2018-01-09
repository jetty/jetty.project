//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.JavaVersion;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.Assume;
import org.junit.Test;

/**
 * MetaInfConfigurationTest
 *
 *
 */
public class MetaInfConfigurationTest
{

    public class TestableMetaInfConfiguration extends MetaInfConfiguration
    {
        List<String> _expectedContainerScanTypes;
        List<String> _expectedWebAppScanTypes;
        int _invocationCount = 0;

        public TestableMetaInfConfiguration(List<String> expectedContainerScanTypes, List<String> expectedWebAppScanTypes)
        {
            _expectedContainerScanTypes = expectedContainerScanTypes;
            _expectedWebAppScanTypes = expectedWebAppScanTypes;
        }
        
        
        /** 
         * @see org.eclipse.jetty.webapp.MetaInfConfiguration#scanJars(org.eclipse.jetty.webapp.WebAppContext, java.util.Collection, boolean, java.util.List)
         */
        @Override
        public void scanJars(WebAppContext context, Collection<Resource> jars, boolean useCaches, List<String> scanTypes) throws Exception
        {
            assertNotNull(scanTypes);
            List<String> expectedScanTypes = null;
            switch (_invocationCount)
            {
                case 0: 
                {
                    expectedScanTypes = _expectedContainerScanTypes;
                    break;
                }
                case 1:
                {
                    expectedScanTypes = _expectedWebAppScanTypes;
                    break;
                }
                default:
                {
                    fail("Too many invocations");
                }
            }

            ++_invocationCount;

            assertNotNull(expectedScanTypes);
            assertTrue(expectedScanTypes.containsAll(scanTypes));
            assertEquals(expectedScanTypes.size(), scanTypes.size());
        }
        
    }
    
    
    @Test
    public void testScanTypes()
    throws Exception
    {
        File web25 = MavenTestingUtils.getTestResourceFile("web25.xml");
        File web31 = MavenTestingUtils.getTestResourceFile("web31.xml");
        File web31false = MavenTestingUtils.getTestResourceFile("web31false.xml");
        
        //test a 2.5 webapp will not look for fragments by default
        MetaInfConfiguration meta25 = new TestableMetaInfConfiguration(MetaInfConfiguration.__allScanTypes,
                                                                       Arrays.asList(MetaInfConfiguration.METAINF_TLDS, MetaInfConfiguration.METAINF_RESOURCES));
        WebAppContext context25 = new WebAppContext();
        context25.getMetaData().setWebXml(Resource.newResource(web25));
        context25.getServletContext().setEffectiveMajorVersion(2);
        context25.getServletContext().setEffectiveMinorVersion(5);
        meta25.preConfigure(context25);
        
        //test a 2.5 webapp will look for fragments if configurationDiscovered==true
        MetaInfConfiguration meta25b = new TestableMetaInfConfiguration(MetaInfConfiguration.__allScanTypes,
                                                                        MetaInfConfiguration.__allScanTypes);
        WebAppContext context25b = new WebAppContext();
        context25b.setConfigurationDiscovered(true);
        context25b.getMetaData().setWebXml(Resource.newResource(web25));
        context25b.getServletContext().setEffectiveMajorVersion(2);
        context25b.getServletContext().setEffectiveMinorVersion(5);
        meta25b.preConfigure(context25b);
        
        //test a 3.x metadata-complete webapp will not look for fragments
        MetaInfConfiguration meta31 = new TestableMetaInfConfiguration(MetaInfConfiguration.__allScanTypes,
                                                                       Arrays.asList(MetaInfConfiguration.METAINF_TLDS, MetaInfConfiguration.METAINF_RESOURCES));
        WebAppContext context31 = new WebAppContext();
        context31.getMetaData().setWebXml(Resource.newResource(web31));
        context31.getServletContext().setEffectiveMajorVersion(3);
        context31.getServletContext().setEffectiveMinorVersion(1);
        meta31.preConfigure(context31);

        //test a 3.x non metadata-complete webapp will look for fragments
        MetaInfConfiguration meta31false = new TestableMetaInfConfiguration(MetaInfConfiguration.__allScanTypes,
                                                                            MetaInfConfiguration.__allScanTypes);
        WebAppContext context31false = new WebAppContext();
        context31false.setConfigurationDiscovered(true);
        context31false.getMetaData().setWebXml(Resource.newResource(web31false));
        context31false.getServletContext().setEffectiveMajorVersion(3);
        context31false.getServletContext().setEffectiveMinorVersion(1);
        meta31false.preConfigure(context31false);
    }
    
    /**
     * Assume target < jdk9. In this case, we should be able to extract
     * the urls from the application classloader, and we should not look
     * at the java.class.path property.
     * @throws Exception
     */
    @Test
    public void testFindAndFilterContainerPaths()
    throws Exception
    {
        Assume.assumeTrue(JavaVersion.VERSION.getMajor() < 9);
        MetaInfConfiguration config = new MetaInfConfiguration();
        WebAppContext context = new WebAppContext();
        context.setAttribute(MetaInfConfiguration.CONTAINER_JAR_PATTERN, ".*/jetty-util-[^/]*\\.jar$|.*/jetty-util/target/classes/");
        WebAppClassLoader loader = new WebAppClassLoader(context);
        context.setClassLoader(loader);
        config.findAndFilterContainerPaths(context);
        List<Resource> containerResources = context.getMetaData().getContainerResources();
        assertEquals(1, containerResources.size());
        assertTrue(containerResources.get(0).toString().contains("jetty-util"));
    }
    
    /**
     * Assume target jdk9 or above. In this case we should extract what we need
     * from the java.class.path. We should also examine the module path.
     * @throws Exception
     */
    @Test
    public void testFindAndFilterContainerPathsJDK9()
            throws Exception
    {
        Assume.assumeTrue(JavaVersion.VERSION.getMajor() >= 9);
        Assume.assumeTrue(System.getProperty("jdk.module.path") != null);
        MetaInfConfiguration config = new MetaInfConfiguration();
        WebAppContext context = new WebAppContext();
        context.setAttribute(MetaInfConfiguration.CONTAINER_JAR_PATTERN, ".*/jetty-util-[^/]*\\.jar$|.*/jetty-util/target/classes/$|.*/foo-bar-janb.jar");
        WebAppClassLoader loader = new WebAppClassLoader(context);
        context.setClassLoader(loader);
        config.findAndFilterContainerPaths(context);
        List<Resource> containerResources = context.getMetaData().getContainerResources();
        assertEquals(2, containerResources.size());
        for (Resource r:containerResources)
        {
            String s = r.toString();
            assertTrue(s.endsWith("foo-bar-janb.jar") || s.contains("jetty-util"));
        }
    }


    /**
     * Assume runtime is jdk9 or above. Target is jdk 8. In this
     * case we must extract from the java.class.path (because jdk 9
     * has no url based application classloader), but we should
     * ignore the module path.
     * @throws Exception
     */
    @Test
    public void testFindAndFilterContainerPathsTarget8()
            throws Exception
    {
        Assume.assumeTrue(JavaVersion.VERSION.getMajor() >= 9);
        Assume.assumeTrue(System.getProperty("jdk.module.path") != null);
        MetaInfConfiguration config = new MetaInfConfiguration();
        WebAppContext context = new WebAppContext();
        context.setAttribute(JavaVersion.JAVA_TARGET_PLATFORM, "8");
        context.setAttribute(MetaInfConfiguration.CONTAINER_JAR_PATTERN, ".*/jetty-util-[^/]*\\.jar$|.*/jetty-util/target/classes/$|.*/foo-bar-janb.jar");
        WebAppClassLoader loader = new WebAppClassLoader(context);
        context.setClassLoader(loader);
        config.findAndFilterContainerPaths(context);
        List<Resource> containerResources = context.getMetaData().getContainerResources();
        assertEquals(1, containerResources.size());
        assertTrue(containerResources.get(0).toString().contains("jetty-util"));
    }


}
