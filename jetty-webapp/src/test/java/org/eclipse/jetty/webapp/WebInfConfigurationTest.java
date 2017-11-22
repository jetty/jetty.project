//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.*;

import java.util.List;

import org.eclipse.jetty.util.JavaVersion;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.Assume;
import org.junit.Test;

/**
 * WebInfConfigurationTest
 *
 *
 */
public class WebInfConfigurationTest
{

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
        WebInfConfiguration config = new WebInfConfiguration();
        WebAppContext context = new WebAppContext();
        context.setAttribute(WebInfConfiguration.CONTAINER_JAR_PATTERN, ".*/jetty-util-[^/]*\\.jar$|.*/jetty-util/target/classes/");
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
        WebInfConfiguration config = new WebInfConfiguration();
        WebAppContext context = new WebAppContext();
        context.setAttribute(WebInfConfiguration.CONTAINER_JAR_PATTERN, ".*/jetty-util-[^/]*\\.jar$|.*/jetty-util/target/classes/$|.*/foo-bar-janb.jar");
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
        WebInfConfiguration config = new WebInfConfiguration();
        WebAppContext context = new WebAppContext();
        context.setAttribute(JavaVersion.JAVA_TARGET_PLATFORM, "8");
        context.setAttribute(WebInfConfiguration.CONTAINER_JAR_PATTERN, ".*/jetty-util-[^/]*\\.jar$|.*/jetty-util/target/classes/$|.*/foo-bar-janb.jar");
        WebAppClassLoader loader = new WebAppClassLoader(context);
        context.setClassLoader(loader);
        config.findAndFilterContainerPaths(context);
        List<Resource> containerResources = context.getMetaData().getContainerResources();
        assertEquals(1, containerResources.size());
        assertTrue(containerResources.get(0).toString().contains("jetty-util"));
    }

}
