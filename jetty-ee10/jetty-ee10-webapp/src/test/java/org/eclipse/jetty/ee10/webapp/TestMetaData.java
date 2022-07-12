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

package org.eclipse.jetty.ee10.webapp;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.acme.webapp.TestAnnotation;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.EmptyResource;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestMetaData
{
    File fragFile;
    File nonFragFile;
    Resource fragResource;
    Resource nonFragResource;
    Resource.Mount mount;
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
    public void setUp() throws Exception
    {
        File jarDir = new File(MavenTestingUtils.getTestResourcesDir(), "fragments");
        assertTrue(jarDir.exists());
        fragFile = new File(jarDir, "zeta.jar");
        assertTrue(fragFile.exists());
        fragResource = Resource.newResource(fragFile.toPath());
        nonFragFile = new File(jarDir, "sigma.jar");
        nonFragResource = Resource.newResource(nonFragFile.toPath());
        assertTrue(nonFragFile.exists());
        mount = Resource.mountJar(fragFile.toPath());
        webfragxml = mount.root().resolve("/META-INF/web-fragment.xml");
        containerDir = Resource.newResource(MavenTestingUtils.getTargetTestingDir("container").toPath());
        webInfClassesDir = Resource.newResource(MavenTestingUtils.getTargetTestingDir("webinfclasses").toPath());
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
        IO.close(mount);
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
        list = wac.getMetaData()._annotations.get(EmptyResource.INSTANCE);
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