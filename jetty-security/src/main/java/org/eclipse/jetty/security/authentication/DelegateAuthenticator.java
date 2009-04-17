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

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.Authentication.User;

public class DelegateAuthenticator implements Authenticator
{
    protected final Authenticator _delegate;

    public void setConfiguration(Configuration configuration)
    {
        _delegate.setConfiguration(configuration);
    }

    public String getAuthMethod()
    {
        return _delegate.getAuthMethod();
    }
    
    public DelegateAuthenticator(Authenticator delegate)
    {
        _delegate=delegate;
    }

    public Authenticator getDelegate()
    {
        return _delegate;
    }

    public Authentication validateRequest(ServletRequest request, ServletResponse response, boolean manditory) throws ServerAuthException
    {
        return _delegate.validateRequest(request, response, manditory);
    }

    public boolean secureResponse(ServletRequest req, ServletResponse res, boolean mandatory, User validatedUser) throws ServerAuthException
    {
        return _delegate.secureResponse(req,res, mandatory, validatedUser);
    }
    
}
