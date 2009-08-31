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

import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Authentication.User;

/**
 * Authenticator Interface
 * <p>
 * An Authenticator is responsible for checking requests and sending
 * response challenges in order to authenticate a request. 
 * Various types of {@link Authentication} are returned in order to
 * signal the next step in authentication.
 * 
 * @version $Rev: 4793 $ $Date: 2009-03-19 00:00:01 +0100 (Thu, 19 Mar 2009) $
 */
public interface Authenticator
{
    /* ------------------------------------------------------------ */
    /**
     * Configure the Authenticator
     * @param configuration
     */
    void setConfiguration(Configuration configuration);
    
    /* ------------------------------------------------------------ */
    /**
     * @return The name of the authentication method
     */
    String getAuthMethod();
    
    /* ------------------------------------------------------------ */
    /** Validate a response
     * @param request The request
     * @param response The response
     * @param mandatory True if authentication is mandatory.
     * @return An Authentication.  If Authentication is successful, this will be a {@link Authentication.User}. If a response has 
     * been sent by the Authenticator (which can be done for both successful and unsuccessful authentications), then the result will
     * implement {@link Authentication.ResponseSent}.  If Authentication is not manditory, then a {@link Authentication.Deferred} 
     * may be returned.
     * 
     * @throws ServerAuthException
     */
    Authentication validateRequest(ServletRequest request, ServletResponse response, boolean mandatory) throws ServerAuthException;
    
    /* ------------------------------------------------------------ */
    /**
     * @param request
     * @param response
     * @param mandatory
     * @param validatedUser
     * @return
     * @throws ServerAuthException
     */
    boolean secureResponse(ServletRequest request, ServletResponse response, boolean mandatory, User validatedUser) throws ServerAuthException;
    
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /** 
     * Authenticator Configuration
     */
    interface Configuration
    {
        String getAuthMethod();
        String getRealmName();
        String getInitParameter(String key);
        Set<String> getInitParameterNames();
        LoginService getLoginService();
        IdentityService getIdentityService();
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /** 
     * Authenticator Facotory
     */
    interface Factory
    {
        Authenticator getAuthenticator(Server server, ServletContext context, Configuration configuration, IdentityService identityService, LoginService loginService);
    }
}
