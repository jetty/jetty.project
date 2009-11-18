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

import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.UserAuthentication;
import org.eclipse.jetty.security.authentication.DeferredAuthentication;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.server.Authentication.User;

/**
 * @version $Rev: 4793 $ $Date: 2009-03-19 00:00:01 +0100 (Thu, 19 Mar 2009) $
 */
public class JaspiAuthenticator implements Authenticator
{
    private final ServerAuthConfig _authConfig;
    private final Map _authProperties;
    private final ServletCallbackHandler _callbackHandler;
    private final Subject _serviceSubject;
    private final boolean _allowLazyAuthentication;
    private final IdentityService _identityService;
    private final DeferredAuthentication _deferred;

    public JaspiAuthenticator(ServerAuthConfig authConfig, Map authProperties, ServletCallbackHandler callbackHandler,
                              Subject serviceSubject, boolean allowLazyAuthentication, IdentityService identityService)
    {
        // TODO maybe pass this in via setConfiguration ?
        if (callbackHandler == null)
            throw new NullPointerException("No CallbackHandler");
        if (authConfig == null)
            throw new NullPointerException("No AuthConfig");
        this._authConfig = authConfig;
        this._authProperties = authProperties;
        this._callbackHandler = callbackHandler;
        this._serviceSubject = serviceSubject;
        this._allowLazyAuthentication = allowLazyAuthentication;
        this._identityService = identityService;
        this._deferred=new DeferredAuthentication(this);
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
            return _deferred;
        
        JaspiMessageInfo info = new JaspiMessageInfo(request, response, mandatory);
        request.setAttribute("org.eclipse.jetty.security.jaspi.info",info);
        return validateRequest(info);
    }

    // most likely validatedUser is not needed here.
    public boolean secureResponse(ServletRequest req, ServletResponse res, boolean mandatory, User validatedUser) throws ServerAuthException
    {
        JaspiMessageInfo info = (JaspiMessageInfo)req.getAttribute("org.eclipse.jetty.security.jaspi.info");
        if (info==null) throw new NullPointerException("MeesageInfo from request missing: " + req);
        return secureResponse(info,validatedUser);
    }
    
    public Authentication validateRequest(JaspiMessageInfo messageInfo) throws ServerAuthException
    {
        try
        {
            String authContextId = _authConfig.getAuthContextID(messageInfo);
            ServerAuthContext authContext = _authConfig.getAuthContext(authContextId,_serviceSubject,_authProperties);
            Subject clientSubject = new Subject();

            AuthStatus authStatus = authContext.validateRequest(messageInfo,clientSubject,_serviceSubject);
//            String authMethod = (String)messageInfo.getMap().get(JaspiMessageInfo.AUTH_METHOD_KEY);

            if (authStatus == AuthStatus.SEND_CONTINUE)
                return Authentication.SEND_CONTINUE;
            if (authStatus == AuthStatus.SEND_FAILURE)
                return Authentication.SEND_FAILURE;
            
            if (authStatus == AuthStatus.SUCCESS)
            {
            Set<UserIdentity> ids = clientSubject.getPrivateCredentials(UserIdentity.class);
                UserIdentity userIdentity;
                if (ids.size() > 0) {
                    userIdentity = ids.iterator().next();
//                    return new FormAuthenticator.FormAuthentication(this,ids.iterator().next());
                } else {
                    CallerPrincipalCallback principalCallback = _callbackHandler.getThreadCallerPrincipalCallback();
                    if (principalCallback == null) throw new NullPointerException("No CallerPrincipalCallback");
                    Principal principal = principalCallback.getPrincipal();
                    if (principal == null) {
                        String principalName = principalCallback.getName();
                        Set<Principal> principals = principalCallback.getSubject().getPrincipals();
                        for (Principal p: principals)
                        {
                            if (p.getName().equals(principalName))
                            {
                                principal = p;
                                break;
                            }
                        }
                        if (principal == null)
                        {
                            return Authentication.UNAUTHENTICATED;
                        }
                    }
                    GroupPrincipalCallback groupPrincipalCallback = _callbackHandler.getThreadGroupPrincipalCallback();
                    String[] groups = groupPrincipalCallback == null ? null : groupPrincipalCallback.getGroups();
                    userIdentity = _identityService.newUserIdentity(clientSubject, principal, groups);
                }
                return new UserAuthentication(this, userIdentity);
            }
            if (authStatus == AuthStatus.SEND_SUCCESS)
            {
                //we are processing a message in a secureResponse dialog.
                return Authentication.SEND_SUCCESS;
            }
            //should not happen
            throw new NullPointerException("No AuthStatus returned");
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
            String authContextId = _authConfig.getAuthContextID(messageInfo);
            ServerAuthContext authContext = _authConfig.getAuthContext(authContextId,_serviceSubject,_authProperties);
            // TODO authContext.cleanSubject(messageInfo,validatedUser.getUserIdentity().getSubject());
            AuthStatus status = authContext.secureResponse(messageInfo,_serviceSubject);
            return (AuthStatus.SEND_SUCCESS.equals(status));
        }
        catch (AuthException e)
        {
            throw new ServerAuthException(e);
        }
    }

}
