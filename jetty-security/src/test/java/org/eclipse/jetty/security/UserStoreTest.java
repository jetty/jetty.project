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

package org.eclipse.jetty.security;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.server.UserIdentity;
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
        this.userStore.addUser("foo", Credential.getCredential("beer"), new String[]{"pub"});
        assertEquals(1, this.userStore.getKnownUserIdentities().size());
        UserIdentity userIdentity = this.userStore.getUserIdentity("foo");
        assertNotNull(userIdentity);
        assertEquals("foo", userIdentity.getUserPrincipal().getName());
        Set<AbstractLoginService.RolePrincipal>
            roles = userIdentity.getSubject().getPrincipals(AbstractLoginService.RolePrincipal.class);
        List<String> list = roles.stream()
            .map(rolePrincipal -> rolePrincipal.getName())
            .collect(Collectors.toList());
        assertEquals(1, list.size());
        assertEquals("pub", list.get(0));
    }

    @Test
    public void removeUser()
    {
        this.userStore.addUser("foo", Credential.getCredential("beer"), new String[]{"pub"});
        assertEquals(1, this.userStore.getKnownUserIdentities().size());
        UserIdentity userIdentity = this.userStore.getUserIdentity("foo");
        assertNotNull(userIdentity);
        assertEquals("foo", userIdentity.getUserPrincipal().getName());
        userStore.removeUser("foo");
        userIdentity = this.userStore.getUserIdentity("foo");
        assertNull(userIdentity);
    }
}
