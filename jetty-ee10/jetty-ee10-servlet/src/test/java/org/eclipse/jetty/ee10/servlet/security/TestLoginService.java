//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.RolePrincipal;
import org.eclipse.jetty.security.UserPrincipal;
import org.eclipse.jetty.security.UserStore;
import org.eclipse.jetty.util.security.Credential;

/**
 * TestLoginService
 */
public class TestLoginService extends AbstractLoginService
{

    UserStore userStore = new UserStore();

    public TestLoginService(String name)
    {
        setName(name);
    }

    public void putUser(String username, Credential credential, String[] roles)
    {
        userStore.addUser(username, credential, roles);
    }

    @Override
    protected List<RolePrincipal> loadRoleInfo(UserPrincipal user)
    {
        return userStore.getRolePrincipals(user.getName());
    }

    @Override
    protected UserPrincipal loadUserInfo(String username)
    {
        return userStore.getUserPrincipal(username);
    }
}
