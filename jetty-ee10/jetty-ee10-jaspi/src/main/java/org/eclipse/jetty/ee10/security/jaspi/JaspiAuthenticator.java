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
import org.eclipse.jetty.ee.security.jaspi.ServletCallbackHandler;
import org.eclipse.jetty.ee.servlet.ServletContextRequest;
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
public class JaspiAuthenticator extends org.eclipse.jetty.ee.security.jaspi.JaspiAuthenticator
{
    public JaspiAuthenticator(Subject serviceSubject, String appContext, boolean allowLazyAuthentication)
    {
        super(serviceSubject, appContext, allowLazyAuthentication);
    }

    @Deprecated
    public JaspiAuthenticator(ServerAuthConfig authConfig, Map authProperties, ServletCallbackHandler callbackHandler, Subject serviceSubject, boolean allowLazyAuthentication, IdentityService identityService)
    {
        super(authConfig, authProperties, callbackHandler, serviceSubject, allowLazyAuthentication, identityService);
    }
}
