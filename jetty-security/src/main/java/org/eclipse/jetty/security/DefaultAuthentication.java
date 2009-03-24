// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.security;

import org.eclipse.jetty.server.UserIdentity;


/**
 * @version $Rev: 4793 $ $Date: 2009-03-19 00:00:01 +0100 (Thu, 19 Mar 2009) $
 */
public class DefaultAuthentication implements Authentication
{
    private final Authentication.Status _authStatus;
    private final String _authMethod;
    private final UserIdentity _userIdentity;

    public DefaultAuthentication(Authentication.Status authStatus, String authMethod, UserIdentity userIdentity)
    {
        _authStatus = authStatus;
        _authMethod = authMethod;
        _userIdentity=userIdentity;
    }

    public String getAuthMethod()
    {
        return _authMethod;
    }
    
    public Authentication.Status getAuthStatus()
    {
        return _authStatus;
    }

    public UserIdentity getUserIdentity()
    {
        return _userIdentity;
    }

    public boolean isSuccess()
    {
        return _authStatus.isSuccess();
    }
    
    public String toString()
    {
        return "{Auth,"+_authMethod+","+_authStatus+","+","+_userIdentity+"}";
    }
}
