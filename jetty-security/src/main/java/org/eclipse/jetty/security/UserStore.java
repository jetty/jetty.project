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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.security.Credential;

/**
 * Store of user authentication and authorization information.
 * 
 */
public class UserStore extends AbstractLifeCycle
{
    protected final Map<String, User> _users = new ConcurrentHashMap<>();
    
    public void addUser(String username, Credential credential, String[] roles)
    {
        _users.put(username, new User(username, credential, roles));
    }

    public void removeUser(String username)
    {
        _users.remove(username);
    }
    
    public UserPrincipal getUserPrincipal(String username)
    {
        User user = _users.get(username);
        return (user == null ? null : user.getUserPrincipal());
    }
    
    public List<RolePrincipal> getRolePrincipals(String username)
    {
        User user = _users.get(username);
        return (user == null ? null : user.getRolePrincipals());
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[users.count=%d]", getClass().getSimpleName(), hashCode(), _users.size());
    }
}
