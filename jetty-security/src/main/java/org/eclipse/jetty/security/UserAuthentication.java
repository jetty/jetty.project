//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.server.UserIdentity.Scope;


/**
 * @version $Rev: 4793 $ $Date: 2009-03-19 00:00:01 +0100 (Thu, 19 Mar 2009) $
 */
public class UserAuthentication implements Authentication.User
{
    private final String _method;
    private final UserIdentity _userIdentity;

    public UserAuthentication(String method, UserIdentity userIdentity)
    {
        _method = method;
        _userIdentity = userIdentity;
    }
    
    public String getAuthMethod()
    {
        return _method;
    }

    public UserIdentity getUserIdentity()
    {
        return _userIdentity;
    }

    public boolean isUserInRole(Scope scope, String role)
    {
        return _userIdentity.isUserInRole(role, scope);
    }
    
    @Override
    public String toString()
    {
        return "{User,"+getAuthMethod()+","+_userIdentity+"}";
    }

    public void logout()
    {
        SecurityHandler security=SecurityHandler.getCurrentSecurityHandler();
        if (security!=null)
            security.logout(this);
    }
}
