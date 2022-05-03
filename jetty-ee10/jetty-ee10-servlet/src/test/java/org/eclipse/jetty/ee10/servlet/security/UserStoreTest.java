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

package org.eclipse.jetty.ee10.servlet.security;

import java.util.List;

import org.eclipse.jetty.util.security.Credential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class UserStoreTest
{
    UserStore userStore;

    @BeforeEach
    public void setup()
    {
        userStore = new UserStore();
    }

    @Test
    public void addUser()
    {
        userStore.addUser("foo", Credential.getCredential("beer"), new String[]{"pub"});
        assertNotNull(userStore.getUserPrincipal("foo"));

        List<RolePrincipal> rps = userStore.getRolePrincipals("foo");
        assertNotNull(rps);
        assertNotNull(rps.get(0));
        assertEquals("pub", rps.get(0).getName());
    }

    @Test
    public void removeUser()
    {
        this.userStore.addUser("foo", Credential.getCredential("beer"), new String[]{"pub"});
        assertNotNull(userStore.getUserPrincipal("foo"));
        userStore.removeUser("foo");
        assertNull(userStore.getUserPrincipal("foo"));
    }
}
