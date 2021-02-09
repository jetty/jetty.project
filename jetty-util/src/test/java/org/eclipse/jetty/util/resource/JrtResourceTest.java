//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.TypeUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

public class JrtResourceTest
{
    @Test
    @DisabledOnJre(JRE.JAVA_8)
    @Disabled("Not supported on Java 9+ Module API")
    public void testResourceFromUriForString()
        throws Exception
    {
        URI stringLoc = TypeUtil.getLocationOfClass(String.class);
        Resource resource = Resource.newResource(stringLoc);

        assertThat(resource.exists(), is(true));
        assertThat(resource.isDirectory(), is(false));
        assertThat(IO.readBytes(resource.getInputStream()).length, Matchers.greaterThan(0));
        assertThat(IO.readBytes(resource.getInputStream()).length, is((int)resource.length()));
        assertThat(resource.getWeakETag("-xxx"), startsWith("W/\""));
        assertThat(resource.getWeakETag("-xxx"), endsWith("-xxx\""));
    }

    @Test
    @DisabledOnJre(JRE.JAVA_8)
    @Disabled("Not supported on Java 9+ Module API")
    public void testResourceFromStringForString()
        throws Exception
    {
        URI stringLoc = TypeUtil.getLocationOfClass(String.class);
        Resource resource = Resource.newResource(stringLoc.toASCIIString());

        assertThat(resource.exists(), is(true));
        assertThat(resource.isDirectory(), is(false));
        assertThat(IO.readBytes(resource.getInputStream()).length, Matchers.greaterThan(0));
        assertThat(IO.readBytes(resource.getInputStream()).length, is((int)resource.length()));
        assertThat(resource.getWeakETag("-xxx"), startsWith("W/\""));
        assertThat(resource.getWeakETag("-xxx"), endsWith("-xxx\""));
    }

    @Test
    @DisabledOnJre(JRE.JAVA_8)
    @Disabled("Not supported on Java 9+ Module API")
    public void testResourceFromURLForString()
        throws Exception
    {
        URI stringLoc = TypeUtil.getLocationOfClass(String.class);
        Resource resource = Resource.newResource(stringLoc.toURL());

        assertThat(resource.exists(), is(true));
        assertThat(resource.isDirectory(), is(false));
        assertThat(IO.readBytes(resource.getInputStream()).length, Matchers.greaterThan(0));
        assertThat(IO.readBytes(resource.getInputStream()).length, is((int)resource.length()));
        assertThat(resource.getWeakETag("-xxx"), startsWith("W/\""));
        assertThat(resource.getWeakETag("-xxx"), endsWith("-xxx\""));
    }

    @Test
    @DisabledOnJre(JRE.JAVA_8)
    public void testResourceModule()
        throws Exception
    {
        Resource resource = Resource.newResource("jrt:/java.base");

        assertThat(resource.exists(), is(false));
        assertThat(resource.isDirectory(), is(false));
        assertThat(resource.length(), is(-1L));
    }

    @Test
    @DisabledOnJre(JRE.JAVA_8)
    public void testResourceAllModules()
        throws Exception
    {
        Resource resource = Resource.newResource("jrt:/");

        assertThat(resource.exists(), is(false));
        assertThat(resource.isDirectory(), is(false));
        assertThat(resource.length(), is(-1L));
    }
}
