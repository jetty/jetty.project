//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.message.callback.CallerPrincipalCallback;
import javax.security.auth.message.callback.CertStoreCallback;
import javax.security.auth.message.callback.GroupPrincipalCallback;
import javax.security.auth.message.callback.PasswordValidationCallback;
import javax.security.auth.message.callback.PrivateKeyCallback;
import javax.security.auth.message.callback.SecretKeyCallback;
import javax.security.auth.message.callback.TrustStoreCallback;

import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.authentication.LoginCallback;
import org.eclipse.jetty.security.authentication.LoginCallbackImpl;
import org.eclipse.jetty.security.jaspi.callback.CredentialValidationCallback;
import org.eclipse.jetty.server.UserIdentity;

/**
 * Idiot class required by jaspi stupidity
 */
public class ServletCallbackHandler implements CallbackHandler
{
    private final LoginService _loginService;

    private final ThreadLocal<CallerPrincipalCallback> _callerPrincipals = new ThreadLocal<CallerPrincipalCallback>();
    private final ThreadLocal<GroupPrincipalCallback> _groupPrincipals = new ThreadLocal<GroupPrincipalCallback>();

    public ServletCallbackHandler(LoginService loginService)
    {
        _loginService = loginService;
    }

    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException
    {
        for (Callback callback : callbacks)
        {
            // jaspi to server communication
            if (callback instanceof CallerPrincipalCallback)
            {
                _callerPrincipals.set((CallerPrincipalCallback) callback);
            }
            else if (callback instanceof GroupPrincipalCallback)
            {
                _groupPrincipals.set((GroupPrincipalCallback) callback);
            }
            else if (callback instanceof PasswordValidationCallback)
            {
                PasswordValidationCallback passwordValidationCallback = (PasswordValidationCallback) callback;
                Subject subject = passwordValidationCallback.getSubject();

                UserIdentity user = _loginService.login(passwordValidationCallback.getUsername(),passwordValidationCallback.getPassword(), null);
                
                if (user!=null)
                {
                    passwordValidationCallback.setResult(true);
                    passwordValidationCallback.getSubject().getPrincipals().addAll(user.getSubject().getPrincipals());
                    passwordValidationCallback.getSubject().getPrivateCredentials().add(user);
                }
            }
            else if (callback instanceof CredentialValidationCallback)
            {
                CredentialValidationCallback credentialValidationCallback = (CredentialValidationCallback) callback;
                Subject subject = credentialValidationCallback.getSubject();
                LoginCallback loginCallback = new LoginCallbackImpl(subject,
                        credentialValidationCallback.getUsername(),
                        credentialValidationCallback.getCredential());

                UserIdentity user = _loginService.login(credentialValidationCallback.getUsername(),credentialValidationCallback.getCredential(), null);

                if (user!=null)
                {
                    loginCallback.setUserPrincipal(user.getUserPrincipal());
                    credentialValidationCallback.getSubject().getPrivateCredentials().add(loginCallback);
                    credentialValidationCallback.setResult(true);
                    credentialValidationCallback.getSubject().getPrincipals().addAll(user.getSubject().getPrincipals());
                    credentialValidationCallback.getSubject().getPrivateCredentials().add(user);
                }
            }
            // server to jaspi communication
            // TODO implement these
            else if (callback instanceof CertStoreCallback)
            {
            }
            else if (callback instanceof PrivateKeyCallback)
            {
            }
            else if (callback instanceof SecretKeyCallback)
            {
            }
            else if (callback instanceof TrustStoreCallback)
            {
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
