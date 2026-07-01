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

import java.io.IOException;
import java.security.Principal;
import java.util.Set;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;

import jakarta.security.auth.message.callback.CallerPrincipalCallback;
import jakarta.security.auth.message.callback.GroupPrincipalCallback;
import jakarta.security.auth.message.callback.PasswordValidationCallback;
import org.eclipse.jetty.ee.security.jaspi.callback.CredentialValidationCallback;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.NamePrincipal;
import org.eclipse.jetty.security.UserIdentity;

import static org.eclipse.jetty.ee.security.jaspi.JaspiAuthenticator.UNAUTHENTICATED;

/**
 * This {@link CallbackHandler} will bridge {@link Callback}s to handle to the given to the Jetty {@link LoginService}.
 */
public class ServletCallbackHandler implements CallbackHandler
{
    private final LoginService _loginService;

    public ServletCallbackHandler(LoginService loginService)
    {
        _loginService = loginService;
    }

    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException
    {
        // TODO: we should actually write to the principals of the Subject.
        //  The problem is that the UserIdentity interface doesn't expose the roles, which makes PasswordValidationCallback
        //  and CredentialValidationCallback not currently possible to implement like this.
        for (Callback callback : callbacks)
        {
            if (callback instanceof CallerPrincipalCallback callerPrincipalCallback)
            {
                Principal principal;
                if (callerPrincipalCallback.getPrincipal() != null)
                    principal = callerPrincipalCallback.getPrincipal();
                else if (callerPrincipalCallback.getName() != null)
                    principal = new NamePrincipal(callerPrincipalCallback.getName());
                else
                    principal = UNAUTHENTICATED;

                JaspiUserIdentity userIdentity = getJaspiUserIdentity(callerPrincipalCallback.getSubject());
                userIdentity.setUserPrincipal(principal);
            }
            else if (callback instanceof GroupPrincipalCallback groupPrincipalCallback)
            {
                String[] groups = groupPrincipalCallback.getGroups();
                JaspiUserIdentity userIdentity = getJaspiUserIdentity(groupPrincipalCallback.getSubject());
                userIdentity.addRoles(groups);
            }
            else if (callback instanceof PasswordValidationCallback passwordValidationCallback)
            {
                UserIdentity userIdentity = _loginService.login(passwordValidationCallback.getUsername(), passwordValidationCallback.getPassword(), null, null);
                passwordValidationCallback.setResult(userIdentity != null);
                JaspiUserIdentity userInfo = getJaspiUserIdentity(passwordValidationCallback.getSubject());
                userInfo.setWrapped(userIdentity);
            }
            else if (callback instanceof CredentialValidationCallback credentialValidationCallback)
            {
                UserIdentity userIdentity = _loginService.login(credentialValidationCallback.getUsername(), credentialValidationCallback.getCredential(), null, null);
                credentialValidationCallback.setResult(userIdentity != null);
                JaspiUserIdentity userInfo = getJaspiUserIdentity(credentialValidationCallback.getSubject());
                userInfo.setWrapped(userIdentity);
            }
            else
            {
                throw new UnsupportedCallbackException(callback);
            }
        }
    }

    private JaspiUserIdentity getJaspiUserIdentity(Subject subject) throws IOException
    {
        Set<JaspiUserIdentity> userIdentitySet = subject.getPrivateCredentials(JaspiUserIdentity.class);
        if (userIdentitySet.isEmpty())
        {
            JaspiUserIdentity userIdentity = new JaspiUserIdentity(subject);
            subject.getPrivateCredentials().add(userIdentity);
            return userIdentity;
        }
        else if (userIdentitySet.size() == 1)
            return userIdentitySet.iterator().next();
        else
            throw new IOException("Multiple user infos found");
    }
}
