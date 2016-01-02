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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyObject;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.when;

import java.io.IOException;
import java.util.HashMap;

import javax.security.auth.Subject;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.message.AuthException;
import javax.security.auth.message.AuthStatus;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.security.Authenticator.AuthConfiguration;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.server.Authentication.User;
import org.eclipse.jetty.server.UserIdentity;
import org.junit.Before;
import org.junit.Test;

public class JaspiAuthenticatorUtilTest extends JaspiAuthenticatorBase
{

    @Before
    public void setUp() throws AuthException
    {
        serverAuthConfig = getAuthConfig(null, SECURE_RESPONSE, AuthStatus.SEND_SUCCESS);
        callbackHandler = new ServletCallbackHandler(null);
        jaspiAuthenticator = new JaspiAuthenticator(serverAuthConfig, new HashMap(), callbackHandler, serviceSubject, allowLazyAuthentication, identityService);
    }

    @Test
    public void testBaseOperations()
    {
        assertEquals("AuthMehod must always be JASPI", "JASPI", jaspiAuthenticator.getAuthMethod());
    }

    @Test(expected = NullPointerException.class)
    public void testJaspiAuthenticatorCbhNpeException()
    {
        // given
        serverAuthConfig = null;
        callbackHandler = null;

        // when
        jaspiAuthenticator = new JaspiAuthenticator(serverAuthConfig, authProperties, callbackHandler, serviceSubject, allowLazyAuthentication, identityService);

        // then
        fail("A NullPointerException must have occurred by now as callbackHandler value is null");
    }

    @Test(expected = NullPointerException.class)
    public void testJaspiAuthenticatorAcNpeException()
    {
        // given
        serverAuthConfig = null;
        callbackHandler = new ServletCallbackHandler(null);

        // when
        jaspiAuthenticator = new JaspiAuthenticator(serverAuthConfig, authProperties, callbackHandler, serviceSubject, allowLazyAuthentication, identityService);

        // then
        fail("A NullPointerException must have occurred by now as authConfig value is null");
    }

    @Test(expected = NullPointerException.class)
    public void testSecureResponseNpeException() throws ServerAuthException
    {
        // when
        jaspiAuthenticator.secureResponse(req, res, mandatory, validatedUser);

        // then
        fail("A NullPointerException must have occurred by now as the req object doesn't contain key" + " with the name org.eclipse.jetty.security.jaspi.info");
    }

    @Test(expected = ServerAuthException.class)
    public void testSecureResponseServerAuthException() throws ServerAuthException
    {
        // given
        res = mock(ServletResponse.class);
        validatedUser = mock(User.class);
        req = mock(ServletRequest.class);
        when(req.getAttribute("org.eclipse.jetty.security.jaspi.info")).thenReturn(getJaspiMessageInfo());

        // when
        jaspiAuthenticator.secureResponse(req, res, mandatory, validatedUser);

        // then
        fail("A ServerAuthException must have occurred by now as the authentication validation fails");
    }

    @Test
    public void testSecureResponse() throws AuthException,ServerAuthException
    {
        // given
        res = mock(ServletResponse.class);
        validatedUser = mock(User.class);
        req = mock(ServletRequest.class);
        jspiMessageInfo = getJaspiMessageInfo();
        when(req.getAttribute("org.eclipse.jetty.security.jaspi.info")).thenReturn(jspiMessageInfo);
        serverAuthConfig = getAuthConfig(jspiMessageInfo,SECURE_RESPONSE, AuthStatus.SEND_SUCCESS);
        callbackHandler = new ServletCallbackHandler(null);

        // when
        jaspiAuthenticator = new JaspiAuthenticator(serverAuthConfig, authProperties, callbackHandler, serviceSubject, allowLazyAuthentication, identityService);

        // then
        Boolean expectedResult = jaspiAuthenticator.secureResponse(req, res, mandatory, validatedUser);
        assertTrue("This should return true as authentication status is success", expectedResult);
    }

    @Test(expected = ServerAuthException.class)
    public void testValidateRequestServerAuthException() throws ServerAuthException
    {
        // given
        res = mock(ServletResponse.class);
        req = mock(ServletRequest.class);

        // when
        jaspiAuthenticator.validateRequest(req, res, true);

        // then
        fail("A ServerAuthException must have occurred by now as there is no module registered in context");
    }

    @Test
    public void testValidateRequestFailureStatus() throws AuthException,ServerAuthException
    {
        // given
        initiateJaspiAuthenticator(AuthStatus.FAILURE);
        jaspiAuthenticator = new JaspiAuthenticator(serverAuthConfig, new HashMap(), callbackHandler, new Subject(), allowLazyAuthentication, identityService);

        // when
        authentication = jaspiAuthenticator.validateRequest(httpServletreq, httpServletresp, true);

        // then
        assertEquals("Auth status must be failure", "FAILURE", authentication.toString());
    }

    @Test
    public void testValidateRequestSendContinueStatus() throws AuthException,ServerAuthException
    {
        // given
        initiateJaspiAuthenticator(AuthStatus.SEND_CONTINUE);
        jaspiAuthenticator = new JaspiAuthenticator(serverAuthConfig, new HashMap(), callbackHandler, new Subject(), allowLazyAuthentication, identityService);

        // when
        authentication = jaspiAuthenticator.validateRequest(httpServletreq, httpServletresp, true);

        // then
        assertEquals("Auth status must be challenge", "CHALLENGE", authentication.toString());
    }

    @Test
    public void testValidateRequestSendSuccessStatus() throws AuthException,ServerAuthException
    {
        // given
        initiateJaspiAuthenticator(AuthStatus.SEND_SUCCESS);
        jaspiAuthenticator = new JaspiAuthenticator(serverAuthConfig, new HashMap(), callbackHandler, new Subject(), allowLazyAuthentication, identityService);

        // when
        authentication = jaspiAuthenticator.validateRequest(httpServletreq, httpServletresp, true);

        // then
        assertEquals("Auth status must be send success", "SEND_SUCCESS", authentication.toString());
    }

    @Test
    public void testValidateRequestSuccessStatus() throws AuthException,ServerAuthException
    {
        // given
        initiateJaspiAuthenticator(AuthStatus.SUCCESS);
        jaspiAuthenticator = new JaspiAuthenticator(serverAuthConfig, new HashMap(), callbackHandler, new Subject(), allowLazyAuthentication, identityService);

        // when
        authentication = jaspiAuthenticator.validateRequest(httpServletreq, httpServletresp, true);

        // then
        assertEquals("Auth status must be send unauthenticated", "UNAUTHENTICATED", authentication.toString());
    }

    @Test
    public void testValidateRequestForInValidUserHandler() throws UnsupportedCallbackException,IOException,AuthException,ServerAuthException
    {
        // given
        initiateJaspiAuthenticator(AuthStatus.SUCCESS);
        callbackHandler.handle(getCallbacks("in valid username"));
        jaspiAuthenticator = new JaspiAuthenticator(serverAuthConfig, new HashMap(), callbackHandler, new Subject(), allowLazyAuthentication, identityService);

        // when
        authentication = jaspiAuthenticator.validateRequest(httpServletreq, httpServletresp, true);

        // then
        assertEquals("Auth status must be send unauthenticated", "UNAUTHENTICATED", authentication.toString());
    }

    @Test
    public void testValidateRequestForValidUserHandler() throws UnsupportedCallbackException,IOException,AuthException,ServerAuthException
    {
        // given
        // username is valid user
        initiateJaspiAuthenticator(AuthStatus.SUCCESS);
        callbackHandler.handle(getCallbacks("a-valid-user"));
        jaspiAuthenticator = new JaspiAuthenticator(serverAuthConfig, new HashMap(), callbackHandler, new Subject(), allowLazyAuthentication, identityService);

        // when
        authentication = jaspiAuthenticator.validateRequest(httpServletreq, httpServletresp, true);

        // then
        assertTrue("Authentication must contain username(a-valid-user)", authentication.toString().contains("JMXPrincipal:  a-valid-user"));
    }

    @Test
    public void testLogin()
    {
        // given
        authConfig = mock(AuthConfiguration.class);
        loginService = mock(LoginService.class);
        identityService = mock(IdentityService.class);
        when(authConfig.getLoginService()).thenReturn(loginService);
        when(authConfig.getIdentityService()).thenReturn(identityService);
        when(loginService.login((String)anyObject(), anyObject(), (ServletRequest)anyObject())).thenReturn(userIdentity);
        // login method intenally calls loginservice object through configuration object

        jaspiAuthenticator.setConfiguration(authConfig);
        req = mock(HttpServletRequest.class);
        assertNull("User identity is null, so this method call must return null", jaspiAuthenticator.login("vijay", "password", req));
        userIdentity = mock(UserIdentity.class);
        when(loginService.login((String)anyObject(), anyObject(), (ServletRequest)anyObject())).thenReturn(userIdentity);

        // when
        userIdentity = jaspiAuthenticator.login("vijay", "password", req);

        // then
        assertNotNull("login method should return null for invalid user", userIdentity);
    }
}