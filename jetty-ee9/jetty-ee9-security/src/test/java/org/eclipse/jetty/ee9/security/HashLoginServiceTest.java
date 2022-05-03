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

package org.eclipse.jetty.security;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the HashLoginService.
 */
public class HashLoginServiceTest
{
    @Test
    public void testAutoCreatedUserStore() throws Exception
    {
        HashLoginService loginService = new HashLoginService("foo", MavenTestingUtils.getTestResourceFile("foo.properties").getAbsolutePath());
        assertThat(loginService.getIdentityService(), is(notNullValue()));
        loginService.start();
        assertTrue(loginService.getUserStore().isStarted());
        assertTrue(loginService.isUserStoreAutoCreate());

        loginService.stop();
        assertFalse(loginService.isUserStoreAutoCreate());
        assertThat(loginService.getUserStore(), is(nullValue()));
    }

    @Test
    public void testProvidedUserStore() throws Exception
    {
        HashLoginService loginService = new HashLoginService("foo");
        assertThat(loginService.getIdentityService(), is(notNullValue()));
        UserStore store = new UserStore();
        loginService.setUserStore(store);
        assertFalse(store.isStarted());
        loginService.start();
        assertTrue(loginService.getUserStore().isStarted());
        assertFalse(loginService.isUserStoreAutoCreate());

        loginService.stop();

        assertFalse(loginService.isUserStoreAutoCreate());
        assertFalse(store.isStarted());
        assertThat(loginService.getUserStore(), is(notNullValue()));
    }
}
