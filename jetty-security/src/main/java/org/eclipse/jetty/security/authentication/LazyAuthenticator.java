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

import org.eclipse.jetty.security.Authentication;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.LazyAuthentication;
import org.eclipse.jetty.security.ServerAuthException;

/**
 * @version $Rev: 4793 $ $Date: 2009-03-19 00:00:01 +0100 (Thu, 19 Mar 2009) $
 */
public class LazyAuthenticator extends DelegateAuthenticator 
{
    public LazyAuthenticator(Authenticator delegate)
    {
        super(delegate);
    }

    /** 
     * @see org.eclipse.jetty.security.Authenticator#validateRequest(ServletRequest, ServletResponse, boolean)
     */
    public Authentication validateRequest(ServletRequest request, ServletResponse response, boolean mandatory) throws ServerAuthException
    {
        if (!mandatory)
        { 
            return new LazyAuthentication(_delegate,request,response);
        }
        return _delegate.validateRequest(request, response, mandatory);
    }
}
