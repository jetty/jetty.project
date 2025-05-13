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

package org.eclipse.jetty.ee10.security.jaspi;

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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.ee10.servlet.ServletContextRequest;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.EmptyLoginService;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.security.UserPrincipal;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.security.authentication.SessionAuthentication;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

import static org.eclipse.jetty.ee10.security.jaspi.JaspiAuthenticatorFactory.MESSAGE_LAYER;

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
            _authProperties = new HashMap();
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
    public AuthenticationState validateRequest(Request request, Response response, Callback callback) throws ServerAuthException
    {
        JaspiMessageInfo info = new JaspiMessageInfo(request, response, callback);
        request.setAttribute("org.eclipse.jetty.ee10.security.jaspi.info", info);

        return validateRequest(info);
    }

    public AuthenticationState validateRequest(JaspiMessageInfo messageInfo) throws ServerAuthException
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
                return AuthenticationState.CHALLENGE;
            if (authStatus == AuthStatus.SEND_FAILURE)
                return AuthenticationState.SEND_FAILURE;

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
                        return null;
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
                            principal = new UserPrincipal(principalName, null);
                        }
                    }
                    GroupPrincipalCallback groupPrincipalCallback = _callbackHandler.getThreadGroupPrincipalCallback();
                    String[] groups = groupPrincipalCallback == null ? null : groupPrincipalCallback.getGroups();
                    userIdentity = _identityService.newUserIdentity(clientSubject, principal, groups);
                }

                HttpSession session = ((HttpServletRequest)messageInfo.getRequestMessage()).getSession(false);
                AuthenticationState cached = (session == null ? null : (SessionAuthentication)session.getAttribute(SessionAuthentication.AUTHENTICATED_ATTRIBUTE));
                if (cached != null)
                    return cached;

                return new UserAuthenticationSucceeded(getAuthenticationType(), userIdentity);
            }
            if (authStatus == AuthStatus.SEND_SUCCESS)
            {
                // we are processing a message in a secureResponse dialog.
                return AuthenticationState.SEND_SUCCESS;
            }
            if (authStatus == AuthStatus.FAILURE)
            {
                Response.writeError(messageInfo.getBaseRequest(), messageInfo.getBaseResponse(), messageInfo.getCallback(), HttpServletResponse.SC_FORBIDDEN);
                return AuthenticationState.SEND_FAILURE;
            }
            // should not happen
            throw new IllegalStateException("No AuthStatus returned");
        }
        catch (AuthException e)
        {
            throw new ServerAuthException(e);
        }
    }

    // TODO This is not longer supported by core security
    public boolean secureResponse(Request request, Response response, Callback callback, boolean mandatory, AuthenticationState.Succeeded validatedSucceeded) throws ServerAuthException
    {
        ServletContextRequest servletContextRequest = Request.asInContext(request, ServletContextRequest.class);
        JaspiMessageInfo info = (JaspiMessageInfo)servletContextRequest.getServletApiRequest().getAttribute("org.eclipse.jetty.ee10.security.jaspi.info");
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
