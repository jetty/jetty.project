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

import java.util.Set;

import javax.servlet.ServletContext;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.eclipse.jetty.server.Server;

/**
 * This is like the JASPI ServerAuthContext but is intended to be easier to use
 * and allow lazy auth.
 * 
 * @version $Rev: 4793 $ $Date: 2009-03-19 00:00:01 +0100 (Thu, 19 Mar 2009) $
 */
public interface Authenticator
{
    void setConfiguration(Configuration configuration);
    String getAuthMethod();
    
    Authentication validateRequest(ServletRequest request, ServletResponse response, boolean mandatory) throws ServerAuthException;
    boolean secureResponse(ServletRequest request, ServletResponse response, boolean mandatory, Authentication validatedUser) throws ServerAuthException;
    
    interface Configuration
    {
        String getAuthMethod();
        String getRealmName();
        boolean isLazy();
        String getInitParameter(String key);
        Set<String> getInitParameterNames();
        LoginService getLoginService();
        IdentityService getIdentityService();
    }
    
    interface Factory
    {
        Authenticator getAuthenticator(Server server, ServletContext context, Configuration configuration);
    }
}
