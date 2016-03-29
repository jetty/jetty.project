//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.security;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.util.security.Credential;

/**
 * TestLoginService
 *
 *
 */
public class TestLoginService extends AbstractLoginService
{
    protected Map<String, UserPrincipal> _users = new HashMap<>();
    protected Map<String, String[]> _roles = new HashMap<>();
 


    public TestLoginService(String name)
    {
        setName(name);
    }

    public void putUser (String username, Credential credential, String[] roles)
    {
        UserPrincipal userPrincipal = new UserPrincipal(username,credential);
        _users.put(username, userPrincipal);
        _roles.put(username, roles);
    }
    
    /** 
     * @see org.eclipse.jetty.security.AbstractLoginService#loadRoleInfo(org.eclipse.jetty.security.AbstractLoginService.UserPrincipal)
     */
    @Override
    protected String[] loadRoleInfo(UserPrincipal user)
    {
       return _roles.get(user.getName());
    }

    /** 
     * @see org.eclipse.jetty.security.AbstractLoginService#loadUserInfo(java.lang.String)
     */
    @Override
    protected UserPrincipal loadUserInfo(String username)
    {
        return _users.get(username);
    }

}
