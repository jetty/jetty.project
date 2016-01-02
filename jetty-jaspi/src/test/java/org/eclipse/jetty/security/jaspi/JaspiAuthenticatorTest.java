//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.security.jaspi;

import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.DefaultUserIdentity;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.MappedLoginService;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.server.session.AbstractSession;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.util.security.Password;
import org.junit.Before;
import org.junit.Test;

import javax.security.auth.Subject;
import javax.security.auth.message.config.ServerAuthConfig;
import javax.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class JaspiAuthenticatorTest
{

    private static final String PASSWORD = "password";

    private static final String JOHN_DOE_USER = "johndoe@jetty.com";

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private static final String ROLE_USER = "ROLE_USER";

    private static final int TWO = 2;

    private String[] roles;

    private Principal principal;

    private ServerAuthConfig authConfig;

    private HttpServletRequest servletRequest;

    private Map authProperties;

    private ServletCallbackHandler callbackHandler;

    private Subject serviceSubject;

    private boolean allowLazyAuthentication;

    private IdentityService identityService;

    private Authenticator.AuthConfiguration authConfiguration;

    private LoginService loginService;

    private AbstractSession abstractSession;

    @Before
    public void setup()
    {
        authConfig = mock(ServerAuthConfig.class);
        servletRequest = mock(HttpServletRequest.class);
        authProperties = new HashMap<>();
        callbackHandler = mock(ServletCallbackHandler.class);
        serviceSubject = new Subject();
        allowLazyAuthentication = true;
        identityService = mock(IdentityService.class);
        authConfiguration = mock(Authenticator.AuthConfiguration.class);
        loginService = mock(LoginService.class);
        abstractSession = mock(AbstractSession.class);
        roles = new String[TWO];
        roles[0] = ROLE_ADMIN;
        roles[1] = ROLE_USER;
        Credential credential = new Password(PASSWORD);
        principal = new MappedLoginService.KnownUser(JOHN_DOE_USER, credential);
    }

    @Test
    public void testLogin() 
    {
        // given
        Subject subject = new Subject();
        UserIdentity userIdentity = new DefaultUserIdentity(subject, principal, roles);
        given(loginService.login(JOHN_DOE_USER, PASSWORD, servletRequest)).willReturn(userIdentity);
        given(authConfiguration.getLoginService()).willReturn(loginService);
        given(authConfiguration.getIdentityService()).willReturn(mock(IdentityService.class));
        given(authConfiguration.isSessionRenewedOnAuthentication()).willReturn(true);
        given(abstractSession.getAttribute(AbstractSession.SESSION_CREATED_SECURE)).willReturn(false);
        given(servletRequest.getSession(false)).willReturn(abstractSession);

        JaspiAuthenticator jaspiAuthenticator = new JaspiAuthenticator(authConfig, authProperties, callbackHandler, serviceSubject, allowLazyAuthentication,
                identityService);
        jaspiAuthenticator.setConfiguration(authConfiguration);

        // when
        UserIdentity returnedUserIdentity = jaspiAuthenticator.login(JOHN_DOE_USER, PASSWORD, servletRequest);

        // then
        verify(servletRequest).getSession(false);
        verify(servletRequest).getSession(true);
        verify(abstractSession).getAttribute(AbstractSession.SESSION_CREATED_SECURE);
        verify(abstractSession).renewId(servletRequest);
        verify(abstractSession).setAttribute(AbstractSession.SESSION_CREATED_SECURE,Boolean.TRUE);
        verify(abstractSession).getAttribute(AbstractSession.SESSION_CREATED_SECURE);
        assertEquals("User Identity is returned", userIdentity,returnedUserIdentity);
    }
}