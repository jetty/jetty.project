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

package org.eclipse.jetty.ee.security.jaspi.modules;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;

import jakarta.security.auth.message.callback.CallerPrincipalCallback;
import jakarta.security.auth.message.callback.GroupPrincipalCallback;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee.security.jaspi.JaspiTest.TestLoginService;
import org.eclipse.jetty.ee.security.jaspi.JaspiUserIdentity;
import org.eclipse.jetty.ee.security.jaspi.ServletCallbackHandler;
import org.eclipse.jetty.ee.security.jaspi.callback.CredentialValidationCallback;
import org.eclipse.jetty.security.EmptyLoginService;
import org.eclipse.jetty.security.NamePrincipal;
import org.eclipse.jetty.security.RolePrincipal;
import org.eclipse.jetty.util.security.Password;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class BaseAuthModuleTest
{
    @Test
    public void testCleanSubjectRemovesWhatTheModuleEstablished() throws Exception
    {
        ServletCallbackHandler callbackHandler = new ServletCallbackHandler(new EmptyLoginService());
        Subject subject = new Subject();
        callbackHandler.handle(new Callback[]{new CallerPrincipalCallback(subject, new NamePrincipal("user"))});
        callbackHandler.handle(new Callback[]{new GroupPrincipalCallback(subject, new String[]{"groupA"})});

        NamePrincipal other = new NamePrincipal("established by something else");
        subject.getPrincipals().add(other);

        JaspiUserIdentity identity = ServletCallbackHandler.getUserIdentity(subject);
        new BasicAuthenticationAuthModule().cleanSubject(null, subject);

        assertThat(subject.getPrincipals(), contains(other));
        assertThat(subject.getPrivateCredentials(), empty());

        // The identity may still be held by a concurrent request, so cleanSubject must not mutate it.
        assertThat(identity.getUserPrincipal().getName(), is("user"));
        assertThat(identity.isUserInRole("groupA"), is(true));
    }

    @Test
    public void testCleanSubjectLeavesGroupPrincipalsTheModuleDidNotEstablish() throws Exception
    {
        TestLoginService loginService = new TestLoginService("TestRealm");
        loginService.putUser("user", new Password("password"), new String[]{"loginRole"});
        loginService.start();

        try
        {
            ServletCallbackHandler callbackHandler = new ServletCallbackHandler(loginService);
            Subject subject = new Subject();
            callbackHandler.handle(new Callback[]{new CredentialValidationCallback(subject, "user", new Password("password"))});
            callbackHandler.handle(new Callback[]{new CallerPrincipalCallback(subject, new NamePrincipal("user"))});

            // A group principal the module did not establish is not the module's to remove.
            RolePrincipal other = new RolePrincipal("otherRole");
            subject.getPrincipals().add(other);

            new BasicAuthenticationAuthModule().cleanSubject(null, subject);

            assertThat(subject.getPrincipals(), contains(other));
        }
        finally
        {
            loginService.stop();
        }
    }

    @Test
    public void testCleanSubjectWithNoSubject() throws Exception
    {
        new BasicAuthenticationAuthModule().cleanSubject(null, null);
    }

    @Test
    public void testEachCallerGetsItsOwnSupportedMessageTypes()
    {
        BasicAuthenticationAuthModule module = new BasicAuthenticationAuthModule();

        assertThat(module.getSupportedMessageTypes(), not(sameInstance(module.getSupportedMessageTypes())));
        assertArrayEquals(new Class<?>[]{HttpServletRequest.class, HttpServletResponse.class}, module.getSupportedMessageTypes());
    }
}
