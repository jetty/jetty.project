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

package org.eclipse.jetty.ee9.security.jaspi;

import java.security.Principal;
import java.util.Set;
import java.util.stream.Collectors;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.UnsupportedCallbackException;

import jakarta.security.auth.message.callback.CallerPrincipalCallback;
import jakarta.security.auth.message.callback.GroupPrincipalCallback;
import jakarta.security.auth.message.callback.PasswordValidationCallback;
import org.eclipse.jetty.ee9.security.jaspi.callback.CredentialValidationCallback;
import org.eclipse.jetty.security.NamePrincipal;
import org.eclipse.jetty.security.RolePrincipal;
import org.eclipse.jetty.util.security.Password;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ServletCallbackHandlerTest
{
    private JaspiTest.TestLoginService _loginService;
    private ServletCallbackHandler _callbackHandler;

    @BeforeEach
    public void before() throws Exception
    {
        _loginService = new JaspiTest.TestLoginService("TestRealm");
        _loginService.putUser("user", new Password("password"), new String[]{"loginRole"});
        _loginService.start();
        _callbackHandler = new ServletCallbackHandler(_loginService);
    }

    @AfterEach
    public void after() throws Exception
    {
        _loginService.stop();
    }

    private JaspiUserIdentity userIdentity(Subject subject)
    {
        JaspiUserIdentity userIdentity = ServletCallbackHandler.getUserIdentity(subject);
        assertNotNull(userIdentity);
        return userIdentity;
    }

    @Test
    public void testNoCallbacksEstablishNoIdentity()
    {
        assertThat(ServletCallbackHandler.getUserIdentity(new Subject()), nullValue());
    }

    @Test
    public void testAnIdentityIsNotChangedByLaterCallbacks() throws Exception
    {
        Subject subject = new Subject();
        _callbackHandler.handle(new Callback[]{new CallerPrincipalCallback(subject, new NamePrincipal("user"))});
        _callbackHandler.handle(new Callback[]{new GroupPrincipalCallback(subject, new String[]{"groupA"})});

        JaspiUserIdentity userIdentity = userIdentity(subject);

        _callbackHandler.handle(new Callback[]{new CallerPrincipalCallback(subject, new NamePrincipal("other"))});
        _callbackHandler.handle(new Callback[]{new GroupPrincipalCallback(subject, new String[]{"groupB"})});
        _callbackHandler.handle(new Callback[]{new CredentialValidationCallback(subject, "user", new Password("password"))});

        assertThat(userIdentity.getUserPrincipal().getName(), is("user"));
        assertThat(userIdentity.getRoles(), arrayContainingInAnyOrder("groupA"));
        assertThat(userIdentity.isUserInRole("groupB"), is(false));
        assertThat(userIdentity.isUserInRole("loginRole"), is(false));

        // The callbacks that followed are in the identity built after them, the last of which named the caller whose credentials it validated.
        assertThat(userIdentity(subject).getUserPrincipal().getName(), is("user"));
        assertThat(userIdentity(subject).getRoles(), arrayContainingInAnyOrder("groupA", "groupB", "loginRole"));
    }

    @Test
    public void testGetRolesFromGroupPrincipalCallback() throws Exception
    {
        Subject subject = new Subject();
        _callbackHandler.handle(new Callback[]{new CallerPrincipalCallback(subject, new NamePrincipal("user"))});
        _callbackHandler.handle(new Callback[]{new GroupPrincipalCallback(subject, new String[]{"groupA", "groupB"})});

        assertThat(userIdentity(subject).getRoles(), arrayContainingInAnyOrder("groupA", "groupB"));
    }

    @Test
    public void testGetRolesIncludesTheValidatedCallerRoles() throws Exception
    {
        Subject subject = new Subject();
        _callbackHandler.handle(new Callback[]{new CredentialValidationCallback(subject, "user", new Password("password"))});
        _callbackHandler.handle(new Callback[]{new GroupPrincipalCallback(subject, new String[]{"groupA"})});

        assertThat(userIdentity(subject).getRoles(), arrayContainingInAnyOrder("groupA", "loginRole"));
    }

    @Test
    public void testGetRolesWithoutAnyGroupOrLogin() throws Exception
    {
        Subject subject = new Subject();
        _callbackHandler.handle(new Callback[]{new CallerPrincipalCallback(subject, new NamePrincipal("user"))});

        assertThat(userIdentity(subject).getRoles(), emptyArray());
    }

    @Test
    public void testCallerAndGroupPrincipalsAreEstablishedInTheSubject() throws Exception
    {
        Subject subject = new Subject();
        NamePrincipal caller = new NamePrincipal("user");
        _callbackHandler.handle(new Callback[]{new CallerPrincipalCallback(subject, caller)});
        _callbackHandler.handle(new Callback[]{new GroupPrincipalCallback(subject, new String[]{"groupA"})});
        _callbackHandler.handle(new Callback[]{new GroupPrincipalCallback(subject, new String[]{"groupA", "groupB"})});

        assertThat(subject.getPrincipals(NamePrincipal.class), contains(caller));
        assertThat(rolePrincipalNames(subject), containsInAnyOrder("groupA", "groupB"));
    }

    @Test
    public void testCallerPrincipalFromANameIsEstablishedInTheSubject() throws Exception
    {
        Subject subject = new Subject();
        _callbackHandler.handle(new Callback[]{new CallerPrincipalCallback(subject, "user")});

        assertThat(subject.getPrincipals().size(), is(1));
        assertThat(subject.getPrincipals().iterator().next().getName(), is("user"));
    }

    @Test
    public void testUnauthenticatedCallerEstablishesNoPrincipal() throws Exception
    {
        Subject subject = new Subject();
        _callbackHandler.handle(new Callback[]{new CallerPrincipalCallback(subject, (Principal)null)});

        assertThat(subject.getPrincipals(), empty());
        assertThat(userIdentity(subject).getUserPrincipal(), is(JaspiAuthenticator.UNAUTHENTICATED));
    }

    @Test
    public void testNullGroupsEstablishesNoGroupPrincipals() throws Exception
    {
        Subject subject = new Subject();
        NamePrincipal caller = new NamePrincipal("user");
        _callbackHandler.handle(new Callback[]{new CallerPrincipalCallback(subject, caller)});
        _callbackHandler.handle(new Callback[]{new GroupPrincipalCallback(subject, new String[]{"groupA"})});
        _callbackHandler.handle(new Callback[]{new GroupPrincipalCallback(subject, null)});

        assertThat(subject.getPrincipals(), contains(caller));
        assertThat(userIdentity(subject).getRoles(), emptyArray());
        assertThat(userIdentity(subject).isUserInRole("groupA"), is(false));
    }

    @Test
    public void testPasswordValidationCallbackWithTheRightPassword() throws Exception
    {
        Subject subject = new Subject();
        PasswordValidationCallback callback = new PasswordValidationCallback(subject, "user", "password".toCharArray());
        _callbackHandler.handle(new Callback[]{callback});

        assertThat(callback.getResult(), is(true));
        assertThat(userIdentity(subject).getUserPrincipal().getName(), is("user"));
        assertThat(userIdentity(subject).getRoles(), arrayContainingInAnyOrder("loginRole"));
        assertThat(userIdentity(subject).isUserInRole("loginRole"), is(true));
    }

    @Test
    public void testPasswordValidationCallbackWithTheWrongPassword() throws Exception
    {
        Subject subject = new Subject();
        PasswordValidationCallback callback = new PasswordValidationCallback(subject, "user", "wrong".toCharArray());
        _callbackHandler.handle(new Callback[]{callback});

        assertThat(callback.getResult(), is(false));
        assertThat(userIdentity(subject).getRoles(), emptyArray());
        assertThat(userIdentity(subject).isUserInRole("loginRole"), is(false));
        assertThat(subject.getPrincipals(), empty());
    }

    @Test
    public void testACallerPrincipalCallbackOnAnotherSubjectDoesNotChangeTheClientSubject() throws Exception
    {
        Subject clientSubject = new Subject();
        _callbackHandler.handle(new Callback[]{new CallerPrincipalCallback(clientSubject, "j2ee")});

        Subject other = new Subject();
        _callbackHandler.handle(new Callback[]{new CallerPrincipalCallback(other, (Principal)null)});

        assertThat(userIdentity(clientSubject).getUserPrincipal().getName(), is("j2ee"));
        assertThat(userIdentity(other).getUserPrincipal(), is(JaspiAuthenticator.UNAUTHENTICATED));
    }

    @Test
    public void testANullCallbackIsUnsupported()
    {
        UnsupportedCallbackException e = assertThrows(UnsupportedCallbackException.class,
            () -> _callbackHandler.handle(new Callback[]{null}));
        assertThat(e.getCallback(), nullValue());
    }

    private static Set<String> rolePrincipalNames(Subject subject)
    {
        return subject.getPrincipals(RolePrincipal.class).stream().map(RolePrincipal::getName).collect(Collectors.toSet());
    }
}
