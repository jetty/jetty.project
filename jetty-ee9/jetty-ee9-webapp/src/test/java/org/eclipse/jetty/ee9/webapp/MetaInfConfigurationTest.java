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

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

    @BeforeEach
    public void beforeEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @AfterEach
    public void afterEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @Test
    public void testScanTypes() throws Exception
    {
        File web25 = MavenTestingUtils.getTargetFile("test-classes/web25.xml");
        File web31 = MavenTestingUtils.getTargetFile("test-classes/web31.xml");
        File web31false = MavenTestingUtils.getTargetFile("test-classes/web31false.xml");

        //test a 2.5 webapp will not look for fragments as manually configured
        MetaInfConfiguration meta25 = new TestableMetaInfConfiguration(MetaInfConfiguration.__allScanTypes,
            Arrays.asList(MetaInfConfiguration.METAINF_TLDS, MetaInfConfiguration.METAINF_RESOURCES));
        WebAppContext context25 = new WebAppContext();
        context25.setConfigurationDiscovered(false);
        context25.getMetaData().setWebDescriptor(new WebDescriptor(ResourceFactory.root().newResource(web25.toPath())));
        context25.getServletContext().setEffectiveMajorVersion(2);
        context25.getServletContext().setEffectiveMinorVersion(5);
        meta25.preConfigure(context25);

        //test a 2.5 webapp will look for fragments as configurationDiscovered default true
        MetaInfConfiguration meta25b = new TestableMetaInfConfiguration(MetaInfConfiguration.__allScanTypes,
            MetaInfConfiguration.__allScanTypes);
        WebAppContext context25b = new WebAppContext();
        context25b.getMetaData().setWebDescriptor(new WebDescriptor(ResourceFactory.root().newResource(web25.toPath())));
        context25b.getServletContext().setEffectiveMajorVersion(2);
        context25b.getServletContext().setEffectiveMinorVersion(5);
        meta25b.preConfigure(context25b);

        //test a 3.x metadata-complete webapp will not look for fragments
        MetaInfConfiguration meta31 = new TestableMetaInfConfiguration(MetaInfConfiguration.__allScanTypes,
            Arrays.asList(MetaInfConfiguration.METAINF_TLDS, MetaInfConfiguration.METAINF_RESOURCES));
        WebAppContext context31 = new WebAppContext();
        context31.getMetaData().setWebDescriptor(new WebDescriptor(ResourceFactory.root().newResource(web31.toPath())));
        context31.getServletContext().setEffectiveMajorVersion(3);
        context31.getServletContext().setEffectiveMinorVersion(1);
        meta31.preConfigure(context31);

        //test a 3.x non metadata-complete webapp will look for fragments
        MetaInfConfiguration meta31false = new TestableMetaInfConfiguration(MetaInfConfiguration.__allScanTypes,
            MetaInfConfiguration.__allScanTypes);
        WebAppContext context31false = new WebAppContext();
        context31false.setConfigurationDiscovered(true);
        context31false.getMetaData().setWebDescriptor(new WebDescriptor(ResourceFactory.root().newResource(web31false.toPath())));
        context31false.getServletContext().setEffectiveMajorVersion(3);
        context31false.getServletContext().setEffectiveMinorVersion(1);
        meta31false.preConfigure(context31false);
    }

    /**
     * This test examines both the classpath and the module path to find
     * container resources.
     * NOTE: the behaviour of the surefire plugin 3.0.0.M2 is different in
     * jetty-9.4.x to jetty-10.0.x (where we use module-info):  in jetty-9.4.x,
     * we can use the --add-module argument to put the foo-bar-janb.jar onto the
     * module path, but this doesn't seem to work in jetty-10.0.x.  So this test
     * will find foo-bar.janb.jar on the classpath, and jetty-util from the module path.
     *
     * @throws Exception if the test fails
     */
    @Test
    public void testFindAndFilterContainerPathsJDK9() throws Exception
    {
        MetaInfConfiguration config = new MetaInfConfiguration();
        WebAppContext context = new WebAppContext();
        context.setServer(new Server());
        config.preConfigure(context);
        try
        {
            context.setAttribute(MetaInfConfiguration.CONTAINER_JAR_PATTERN, ".*servlet-api-[^/]*\\.jar$|.*/foo-bar-janb.jar");
            WebAppClassLoader loader = new WebAppClassLoader(context);
            context.setClassLoader(loader);
            config.findAndFilterContainerPaths(context);
            List<Resource> containerResources = context.getMetaData().getContainerResources();
            assertEquals(2, containerResources.size());
            for (Resource r : containerResources)
            {
                String s = r.toString();
                assertTrue(s.endsWith("foo-bar-janb.jar") || s.contains("servlet-api"));
            }
        }
        finally
        {
            config.postConfigure(context);
        }
    }
}
