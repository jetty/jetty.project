//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.webapp;

import java.io.File;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    @BeforeEach
    public void setUp() throws Exception
    {
        File jarDir = new File(MavenTestingUtils.getTestResourcesDir(), "fragments");
        assertTrue(jarDir.exists());
        fragFile = new File(jarDir, "zeta.jar");
        assertTrue(fragFile.exists());
        fragResource = Resource.newResource(fragFile);

        nonFragFile = new File(jarDir, "sigma.jar");
        nonFragResource = Resource.newResource(nonFragFile);
        assertTrue(nonFragFile.exists());
    }

    @Test
    public void testAddWebInfLibJar() throws Exception
    {
        WebAppContext wac = new WebAppContext();
        assertTrue(wac.getMetaData().getWebInfResources(false).isEmpty());
        wac.getMetaData().addWebInfResource(fragResource);
        wac.getMetaData().addWebInfResource(nonFragResource);
        assertTrue(wac.getMetaData().getWebInfResources(false).contains(fragResource));
        assertTrue(wac.getMetaData().getWebInfResources(false).contains(nonFragResource));
    }
    
    @Test
    public void testGetFragmentFromJar() throws Exception
    {        
        Resource webfragxml = Resource.newResource("jar:" + fragFile.toURI().toString() + "!/META-INF/web-fragment.xml");
        
        WebAppContext wac = new WebAppContext();
        wac.getMetaData().addWebInfResource(fragResource);
        wac.getMetaData().addWebInfResource(nonFragResource);
        wac.getMetaData().addFragmentDescriptor(fragResource, webfragxml);
        assertThrows(NullPointerException.class, () ->
        {
            wac.getMetaData().addFragmentDescriptor(nonFragResource, null);
        });

        assertNotNull(wac.getMetaData().getFragmentDescriptorForJar(fragResource));
        assertNull(wac.getMetaData().getFragmentDescriptorForJar(nonFragResource));
        assertNull(wac.getMetaData().getFragmentDescriptorForJar(null));
    } 
}