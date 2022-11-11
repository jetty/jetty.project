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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.acme.webapp.TestAnnotation;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestMetaData
{
    Path fragFile;
    Path nonFragFile;
    Resource fragResource;
    Resource nonFragResource;
    ResourceFactory.Closeable resourceFactory;
    Resource webfragxml;
    Resource containerDir;
    Resource webInfClassesDir;
    WebAppContext wac;
    TestAnnotation annotationA;
    TestAnnotation annotationB;
    TestAnnotation annotationC;
    TestAnnotation annotationD;
    TestAnnotation annotationE;
    List<TestAnnotation> applications;

    @BeforeEach
    public void setUp(WorkDir workDir) throws Exception
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
        resourceFactory = ResourceFactory.closeable();

        Path testDir = workDir.getEmptyPathDir();
        Path jarsDir = testDir.resolve("jars");
        FS.ensureDirExists(jarsDir);

        // Zeta JAR
        fragFile = jarsDir.resolve("zeta.jar");
        Files.copy(MavenTestingUtils.getTargetPath().resolve("test-classes/fragments/zeta.jar"), fragFile);
        assertTrue(Files.exists(fragFile));
        fragResource = resourceFactory.newResource(fragFile);
        assertNotNull(fragResource);

        // Sigma JAR
        nonFragFile = jarsDir.resolve("sigma.jar");
        Files.copy(MavenTestingUtils.getTargetPath().resolve("test-classes/fragments/sigma.jar"), nonFragFile);
        assertTrue(Files.exists(nonFragFile));
        nonFragResource = resourceFactory.newResource(nonFragFile);
        assertNotNull(nonFragResource);

        // Various Resources
        Resource fragMount = resourceFactory.newJarFileResource(fragFile.toUri());
        assertNotNull(fragMount);
        webfragxml = fragMount.resolve("/META-INF/web-fragment.xml");
        assertTrue(Resources.isReadableFile(webfragxml));

        Path testContainerDir = testDir.resolve("container");
        FS.ensureDirExists(testContainerDir);
        Path testWebInfClassesDir = testDir.resolve("webinfclasses");
        FS.ensureDirExists(testWebInfClassesDir);

        containerDir = resourceFactory.newResource(testContainerDir);
        assertTrue(Resources.isReadableDirectory(containerDir));
        webInfClassesDir = resourceFactory.newResource(testWebInfClassesDir);
        assertTrue(Resources.isReadableDirectory(webInfClassesDir));

        wac = new WebAppContext();
        applications = new ArrayList<>();
        annotationA = new TestAnnotation(wac, "com.acme.A", fragResource, applications);
        annotationB = new TestAnnotation(wac, "com.acme.B", nonFragResource, applications);
        annotationC = new TestAnnotation(wac, "com.acme.C", null, applications);
        annotationD = new TestAnnotation(wac, "com.acme.D", containerDir, applications);
        annotationE = new TestAnnotation(wac, "com.acme.E", webInfClassesDir, applications);
    }

    @AfterEach
    public void tearDown()
    {
        IO.close(resourceFactory);
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @Test
    public void testAddWebInfResource() throws Exception
    {
        assertTrue(wac.getMetaData().getWebInfResources(false).isEmpty());
        wac.getMetaData().addWebInfResource(fragResource);
        wac.getMetaData().addWebInfResource(nonFragResource);
        assertTrue(wac.getMetaData().getWebInfResources(false).contains(fragResource));
        assertTrue(wac.getMetaData().getWebInfResources(false).contains(nonFragResource));
    }

    @Test
    public void testGetFragmentForJar() throws Exception
    {
        wac.getMetaData().addWebInfResource(fragResource);
        wac.getMetaData().addWebInfResource(nonFragResource);
        wac.getMetaData().addFragmentDescriptor(fragResource, new FragmentDescriptor(webfragxml));
        assertThrows(NullPointerException.class, () ->
        {
            wac.getMetaData().addFragmentDescriptor(nonFragResource, null);
        });

        assertNotNull(wac.getMetaData().getFragmentDescriptorForJar(fragResource));
        assertNull(wac.getMetaData().getFragmentDescriptorForJar(nonFragResource));
        assertNull(wac.getMetaData().getFragmentDescriptorForJar(null));
    }

    @Test
    public void testGetFragmentDescriptorByName() throws Exception
    {
        wac.getMetaData().addWebInfResource(fragResource);
        wac.getMetaData().addWebInfResource(nonFragResource);
        FragmentDescriptor fragDescriptor = new FragmentDescriptor(webfragxml);
        wac.getMetaData().addFragmentDescriptor(fragResource, fragDescriptor);
        assertNotNull(wac.getMetaData().getFragmentDescriptor(fragDescriptor.getName()));
    }

    @Test
    public void testGetFragmentDescriptorByLocation() throws Exception
    {
        wac.getMetaData().addWebInfResource(fragResource);
        wac.getMetaData().addWebInfResource(nonFragResource);
        FragmentDescriptor fragDescriptor = new FragmentDescriptor(webfragxml);
        wac.getMetaData().addFragmentDescriptor(fragResource, fragDescriptor);
        assertNotNull(wac.getMetaData().getFragmentDescriptor(webfragxml));
    }

    @Test
    public void testGetJarForFragmentName() throws Exception
    {
        wac.getMetaData().addWebInfResource(fragResource);
        wac.getMetaData().addWebInfResource(nonFragResource);
        wac.getMetaData().addFragmentDescriptor(fragResource, new FragmentDescriptor(webfragxml));
        FragmentDescriptor descriptor = wac.getMetaData().getFragmentDescriptorForJar(fragResource);
        assertNotNull(descriptor);

        assertNotNull(wac.getMetaData().getJarForFragmentName(descriptor.getName()));
        assertNull(wac.getMetaData().getJarForFragmentName(null));
        assertNull(wac.getMetaData().getJarForFragmentName(""));
        assertNull(wac.getMetaData().getJarForFragmentName("xxx"));
    }

    @Test
    public void testAddDiscoveredAnnotation() throws Exception
    {
        wac.getMetaData().addWebInfResource(fragResource);
        wac.getMetaData().addWebInfResource(nonFragResource);
        wac.getMetaData().addFragmentDescriptor(fragResource, new FragmentDescriptor(webfragxml));
        wac.getMetaData().addContainerResource(containerDir);
        wac.getMetaData().setWebInfClassesResources(Collections.singletonList(webInfClassesDir));

        wac.getMetaData().addDiscoveredAnnotation(annotationA);
        wac.getMetaData().addDiscoveredAnnotation(annotationB);
        wac.getMetaData().addDiscoveredAnnotation(annotationC);
        wac.getMetaData().addDiscoveredAnnotation(annotationD);
        wac.getMetaData().addDiscoveredAnnotation(annotationE);

        //test an annotation from a web-inf lib fragment
        List<DiscoveredAnnotation> list = wac.getMetaData()._annotations.get(fragResource);
        assertThat(list, contains(annotationA));
        assertThat(list, hasSize(1));

        //test an annotation from a web-inf lib fragment without a descriptor
        list = wac.getMetaData()._annotations.get(nonFragResource);
        assertThat(list, contains(annotationB));
        assertThat(list, hasSize(1));

        //test an annotation that didn't have an associated resource
        list = wac.getMetaData()._annotations.get(null);
        assertThat(list, contains(annotationC));
        assertThat(list, hasSize(1));

        //test an annotation that came from the container path
        list = wac.getMetaData()._annotations.get(containerDir);
        assertThat(list, contains(annotationD));
        assertThat(list, hasSize(1));

        //test an annoation from web-inf classes
        list = wac.getMetaData()._annotations.get(webInfClassesDir);
        assertThat(list, contains(annotationE));
        assertThat(list, hasSize(1));
    }

    @Test
    public void testResolve() throws Exception
    {
        wac.getMetaData().addWebInfResource(fragResource);
        wac.getMetaData().addWebInfResource(nonFragResource);
        wac.getMetaData().addFragmentDescriptor(fragResource, new FragmentDescriptor(webfragxml));
        wac.getMetaData().addContainerResource(containerDir);
        wac.getMetaData().setWebInfClassesResources(Collections.singletonList(webInfClassesDir));

        wac.getMetaData().addDiscoveredAnnotation(annotationA);
        wac.getMetaData().addDiscoveredAnnotation(annotationB);
        wac.getMetaData().addDiscoveredAnnotation(annotationC);
        wac.getMetaData().addDiscoveredAnnotation(annotationD);
        wac.getMetaData().addDiscoveredAnnotation(annotationE);

        wac.getMetaData().resolve(wac);
        //test that annotations are applied from resources in order:
        //no resource associated, container resources, web-inf classes resources, web-inf lib resources
        assertThat(applications, contains(annotationC, annotationD, annotationE, annotationA, annotationB));
    }
}