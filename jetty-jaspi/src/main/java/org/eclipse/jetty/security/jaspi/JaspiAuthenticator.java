//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.security.jaspi;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import java.util.Set;
import javax.security.auth.Subject;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.AuthStatus;
import jakarta.security.auth.message.callback.CallerPrincipalCallback;
import jakarta.security.auth.message.callback.GroupPrincipalCallback;
import jakarta.security.auth.message.config.ServerAuthConfig;
import jakarta.security.auth.message.config.ServerAuthContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.UserAuthentication;
import org.eclipse.jetty.security.authentication.DeferredAuthentication;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.security.authentication.SessionAuthentication;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.Authentication.User;
import org.eclipse.jetty.server.UserIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of Jetty {@link LoginAuthenticator} that is a bridge from Jakarta Authentication to Jetty Security.
 * 
 * @version $Rev: 4793 $ $Date: 2009-03-19 00:00:01 +0100 (Thu, 19 Mar 2009) $
 */
public class JaspiAuthenticator extends LoginAuthenticator
{
    private static final Logger LOG = LoggerFactory.getLogger(JaspiAuthenticator.class.getName());

    private final ServerAuthConfig _authConfig;

    private final Map _authProperties;

    private final ServletCallbackHandler _callbackHandler;

    private final Subject _serviceSubject;

    private final boolean _allowLazyAuthentication;

    private final IdentityService _identityService;

    public JaspiAuthenticator(ServerAuthConfig authConfig, Map authProperties, ServletCallbackHandler callbackHandler, Subject serviceSubject,
                              boolean allowLazyAuthentication, IdentityService identityService)
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
    }

    @Override
    public void setConfiguration(AuthConfiguration configuration)
    {
        super.setConfiguration(configuration);
    }

    @Override
    public String getAuthMethod()
    {
        return "JASPI";
    }

    @Override
    public UserIdentity login(String username, Object password, ServletRequest request)
    {
        UserIdentity user = _loginService.login(username, password, request);
        if (user != null)
        {
            renewSession((HttpServletRequest)request, null);
            HttpSession session = ((HttpServletRequest)request).getSession(true);
            if (session != null)
            {
                SessionAuthentication sessionAuth = new SessionAuthentication(getAuthMethod(), user, password);
                session.setAttribute(SessionAuthentication.__J_AUTHENTICATED, sessionAuth);
            }
        }
        return user;
    }

    @Override
    public Authentication validateRequest(ServletRequest request, ServletResponse response, boolean mandatory) throws ServerAuthException
    {
        JaspiMessageInfo info = new JaspiMessageInfo(request, response, mandatory);
        request.setAttribute("org.eclipse.jetty.security.jaspi.info", info);

        Authentication a = validateRequest(info);

        //if its not mandatory to authenticate, and the authenticator returned UNAUTHENTICATED, we treat it as authentication deferred
        if (_allowLazyAuthentication && !info.isAuthMandatory() && a == Authentication.UNAUTHENTICATED)
            a = new DeferredAuthentication(this);
        return a;
    }

    public Authentication validateRequest(JaspiMessageInfo messageInfo) throws ServerAuthException
    {
        try
        {
            String authContextId = _authConfig.getAuthContextID(messageInfo);
            ServerAuthContext authContext = _authConfig.getAuthContext(authContextId, _serviceSubject, _authProperties);
            Subject clientSubject = new Subject();

            AuthStatus authStatus = authContext.validateRequest(messageInfo, clientSubject, _serviceSubject);

            if (authStatus == AuthStatus.SEND_CONTINUE)
                return Authentication.SEND_CONTINUE;
            if (authStatus == AuthStatus.SEND_FAILURE)
                return Authentication.SEND_FAILURE;

            if (authStatus == AuthStatus.SUCCESS)
            {
                Set<UserIdentity> ids = clientSubject.getPrivateCredentials(UserIdentity.class);
                UserIdentity userIdentity;
                if (ids.size() > 0)
                {
                    userIdentity = ids.iterator().next();
                }
                else
                {
                    CallerPrincipalCallback principalCallback = _callbackHandler.getThreadCallerPrincipalCallback();
                    if (principalCallback == null)
                    {
                        return Authentication.UNAUTHENTICATED;
                    }
                    Principal principal = principalCallback.getPrincipal();
                    if (principal == null)
                    {
                        String principalName = principalCallback.getName();
                        Set<Principal> principals = principalCallback.getSubject().getPrincipals();
                        for (Principal p : principals)
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

                HttpSession session = ((HttpServletRequest)messageInfo.getRequestMessage()).getSession(false);
                Authentication cached = (session == null ? null : (SessionAuthentication)session.getAttribute(SessionAuthentication.__J_AUTHENTICATED));
                if (cached != null)
                    return cached;

                return new UserAuthentication(getAuthMethod(), userIdentity);
            }
            if (authStatus == AuthStatus.SEND_SUCCESS)
            {
                // we are processing a message in a secureResponse dialog.
                return Authentication.SEND_SUCCESS;
            }
            if (authStatus == AuthStatus.FAILURE)
            {
                HttpServletResponse response = (HttpServletResponse)messageInfo.getResponseMessage();
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return Authentication.SEND_FAILURE;
            }
            // should not happen
            throw new IllegalStateException("No AuthStatus returned");
        }
        catch (IOException | AuthException e)
        {
            throw new ServerAuthException(e);
        }
    }

    // most likely validatedUser is not needed here.
    @Override
    public boolean secureResponse(ServletRequest req, ServletResponse res, boolean mandatory, User validatedUser) throws ServerAuthException
    {
        JaspiMessageInfo info = (JaspiMessageInfo)req.getAttribute("org.eclipse.jetty.security.jaspi.info");
        if (info == null)
            throw new NullPointerException("MessageInfo from request missing: " + req);
        return secureResponse(info, validatedUser);
    }

    public boolean secureResponse(JaspiMessageInfo messageInfo, Authentication validatedUser) throws ServerAuthException
    {
        try
        {
            String authContextId = _authConfig.getAuthContextID(messageInfo);
            ServerAuthContext authContext = _authConfig.getAuthContext(authContextId, _serviceSubject, _authProperties);
            // TODO
            // authContext.cleanSubject(messageInfo,validatedUser.getUserIdentity().getSubject());
            AuthStatus status = authContext.secureResponse(messageInfo, _serviceSubject);
            return (AuthStatus.SEND_SUCCESS.equals(status));
        }
        catch (AuthException e)
        {
            throw new ServerAuthException(e);
        }
    }
}
