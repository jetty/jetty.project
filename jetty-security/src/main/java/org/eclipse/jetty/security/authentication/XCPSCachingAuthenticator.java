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
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.security.Authentication;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.CrossContextPsuedoSession;
import org.eclipse.jetty.security.ServerAuthException;

/**
 * Cross-context psuedo-session caching ServerAuthentication
 * 
 * @version $Rev: 4793 $ $Date: 2009-03-19 00:00:01 +0100 (Thu, 19 Mar 2009) $
 */
public class XCPSCachingAuthenticator extends DelegateAuthenticator
{
    public final static String __J_AUTHENTICATED = "org.eclipse.jetty.server.Auth";

    private final CrossContextPsuedoSession<Authentication> _xcps;

    public XCPSCachingAuthenticator(Authenticator delegate, CrossContextPsuedoSession<Authentication> xcps)
    {
        super(delegate);
        this._xcps = xcps;
    }

    public Authentication validateRequest(ServletRequest request, ServletResponse response, boolean manditory) throws ServerAuthException
    {

        Authentication serverAuthResult = _xcps.fetch((HttpServletRequest)request);
        if (serverAuthResult != null) 
            return serverAuthResult;

        serverAuthResult = _delegate.validateRequest(request, response, manditory);
        if (serverAuthResult != null) 
            _xcps.store(serverAuthResult, (HttpServletResponse)response);

        return serverAuthResult;
    }

}
