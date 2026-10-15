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
import java.util.HashSet;
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
import org.eclipse.jetty.security.RolePrincipal;
import org.eclipse.jetty.security.UserIdentity;

import static org.eclipse.jetty.ee.security.jaspi.JaspiAuthenticator.UNAUTHENTICATED;

/**
 * <p>Bridges the {@link Callback}s a module issues to the Jetty {@link LoginService}.</p>
 * <p>The caller and group principals a module establishes with {@link CallerPrincipalCallback} and
 * {@link GroupPrincipalCallback} are added to {@link Subject#getPrincipals()} as the callbacks are
 * issued, and are accumulated in a private credential of the subject until {@link #getUserIdentity(Subject)}
 * builds the {@link JaspiUserIdentity} of the caller from them.</p>
 */
public class ServletCallbackHandler implements CallbackHandler
{
    private final LoginService _loginService;

    public ServletCallbackHandler(LoginService loginService)
    {
        _loginService = loginService;
    }

    /**
     * @param subject the subject the callbacks were issued for.
     * @return the identity of the caller the callbacks established, or null if none were issued.
     */
    public static JaspiUserIdentity getUserIdentity(Subject subject)
    {
        Caller caller = getCaller(subject);
        return caller == null ? null : caller.newUserIdentity(subject);
    }

    /**
     * Remove the caller and group principals the callbacks established in a subject, which is what
     * the {@code ServerAuth.cleanSubject} contract has a module remove from it.
     *
     * @param subject the subject the callbacks were issued for.
     */
    public static void cleanSubject(Subject subject)
    {
        Caller caller = getCaller(subject);
        if (caller != null)
            caller.clean(subject);
    }

    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException
    {
        for (Callback callback : callbacks)
        {
            switch (callback)
            {
                case CallerPrincipalCallback callerPrincipalCallback ->
                {
                    Principal principal;
                    if (callerPrincipalCallback.getPrincipal() != null)
                        principal = callerPrincipalCallback.getPrincipal();
                    else if (callerPrincipalCallback.getName() != null)
                        principal = new NamePrincipal(callerPrincipalCallback.getName());
                    else
                        principal = UNAUTHENTICATED;

                    Subject subject = callerPrincipalCallback.getSubject();
                    getOrCreateCaller(subject).setUserPrincipal(subject, principal);
                }
                case GroupPrincipalCallback groupPrincipalCallback ->
                {
                    Subject subject = groupPrincipalCallback.getSubject();
                    getOrCreateCaller(subject).addRoles(subject, groupPrincipalCallback.getGroups());
                }
                case PasswordValidationCallback passwordValidationCallback ->
                {
                    UserIdentity userIdentity = _loginService.login(passwordValidationCallback.getUsername(), passwordValidationCallback.getPassword(), null, null);
                    passwordValidationCallback.setResult(userIdentity != null);
                    setValidatedCaller(passwordValidationCallback.getSubject(), userIdentity);
                }
                case CredentialValidationCallback credentialValidationCallback ->
                {
                    UserIdentity userIdentity = _loginService.login(credentialValidationCallback.getUsername(), credentialValidationCallback.getCredential(), null, null);
                    credentialValidationCallback.setResult(userIdentity != null);
                    setValidatedCaller(credentialValidationCallback.getSubject(), userIdentity);
                }
                case null, default -> throw new UnsupportedCallbackException(callback);
            }
        }
    }

    /**
     * Establish the caller the validated credentials identify, and its roles, as a
     * {@link CallerPrincipalCallback} naming that caller and a {@link GroupPrincipalCallback}
     * establishing its groups would, so that a module which validates credentials and establishes
     * no caller of its own is still answered with the one it validated.
     */
    private static void setValidatedCaller(Subject subject, UserIdentity loginIdentity)
    {
        Caller caller = getOrCreateCaller(subject);
        if (loginIdentity != null)
        {
            caller.setUserPrincipal(subject, loginIdentity.getUserPrincipal());
            caller.addRoles(subject, loginIdentity.getRoles());
        }
    }

    private static Caller getCaller(Subject subject)
    {
        Set<Caller> callers = subject.getPrivateCredentials(Caller.class);
        if (callers.isEmpty())
            return null;
        if (callers.size() > 1)
            throw new IllegalStateException("Multiple callers established in the subject");
        return callers.iterator().next();
    }

    private static Caller getOrCreateCaller(Subject subject)
    {
        Caller caller = getCaller(subject);
        if (caller == null)
        {
            caller = new Caller();
            subject.getPrivateCredentials().add(caller);
        }
        return caller;
    }

    /**
     * The caller a module is establishing for a subject, accumulated as the callbacks are issued so
     * that the {@link JaspiUserIdentity} can be built from them once they are done.
     */
    private static class Caller
    {
        private final Set<String> _roles = new HashSet<>();
        private Principal _userPrincipal;
        private boolean _groupsEstablished;

        /**
         * Establish the caller principal in the subject, replacing any previously established for
         * this caller. The unauthenticated caller has no principal to distinguish, so nothing is
         * added for it.
         */
        private void setUserPrincipal(Subject subject, Principal userPrincipal)
        {
            if (_userPrincipal != null)
                subject.getPrincipals().remove(_userPrincipal);
            _userPrincipal = userPrincipal;
            if (userPrincipal != null && userPrincipal != UNAUTHENTICATED)
                subject.getPrincipals().add(userPrincipal);
        }

        /**
         * Establish group principals in the subject, as the union of those already established for
         * this caller and the given names. A null array establishes no group principals, so those
         * already established are removed.
         */
        private void addRoles(Subject subject, String[] roles)
        {
            _groupsEstablished = true;
            if (roles == null)
            {
                subject.getPrincipals().removeIf(principal -> principal instanceof RolePrincipal && _roles.contains(principal.getName()));
                _roles.clear();
                return;
            }

            for (String role : roles)
            {
                if (role != null && _roles.add(role))
                    subject.getPrincipals().add(new RolePrincipal(role));
            }
        }

        private JaspiUserIdentity newUserIdentity(Subject subject)
        {
            return new JaspiUserIdentity(subject, _userPrincipal, _roles, _groupsEstablished);
        }

        private void clean(Subject subject)
        {
            subject.getPrincipals().removeIf(principal ->
                principal.equals(_userPrincipal) || (principal instanceof RolePrincipal && _roles.contains(principal.getName())));
            subject.getPrivateCredentials().remove(this);
        }
    }
}
