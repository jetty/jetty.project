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

import javax.security.auth.Subject;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.eclipse.jetty.server.UserIdentity;


/**
 * @version $Rev: 4793 $ $Date: 2009-03-19 00:00:01 +0100 (Thu, 19 Mar 2009) $
 */
public class LazyAuthentication implements Authentication
{
    private static final Subject unauthenticatedSubject = new Subject();

    private final Authenticator _serverAuthentication;
    private final ServletRequest _request;
    private final ServletResponse _response;

    private Authentication _delegate;

    public LazyAuthentication(Authenticator serverAuthentication, ServletRequest request, ServletResponse response)
    {
        if (serverAuthentication == null) throw new NullPointerException("No ServerAuthentication");
        this._serverAuthentication = serverAuthentication;
        this._request=request;
        this._response=response;   
    }

    private Authentication getDelegate()
    {
        if (_delegate == null)
        {
            try
            {
                _delegate = _serverAuthentication.validateRequest(_request, _response, false);
            }
            catch (ServerAuthException e)
            {
                _delegate = DefaultAuthentication.SEND_FAILURE_RESULTS;
            }
        }
        return _delegate;
    }

    public Authentication.Status getAuthStatus()
    {
        return getDelegate().getAuthStatus();
    }

    public boolean isSuccess()
    {
        return getDelegate().isSuccess();
    }
    
    // for cleaning in secureResponse
    public UserIdentity getUserIdentity()
    {
        return _delegate == null ? UserIdentity.UNAUTHENTICATED_IDENTITY: _delegate.getUserIdentity();
    }

    public String getAuthMethod()
    {
        return getDelegate().getAuthMethod();
    }

    public String toString()
    {
        return "{Lazy,"+_delegate+"}";
    }
}
