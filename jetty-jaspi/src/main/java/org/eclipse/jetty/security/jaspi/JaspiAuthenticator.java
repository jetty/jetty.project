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

package org.eclipse.jetty.security.jaspi;

import java.security.Principal;
import java.util.Map;
import java.util.Set;

import javax.security.auth.Subject;
import javax.security.auth.message.AuthException;
import javax.security.auth.message.AuthStatus;
import javax.security.auth.message.callback.CallerPrincipalCallback;
import javax.security.auth.message.callback.GroupPrincipalCallback;
import javax.security.auth.message.config.ServerAuthConfig;
import javax.security.auth.message.config.ServerAuthContext;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.UserAuthentication;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.authentication.FormAuthenticator;
import org.eclipse.jetty.security.authentication.DeferredAuthenticator;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.server.Authentication.User;

/**
 * @version $Rev: 4793 $ $Date: 2009-03-19 00:00:01 +0100 (Thu, 19 Mar 2009) $
 */
public class JaspiAuthenticator implements Authenticator
{
    private final String _authContextId;
    private final ServerAuthConfig _authConfig;
    private final Map _authProperties;
    private final ServletCallbackHandler _callbackHandler;
    private final Subject _serviceSubject;
    private final boolean _allowLazyAuthentication;

    
    public JaspiAuthenticator(String authContextId, ServerAuthConfig authConfig, Map authProperties, ServletCallbackHandler callbackHandler,
            Subject serviceSubject, boolean allowLazyAuthentication)
    {
        // TODO maybe pass this in via setConfiguration ?
        if (callbackHandler == null)
            throw new NullPointerException("No CallbackHandler");
        if (authConfig == null)
            throw new NullPointerException("No AuthConfig");
        this._authContextId = authContextId;
        this._authConfig = authConfig;
        this._authProperties = authProperties;
        this._callbackHandler = callbackHandler;
        this._serviceSubject = serviceSubject;
        this._allowLazyAuthentication = allowLazyAuthentication;
    }


    public void setConfiguration(Configuration configuration)
    {
    }
    
    
    public String getAuthMethod()
    {
        return "JASPI";
    }

    public Authentication validateRequest(ServletRequest request, ServletResponse response, boolean mandatory) throws ServerAuthException
    {
        if (_allowLazyAuthentication && !mandatory)
            return new DeferredAuthenticator.DeferredAuthentication(this,request,response);
        
        JaspiMessageInfo info = new JaspiMessageInfo((HttpServletRequest)request,(HttpServletResponse)response,mandatory);
        request.setAttribute("org.eclipse.jetty.security.jaspi.info",info);
        return validateRequest(info);
    }

    // most likely validatedUser is not needed here.
    public boolean secureResponse(ServletRequest req, ServletResponse res, boolean mandatory, User validatedUser) throws ServerAuthException
    {
        JaspiMessageInfo info = (JaspiMessageInfo)req.getAttribute("org.eclipse.jetty.security.jaspi.info");
        if (info==null)
            info = new JaspiMessageInfo((HttpServletRequest)req,(HttpServletResponse)res,mandatory);
        return secureResponse(info,validatedUser);
    }
    
    public Authentication validateRequest(JaspiMessageInfo messageInfo) throws ServerAuthException
    {
        try
        {
            ServerAuthContext authContext = _authConfig.getAuthContext(_authContextId,_serviceSubject,_authProperties);
            Subject clientSubject = new Subject();

            AuthStatus authStatus = authContext.validateRequest(messageInfo,clientSubject,_serviceSubject);
            String authMethod = (String)messageInfo.getMap().get(JaspiMessageInfo.AUTH_METHOD_KEY);
            CallerPrincipalCallback principalCallback = _callbackHandler.getThreadCallerPrincipalCallback();
            Principal principal = principalCallback == null?null:principalCallback.getPrincipal();
            GroupPrincipalCallback groupPrincipalCallback = _callbackHandler.getThreadGroupPrincipalCallback();
            String[] groups = groupPrincipalCallback == null?null:groupPrincipalCallback.getGroups();
            

            if (authStatus == AuthStatus.SEND_CONTINUE)
                return Authentication.CHALLENGE;
            if (authStatus == AuthStatus.SEND_FAILURE)
                return Authentication.FAILURE;
            
            Set<UserIdentity> ids = clientSubject.getPrivateCredentials(UserIdentity.class);
            if (ids.size()>0)
            {
                if (authStatus == AuthStatus.SEND_SUCCESS)
                    return new FormAuthenticator.FormAuthentication(this,ids.iterator().next());
                return new UserAuthentication(this,ids.iterator().next());
            }
            return Authentication.FAILURE;
        }
        catch (AuthException e)
        {
            throw new ServerAuthException(e);
        }
    }

    public boolean secureResponse(JaspiMessageInfo messageInfo, Authentication validatedUser) throws ServerAuthException
    {
        try
        {
            ServerAuthContext authContext = _authConfig.getAuthContext(_authContextId,_serviceSubject,_authProperties);
            // TODO authContext.cleanSubject(messageInfo,validatedUser.getUserIdentity().getSubject());
            AuthStatus status = authContext.secureResponse(messageInfo,_serviceSubject);
            return (AuthStatus.SUCCESS.equals(status));
        }
        catch (AuthException e)
        {
            throw new ServerAuthException(e);
        }
    }

}
