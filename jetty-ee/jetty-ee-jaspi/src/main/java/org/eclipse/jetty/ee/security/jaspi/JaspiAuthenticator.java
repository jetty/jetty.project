//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee.security.jaspi;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.security.auth.Subject;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.AuthStatus;
import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.AuthConfigProvider;
import jakarta.security.auth.message.config.RegistrationListener;
import jakarta.security.auth.message.config.ServerAuthConfig;
import jakarta.security.auth.message.config.ServerAuthContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.ee.servlet.ServletContextRequest;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.EmptyLoginService;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.ServletAuthenticator;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.security.authentication.SessionAuthentication;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

import static jakarta.security.auth.message.AuthStatus.SEND_CONTINUE;
import static jakarta.security.auth.message.AuthStatus.SEND_FAILURE;
import static jakarta.security.auth.message.AuthStatus.SEND_SUCCESS;
import static jakarta.security.auth.message.AuthStatus.SUCCESS;
import static org.eclipse.jetty.ee.security.jaspi.JaspiAuthenticatorFactory.MESSAGE_LAYER;

/**
 * Implementation of Jetty {@link LoginAuthenticator} that is a bridge from Jakarta Authentication to Jetty Security.
 */
public class JaspiAuthenticator extends LoginAuthenticator implements ServletAuthenticator
{
    public static final Principal UNAUTHENTICATED = () -> null;

    private final Subject _serviceSubject;
    private final String _appContext;
    private final AuthConfigFactory _authConfigFactory = AuthConfigFactory.getFactory();
    private Map<String, Object> _authProperties;
    private ServletCallbackHandler _callbackHandler;
    private ServerAuthConfig _authConfig;

    public JaspiAuthenticator(Subject serviceSubject, String appContext)
    {
        _serviceSubject = serviceSubject;
        _appContext = appContext;
    }

    @Override
    public void setConfiguration(Configuration configuration)
    {
        LoginService loginService = configuration.getLoginService();
        if (loginService == null)
        {
            // Add an empty login service so we can use JASPI without tying into Jetty auth mechanisms.
            configuration = new JaspiAuthenticatorConfiguration(configuration);
            loginService = configuration.getLoginService();
        }

        super.setConfiguration(configuration);

        // Only do this if the new constructor was used.
        if (_authConfig == null)
        {
            _identityService = configuration.getIdentityService();
            _callbackHandler = new ServletCallbackHandler(loginService);
            _authProperties = new HashMap<>();
            for (String key : configuration.getParameterNames())
            {
                _authProperties.put(key, configuration.getParameter(key));
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
    public String getAuthenticationType()
    {
        return "JASPI";
    }

    @Override
    public UserIdentity login(String username, Object password, Request request, Response response)
    {
        UserIdentity user = _loginService.login(username, password, request, request::getSession);
        if (user != null)
        {
            updateSession(request, response);
            HttpSession session = ((HttpServletRequest)request).getSession(true);
            if (session != null)
            {
                SessionAuthentication sessionAuth = new SessionAuthentication(getAuthenticationType(), user, password);
                session.setAttribute(SessionAuthentication.AUTHENTICATED_ATTRIBUTE, sessionAuth);
            }
        }
        return user;
    }

    @Override
    public AuthenticationState validateRequest(Request request, Response response, Callback ignored) throws ServerAuthException
    {
        boolean isDeferred = AuthenticationState.getAuthenticationState(request) instanceof AuthenticationState.Deferred;
        boolean isAuthenticationRequest = isDeferred && !AuthenticationState.Deferred.isDeferred(response);
        boolean isMandatory = !isDeferred || isAuthenticationRequest;
        JaspiMessageInfo messageInfo = new JaspiMessageInfo(request, response);
        messageInfo.setMandatory(isMandatory);
        messageInfo.setAuthenticationRequest(isAuthenticationRequest);

        try
        {
            ServerAuthConfig authConfig = getAuthConfig();
            if (authConfig == null)
                throw new ServerAuthException("No ServerAuthConfig");

            String authContextId = authConfig.getAuthContextID(messageInfo);
            ServerAuthContext authContext = authConfig.getAuthContext(authContextId, _serviceSubject, _authProperties);
            Subject clientSubject = new Subject();

            AuthStatus authStatus = authContext.validateRequest(messageInfo, clientSubject, _serviceSubject);
            if (authStatus == SUCCESS)
            {
                Set<JaspiUserIdentity> userInfoSet = clientSubject.getPrivateCredentials(JaspiUserIdentity.class);
                if (userInfoSet.size() != 1)
                    throw new ServerAuthException("incorrect JaspiUserIdentity set size " +  userInfoSet.size());

                JaspiUserIdentity userIdentity = userInfoSet.iterator().next();
                if (userIdentity.getUserPrincipal() == UNAUTHENTICATED)
                    return null;
                if (userIdentity.getUserPrincipal() == null)
                    throw new ServerAuthException("No Caller Principal set");

                // Set HttpServletRequest / HttpServletResponse from MessageInfo as they may be wrapped.
                ServletContextRequest servletContextRequest = Request.asInContext(request, ServletContextRequest.class);
                assert servletContextRequest != null;
                servletContextRequest.setHttpServletRequest((HttpServletRequest)messageInfo.getRequestMessage());
                servletContextRequest.setHttpServletResponse((HttpServletResponse)messageInfo.getResponseMessage());

                String authType = messageInfo.getAuthenticationType();
                if (authType == null)
                    authType = getAuthenticationType();
                return new UserAuthenticationSucceeded(authType, userIdentity);
            }
            else if (authStatus == SEND_SUCCESS)
                return AuthenticationState.SEND_SUCCESS;
            else if (authStatus == SEND_CONTINUE)
                return AuthenticationState.CHALLENGE;
            else if (authStatus == SEND_FAILURE)
                return AuthenticationState.SEND_FAILURE;
            throw new ServerAuthException("Bad AuthStatus "  + authStatus);
        }
        catch (AuthException e)
        {
            throw new ServerAuthException(e);
        }
    }

    // TODO: find where in the lifecycle to tie this into.
    public boolean secureResponse(Request request, Response response, Callback callback, boolean mandatory, AuthenticationState.Succeeded validatedSucceeded) throws ServerAuthException
    {
        ServletContextRequest servletContextRequest = Request.asInContext(request, ServletContextRequest.class);
        assert servletContextRequest != null;
        JaspiMessageInfo info = (JaspiMessageInfo)servletContextRequest.getHttpServletRequest().getAttribute("org.eclipse.jetty.ee11.security.jaspi.info");
        if (info == null)
            throw new NullPointerException("MessageInfo from request missing: " + request);
        return secureResponse(info, validatedSucceeded);
    }

    public boolean secureResponse(JaspiMessageInfo messageInfo, AuthenticationState validatedUser) throws ServerAuthException
    {
        try
        {
            ServerAuthConfig authConfig = getAuthConfig();
            if (authConfig == null)
                throw new NullPointerException("no ServerAuthConfig found for context");

            String authContextId = authConfig.getAuthContextID(messageInfo);
            ServerAuthContext authContext = authConfig.getAuthContext(authContextId, _serviceSubject, _authProperties);
            if (validatedUser instanceof AuthenticationState.Succeeded userAuthenticated)
                authContext.cleanSubject(messageInfo, userAuthenticated.getUserIdentity().getSubject());
            AuthStatus status = authContext.secureResponse(messageInfo, _serviceSubject);
            return (AuthStatus.SEND_SUCCESS.equals(status));
        }
        catch (AuthException e)
        {
            throw new ServerAuthException(e);
        }
    }

    private static class JaspiAuthenticatorConfiguration extends Configuration.Wrapper
    {
        private final LoginService loginService = new EmptyLoginService();

        public JaspiAuthenticatorConfiguration(Configuration configuration)
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
