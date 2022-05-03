//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.security.jaspi;

import java.io.IOException;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.security.auth.Subject;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.AuthStatus;
import jakarta.security.auth.message.callback.CallerPrincipalCallback;
import jakarta.security.auth.message.callback.GroupPrincipalCallback;
import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.AuthConfigProvider;
import jakarta.security.auth.message.config.RegistrationListener;
import jakarta.security.auth.message.config.ServerAuthConfig;
import jakarta.security.auth.message.config.ServerAuthContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.ee9.nested.UserIdentity;
import org.eclipse.jetty.ee9.security.EmptyLoginService;
import org.eclipse.jetty.ee9.security.IdentityService;
import org.eclipse.jetty.ee9.security.LoginService;
import org.eclipse.jetty.ee9.security.ServerAuthException;
import org.eclipse.jetty.ee9.security.UserAuthentication;
import org.eclipse.jetty.ee9.security.WrappedAuthConfiguration;
import org.eclipse.jetty.ee9.security.authentication.DeferredAuthentication;
import org.eclipse.jetty.ee9.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.ee9.security.authentication.SessionAuthentication;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.Authentication.User;

import static org.eclipse.jetty.ee9.security.jaspi.JaspiAuthenticatorFactory.MESSAGE_LAYER;

/**
 * Implementation of Jetty {@link LoginAuthenticator} that is a bridge from Jakarta Authentication to Jetty Security.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class JaspiAuthenticator extends LoginAuthenticator
{
    private final Subject _serviceSubject;
    private final String _appContext;
    private final boolean _allowLazyAuthentication;
    private final AuthConfigFactory _authConfigFactory = AuthConfigFactory.getFactory();
    private Map _authProperties;
    private IdentityService _identityService;
    private ServletCallbackHandler _callbackHandler;
    private ServerAuthConfig _authConfig;

    public JaspiAuthenticator(Subject serviceSubject, String appContext, boolean allowLazyAuthentication)
    {
        _serviceSubject = serviceSubject;
        _appContext = appContext;
        _allowLazyAuthentication = allowLazyAuthentication;
    }

    @Deprecated
    public JaspiAuthenticator(ServerAuthConfig authConfig, Map authProperties, ServletCallbackHandler callbackHandler, Subject serviceSubject, boolean allowLazyAuthentication, IdentityService identityService)
    {
        if (callbackHandler == null)
            throw new NullPointerException("No CallbackHandler");
        if (authConfig == null)
            throw new NullPointerException("No AuthConfig");
        this._authProperties = authProperties;
        this._callbackHandler = callbackHandler;
        this._serviceSubject = serviceSubject;
        this._allowLazyAuthentication = allowLazyAuthentication;
        this._identityService = identityService;
        this._appContext = null;
        this._authConfig = authConfig;
    }

    @Override
    public void setConfiguration(AuthConfiguration configuration)
    {
        LoginService loginService = configuration.getLoginService();
        if (loginService == null)
        {
            // Add an empty login service so we can use JASPI without tying into Jetty auth mechanisms.
            configuration = new JaspiAuthConfiguration(configuration);
            loginService = configuration.getLoginService();
        }

        super.setConfiguration(configuration);

        // Only do this if the new constructor was used.
        if (_authConfig == null)
        {
            _identityService = configuration.getIdentityService();
            _callbackHandler = new ServletCallbackHandler(loginService);
            _authProperties = new HashMap();
            for (String key : configuration.getInitParameterNames())
            {
                _authProperties.put(key, configuration.getInitParameter(key));
            }
        }
    }

    private ServerAuthConfig getAuthConfig() throws AuthException
    {
        if (_authConfig != null)
            return _authConfig;

        RegistrationListener listener = (layer, appContext) -> _authConfig = null;
        AuthConfigProvider authConfigProvider = _authConfigFactory.getConfigProvider(MESSAGE_LAYER, _appContext, listener);
        if (authConfigProvider == null)
        {
            _authConfigFactory.detachListener(listener, MESSAGE_LAYER, _appContext);
            return null;
        }

        _authConfig = authConfigProvider.getServerAuthConfig(MESSAGE_LAYER, _appContext, _callbackHandler);
        return _authConfig;
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
            ServerAuthConfig authConfig = getAuthConfig();
            if (authConfig == null)
                throw new ServerAuthException("No ServerAuthConfig");

            String authContextId = authConfig.getAuthContextID(messageInfo);
            ServerAuthContext authContext = authConfig.getAuthContext(authContextId, _serviceSubject, _authProperties);
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

                        // TODO: if the Principal class is provided it doesn't need to be in subject, why do we enforce this here?
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
            ServerAuthConfig authConfig = getAuthConfig();
            if (authConfig == null)
                throw new NullPointerException("no ServerAuthConfig found for context");

            String authContextId = authConfig.getAuthContextID(messageInfo);
            ServerAuthContext authContext = authConfig.getAuthContext(authContextId, _serviceSubject, _authProperties);
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

    private static class JaspiAuthConfiguration extends WrappedAuthConfiguration
    {
        private final LoginService loginService = new EmptyLoginService();

        public JaspiAuthConfiguration(AuthConfiguration configuration)
        {
            super(configuration);
        }

        @Override
        public LoginService getLoginService()
        {
            return loginService;
        }
    }
}
