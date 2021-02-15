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

package org.eclipse.jetty.jaas.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.util.security.Credential;

/**
 * UserInfo
 *
 * This is the information read from the external source
 * about a user.
 *
 * Can be cached.
 */
public class UserInfo
{

    private String _userName;
    private Credential _credential;
    protected List<String> _roleNames = new ArrayList<>();
    protected boolean _rolesLoaded = false;

    /**
     * @param userName the user name
     * @param credential the credential
     * @param roleNames a {@link List} of role name
     */
    public UserInfo(String userName, Credential credential, List<String> roleNames)
    {
        _userName = userName;
        _credential = credential;
        if (roleNames != null)
        {
            _roleNames.addAll(roleNames);
            _rolesLoaded = true;
        }
    }

    /**
     * @param userName the user name
     * @param credential the credential
     */
    public UserInfo(String userName, Credential credential)
    {
        this(userName, credential, null);
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
        synchronized (_roleNames)
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
        return this._userName;
    }

    public List<String> getRoleNames()
    {
        return Collections.unmodifiableList(_roleNames);
    }

    public boolean checkCredential(Object suppliedCredential)
    {
        return _credential.check(suppliedCredential);
    }

    protected Credential getCredential()
    {
        return _credential;
    }
}
