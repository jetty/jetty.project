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

package org.eclipse.jetty.security.authentication;

import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;

public abstract class LoginAuthenticator implements Authenticator
{
    protected final DeferredAuthentication _deferred=new DeferredAuthentication(this);
    protected LoginService _loginService;
    protected IdentityService _identityService;

    protected LoginAuthenticator()
    {
    }

    public void setConfiguration(Configuration configuration)
    {
        _loginService=configuration.getLoginService();
        if (_loginService==null)
            throw new IllegalStateException("No LoginService for "+this+" in "+configuration);
        _identityService=configuration.getIdentityService();
        if (_identityService==null)
            throw new IllegalStateException("No IdentityService for "+this+" in "+configuration);
    }
    
    public LoginService getLoginService()
    {
        return _loginService;
    }
}
