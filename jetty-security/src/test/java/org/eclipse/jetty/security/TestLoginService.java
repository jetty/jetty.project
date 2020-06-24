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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.server.UserIdentity;
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
    protected String[] loadRoleInfo(UserPrincipal user)
    {
        UserIdentity userIdentity = userStore.getUserIdentity(user.getName());
        Set<RolePrincipal> roles = userIdentity.getSubject().getPrincipals(RolePrincipal.class);
        if (roles == null)
            return null;

        List<String> list = new ArrayList<>();
        for (RolePrincipal r : roles)
        {
            list.add(r.getName());
        }

        return list.toArray(new String[roles.size()]);
    }

    @Override
    protected UserPrincipal loadUserInfo(String username)
    {
        UserIdentity userIdentity = userStore.getUserIdentity(username);
        return userIdentity == null ? null : (UserPrincipal)userIdentity.getUserPrincipal();
    }
}
