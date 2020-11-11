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

package org.eclipse.jetty.jaas.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.security.UserPrincipal;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.util.thread.AutoLock;

/**
 * User
 *
 * Authentication information about a user. Also allows for
 * lazy loading of authorization (role) information for the user.
 *
 */
public class User
{
    private final AutoLock _lock = new AutoLock();
    protected UserPrincipal _userPrincipal;
    protected List<String> _roleNames = new ArrayList<>();
    protected boolean _rolesLoaded = false;

    /**
     * @param userPrincipal
     * @param roleNames
     */
    public User(UserPrincipal userPrincipal, List<String> roleNames)
    {
        _userPrincipal = userPrincipal;
        if (roleNames != null)
        {
            _roleNames.addAll(roleNames);
            _rolesLoaded = true;
        }
    }

    /**
     * @param userPrincipal
     */
    public User(UserPrincipal userPrincipal)
    {
        this(userPrincipal, null);
    }

    /**
     * Should be overridden by subclasses to obtain
     * role info
     *
     * @return List of role associated to the user
     * @throws Exception if the roles cannot be retrieved
     */
    public List<String> doFetchRoles()
        throws Exception
    {
        return Collections.emptyList();
    }

    public void fetchRoles() throws Exception
    {
        try (AutoLock l = _lock.lock())
        {
            if (!_rolesLoaded)
            {
                _roleNames.addAll(doFetchRoles());
                _rolesLoaded = true;
            }
        }
    }

    public String getUserName()
    {
        return (_userPrincipal == null ? null : _userPrincipal.getName());
    }
    
    public UserPrincipal getUserPrincipal()
    {
        return _userPrincipal;
    }

    public List<String> getRoleNames()
    {
        return Collections.unmodifiableList(_roleNames);
    }

    public boolean checkCredential(Object suppliedCredential)
    {
        return _userPrincipal.authenticate(suppliedCredential);
    }
}
