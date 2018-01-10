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

package org.eclipse.jetty.util.resource;

import java.net.URI;
import java.util.Arrays;

import org.eclipse.jetty.toolchain.test.JDK;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.TypeUtil;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;


public class JrtResourceTest
{
    private String testResURI = MavenTestingUtils.getTestResourcesDir().getAbsoluteFile().toURI().toASCIIString();

    @Test
    public void testResourceFromUriForString()
    throws Exception
    {

        Assume.assumeTrue(JDK.IS_9);

        URI string_loc = TypeUtil.getLocationOfClass(String.class);
        Resource resource = Resource.newResource(string_loc);

        assertThat(resource.exists(), is(true));
        assertThat(resource.isDirectory(), is(false));
        assertThat(IO.readBytes(resource.getInputStream()).length,Matchers.greaterThan(0));
        assertThat(IO.readBytes(resource.getInputStream()).length,is((int)resource.length()));
        assertThat(resource.getWeakETag("-xxx"),startsWith("W/\""));
        assertThat(resource.getWeakETag("-xxx"),endsWith("-xxx\""));

    }

    @Test
    public void testResourceFromStringForString()
            throws Exception
    {
        Assume.assumeTrue(JDK.IS_9);

        URI string_loc = TypeUtil.getLocationOfClass(String.class);
        Resource resource = Resource.newResource(string_loc.toASCIIString());

        assertThat(resource.exists(), is(true));
        assertThat(resource.isDirectory(), is(false));
        assertThat(IO.readBytes(resource.getInputStream()).length,Matchers.greaterThan(0));
        assertThat(IO.readBytes(resource.getInputStream()).length,is((int)resource.length()));
        assertThat(resource.getWeakETag("-xxx"),startsWith("W/\""));
        assertThat(resource.getWeakETag("-xxx"),endsWith("-xxx\""));
    }

    @Test
    public void testResourceFromURLForString()
            throws Exception
    {
        Assume.assumeTrue(JDK.IS_9);

        URI string_loc = TypeUtil.getLocationOfClass(String.class);
        Resource resource = Resource.newResource(string_loc.toURL());

        assertThat(resource.exists(), is(true));
        assertThat(resource.isDirectory(), is(false));
        assertThat(IO.readBytes(resource.getInputStream()).length,Matchers.greaterThan(0));
        assertThat(IO.readBytes(resource.getInputStream()).length,is((int)resource.length()));
        assertThat(resource.getWeakETag("-xxx"),startsWith("W/\""));
        assertThat(resource.getWeakETag("-xxx"),endsWith("-xxx\""));
    }


    @Test
    public void testResourceModule()
            throws Exception
    {
        Assume.assumeTrue(JDK.IS_9);

        Resource resource = Resource.newResource("jrt:/java.base");

        assertThat(resource.exists(), is(false));
        assertThat(resource.isDirectory(), is(false));
        assertThat(resource.length(),is(-1L));
    }

    @Test
    public void testResourceAllModules()
            throws Exception
    {
        Assume.assumeTrue(JDK.IS_9);

        Resource resource = Resource.newResource("jrt:/");

        assertThat(resource.exists(), is(false));
        assertThat(resource.isDirectory(), is(false));
        assertThat(resource.length(),is(-1L));
    }



}
