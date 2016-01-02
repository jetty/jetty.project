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
import static org.junit.Assert.assertTrue;
import static org.mockito.BDDMockito.given;

import java.io.IOException;
import java.security.Principal;
import java.util.Iterator;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.message.callback.CallerPrincipalCallback;
import javax.security.auth.message.callback.CertStoreCallback;
import javax.security.auth.message.callback.GroupPrincipalCallback;
import javax.security.auth.message.callback.PasswordValidationCallback;
import javax.security.auth.message.callback.PrivateKeyCallback;
import javax.security.auth.message.callback.SecretKeyCallback;
import javax.security.auth.message.callback.TrustStoreCallback;

import org.eclipse.jetty.security.DefaultUserIdentity;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.MappedLoginService;
import org.eclipse.jetty.security.authentication.LoginCallback;
import org.eclipse.jetty.security.jaspi.callback.CredentialValidationCallback;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.util.security.Password;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mockito;

public class ServletCallbackHandlerTest
{

    private static final String PASSWORD = "password";

    private static final String JOHN_DOE_USER = "johndoe@jetty.com";

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private static final String ROLE_USER = "ROLE_USER";

    private static final String COOL_GROUP = "cool-group";

    private static final int TWO = 2;

    private static final int ONE = 1;

    private String[] roles;

    private Subject subject;

    private Subject subject2;

    private Principal principal;

    private Credential credential;

    private LoginService loginService;

    private ServletCallbackHandler handler;

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Before
    public void setup()
    {
        roles = new String[TWO];
        roles[0] = ROLE_ADMIN;
        roles[1] = ROLE_USER;
        credential = new Password(PASSWORD);
        principal = new MappedLoginService.KnownUser(JOHN_DOE_USER, credential);
        loginService = Mockito.mock(LoginService.class);
        handler = new ServletCallbackHandler(loginService);
        subject = new Subject();
        subject2 = new Subject();
    }

    @Test
    public void testHandleForCallerPrincipalCallback() throws UnsupportedCallbackException,IOException
    {
        // given
        Callback[] callbacks = new Callback[ONE];
        callbacks[0] = new CallerPrincipalCallback(subject, "hi");
        handler.handle(callbacks);

        // when
        CallerPrincipalCallback callback = handler.getThreadCallerPrincipalCallback();

        // then
        assertEquals("Handler must return callback[0] instance as we set the same instance through handle method", callback, callbacks[0]);
    }

    @Test
    public void testHandleForGroupPrincipalCallback() throws UnsupportedCallbackException,IOException
    {
        // given
        Callback[] callbacks = new Callback[ONE];
        String[] groups = new String[ONE];
        groups[0] = COOL_GROUP;

        // when
        callbacks[0] = new GroupPrincipalCallback(subject, groups);
        handler.handle(callbacks);

        // then
        GroupPrincipalCallback callback = handler.getThreadGroupPrincipalCallback();
        assertEquals("Handler must return callback[0] instance as we set the same instance through handle method", callback, callbacks[0]);
    }

    @Test
    public void testHandleForPasswordValidationCallback() throws UnsupportedCallbackException,IOException
    {
        // given
        UserIdentity userIdentity = new DefaultUserIdentity(subject, principal, roles);
        given(loginService.login(JOHN_DOE_USER, PASSWORD.toCharArray(), null)).willReturn(userIdentity);

        Callback[] callbacks = new Callback[ONE];

        PasswordValidationCallback passwordValidationCallback = new PasswordValidationCallback(subject2, JOHN_DOE_USER, PASSWORD.toCharArray());
        callbacks[0] = passwordValidationCallback;

        // when
        handler.handle(callbacks);

        // then
        assertTrue("This must return true as credentials are correct", passwordValidationCallback.getResult() );
        assertEquals("Private credentials size must be one as login method returns userIdentity",
                (Integer)passwordValidationCallback.getSubject().getPrivateCredentials().size(), (Integer)ONE);
        assertEquals("Private credentials instance must be equal to useridentity as login method returns userIdentity",
                passwordValidationCallback.getSubject().getPrivateCredentials().iterator().next(), userIdentity);
    }

    @Test
    public void testHandleForCredentialValidationCallback() throws UnsupportedCallbackException,IOException
    {
        // given
        UserIdentity userIdentity = new DefaultUserIdentity(subject, principal, roles);

        given(loginService.login(JOHN_DOE_USER, new Password(PASSWORD), null)).willReturn(userIdentity);

        Callback[] callbacks = new Callback[ONE];

        CredentialValidationCallback credentialValidationCallback = new CredentialValidationCallback(subject2, JOHN_DOE_USER, credential);
        callbacks[0] = credentialValidationCallback;

        // when
        handler.handle(callbacks);

        // then
        assertTrue(credentialValidationCallback.getResult());
        assertEquals(TWO, credentialValidationCallback.getSubject().getPrivateCredentials().size());

        Iterator iterator = credentialValidationCallback.getSubject().getPrivateCredentials().iterator();

        Object loginCallback = iterator.next();
        assertTrue("First item is instance of LoginCallback", loginCallback instanceof LoginCallback);

        Object user = iterator.next();
        assertEquals("Second item is UserIdentity object, same as the one we described in our login service mock", userIdentity, user);
    }

    @Test
    public void testHandleForNoOperationCallBacks() throws UnsupportedCallbackException,IOException
    {
        // given
        UserIdentity userIdentity = new DefaultUserIdentity(subject, principal, roles);
        given(loginService.login(JOHN_DOE_USER, PASSWORD.toCharArray(), null)).willReturn(userIdentity);

        Callback[] callbacks = new Callback[1];

        // Executing the handle method for which each of Callback classes (CertStoreCallback, PrivateKeyCallback,
        // SecretKeyCallback, TrustStoreCallback) to show no exception is thrown

        // when
        callbacks[0] = new CertStoreCallback();
        handler.handle(callbacks);

        callbacks[0] = new PrivateKeyCallback(null);
        handler.handle(callbacks);

        callbacks[0] = new SecretKeyCallback(null);
        handler.handle(callbacks);

        callbacks[0] = new TrustStoreCallback();
        handler.handle(callbacks);

        class MyCallback implements Callback
        {

        }

        callbacks[0] = new MyCallback();

        // then
        thrown.expect(UnsupportedCallbackException.class);
        handler.handle(callbacks);
    }
}