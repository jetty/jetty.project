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

package org.eclipse.jetty.ee10.security.jaspi;

import java.io.IOException;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;

import jakarta.security.auth.message.callback.CallerPrincipalCallback;
import jakarta.security.auth.message.callback.CertStoreCallback;
import jakarta.security.auth.message.callback.GroupPrincipalCallback;
import jakarta.security.auth.message.callback.PasswordValidationCallback;
import jakarta.security.auth.message.callback.PrivateKeyCallback;
import jakarta.security.auth.message.callback.SecretKeyCallback;
import jakarta.security.auth.message.callback.TrustStoreCallback;
import org.eclipse.jetty.ee10.security.jaspi.callback.CredentialValidationCallback;
import org.eclipse.jetty.ee10.servlet.security.LoginService;
import org.eclipse.jetty.ee10.servlet.security.UserIdentity;
import org.eclipse.jetty.ee10.servlet.security.authentication.LoginCallback;
import org.eclipse.jetty.ee10.servlet.security.authentication.LoginCallbackImpl;

/**
 * This {@link CallbackHandler} will bridge {@link Callback}s to handle to the given to the Jetty {@link LoginService}.
 */
public class ServletCallbackHandler implements CallbackHandler
{
    private final LoginService _loginService;
    private final ThreadLocal<CallerPrincipalCallback> _callerPrincipals = new ThreadLocal<>();
    private final ThreadLocal<GroupPrincipalCallback> _groupPrincipals = new ThreadLocal<>();

    public ServletCallbackHandler(LoginService loginService)
    {
        _loginService = loginService;
    }

    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException
    {
        for (Callback callback : callbacks)
        {
            // jaspi to server communication
            if (callback instanceof CallerPrincipalCallback)
            {
                _callerPrincipals.set((CallerPrincipalCallback)callback);
            }
            else if (callback instanceof GroupPrincipalCallback)
            {
                _groupPrincipals.set((GroupPrincipalCallback)callback);
            }
            else if (callback instanceof PasswordValidationCallback)
            {
                PasswordValidationCallback passwordValidationCallback = (PasswordValidationCallback)callback;
                @SuppressWarnings("unused")
                Subject subject = passwordValidationCallback.getSubject();

                UserIdentity user = _loginService.login(passwordValidationCallback.getUsername(), passwordValidationCallback.getPassword(), null);

                if (user != null)
                {
                    passwordValidationCallback.setResult(true);
                    passwordValidationCallback.getSubject().getPrincipals().addAll(user.getSubject().getPrincipals());
                    passwordValidationCallback.getSubject().getPrivateCredentials().add(user);
                }
            }
            else if (callback instanceof CredentialValidationCallback)
            {
                CredentialValidationCallback credentialValidationCallback = (CredentialValidationCallback)callback;
                Subject subject = credentialValidationCallback.getSubject();
                LoginCallback loginCallback = new LoginCallbackImpl(subject,
                    credentialValidationCallback.getUsername(),
                    credentialValidationCallback.getCredential());

                UserIdentity user = _loginService.login(credentialValidationCallback.getUsername(), credentialValidationCallback.getCredential(), null);

                if (user != null)
                {
                    loginCallback.setUserPrincipal(user.getUserPrincipal());
                    credentialValidationCallback.getSubject().getPrivateCredentials().add(loginCallback);
                    credentialValidationCallback.setResult(true);
                    credentialValidationCallback.getSubject().getPrincipals().addAll(user.getSubject().getPrincipals());
                    credentialValidationCallback.getSubject().getPrivateCredentials().add(user);
                }
            }
            // server to jaspi communication
            else if (callback instanceof CertStoreCallback)
            {
                // TODO implement this
            }
            else if (callback instanceof PrivateKeyCallback)
            {
                // TODO implement this
            }
            else if (callback instanceof SecretKeyCallback)
            {
                // TODO implement this
            }
            else if (callback instanceof TrustStoreCallback)
            {
                // TODO implement this
            }
            else
            {
                throw new UnsupportedCallbackException(callback);
            }
        }
    }

    public CallerPrincipalCallback getThreadCallerPrincipalCallback()
    {
        CallerPrincipalCallback callerPrincipalCallback = _callerPrincipals.get();
        _callerPrincipals.set(null);
        return callerPrincipalCallback;
    }

    public GroupPrincipalCallback getThreadGroupPrincipalCallback()
    {
        GroupPrincipalCallback groupPrincipalCallback = _groupPrincipals.get();
        _groupPrincipals.set(null);
        return groupPrincipalCallback;
    }
}
