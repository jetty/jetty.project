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

package org.eclipse.jetty.util.resource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

public class JrtResourceTest
{
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
    public void testResourceModule()
        throws Exception
    {
        Resource resource = Resource.newResource("jrt:/java.base");

        assertThat(resource.exists(), is(true));
        assertThat(resource.isDirectory(), is(true));
        assertThat(resource.length(), is(0L));
    }

    @Test
    public void testResourceAllModules()
        throws Exception
    {
        Resource resource = Resource.newResource("jrt:/");

        assertThat(resource.exists(), is(true));
        assertThat(resource.isDirectory(), is(true));
        assertThat(resource.length(), is(0L));
    }
}
