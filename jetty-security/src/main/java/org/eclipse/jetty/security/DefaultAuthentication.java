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

import org.eclipse.jetty.security.authentication.DelegateAuthenticator;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.UserIdentity;


/**
 * @version $Rev: 4793 $ $Date: 2009-03-19 00:00:01 +0100 (Thu, 19 Mar 2009) $
 */
public class DefaultAuthentication implements Authentication
{
    private final Authenticator _authenticator;
    private final UserIdentity _userIdentity;

    public DefaultAuthentication(Authenticator authenticator, UserIdentity userIdentity)
    {
        _authenticator = authenticator;
        _userIdentity=userIdentity;
    }

    public String getAuthMethod()
    {
        return _authenticator.getAuthMethod();
    }

    public UserIdentity getUserIdentity()
    {
        return _userIdentity;
    }

    public boolean isSuccess()
    {
        return true;
    }

    public boolean isSend()
    {
        return false;
    }
    
    public void logout() 
    {    
        Authenticator authenticator = _authenticator;
        while (true)
        {
            if (authenticator instanceof LoginAuthenticator)
            {
                ((LoginAuthenticator)authenticator).getLoginService().logout(this.getUserIdentity());
                break;
            }
            else if (authenticator instanceof DelegateAuthenticator)
            {
                authenticator=((DelegateAuthenticator)authenticator).getDelegate();
            }
            else
                break;
        }
    }
    
    public String toString()
    {
        return "{Auth,"+getAuthMethod()+","+_userIdentity+"}";
    }
    
    public static class Send extends DefaultAuthentication
    {
        public Send(Authenticator authenticator, UserIdentity userIdentity)
        {
            super(authenticator,userIdentity);
        }

        public boolean isSend()
        {
            return true;
        }
    }
}
