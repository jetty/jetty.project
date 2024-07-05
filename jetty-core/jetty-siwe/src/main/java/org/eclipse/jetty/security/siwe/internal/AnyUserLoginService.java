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

package org.eclipse.jetty.security.siwe.internal;

import java.util.function.Function;
import javax.security.auth.Subject;

import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.security.UserPrincipal;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Session;

public class AnyUserLoginService implements LoginService
{
    private final String _realm;
    private final LoginService _loginService;
    private IdentityService _identityService;
    private boolean _authenticateNewUsers;

    public AnyUserLoginService(String realm)
    {
        this(realm, null);
    }

    public AnyUserLoginService(String realm, LoginService loginService)
    {
        _realm = realm;
        _loginService = loginService;
        _identityService = (loginService == null) ? new DefaultIdentityService() : null;
    }

    /**
     * This setting is only meaningful if a wrapped {@link LoginService} has been set.
     * <p>
     * If set to true, any users not found by the wrapped {@link LoginService} will still
     * be authenticated but with no roles, if set to false users will not be
     * authenticated unless they are discovered by the wrapped {@link LoginService}.
     * </p>
     * @param authenticateNewUsers whether to authenticate users not found by a wrapping LoginService
     */
    public void setAuthenticateNewUsers(boolean authenticateNewUsers)
    {
        this._authenticateNewUsers = authenticateNewUsers;
    }

    @Override
    public String getName()
    {
        return _realm;
    }

    @Override
    public UserIdentity login(String username, Object credentials, Request request, Function<Boolean, Session> getOrCreateSession)
    {
        UserPrincipal userPrincipal = new UserPrincipal(username, null);
        Subject subject = new Subject();
        subject.getPrincipals().add(userPrincipal);
        subject.getPrivateCredentials().add(credentials);
        subject.setReadOnly();

        if (_loginService != null)
            return _loginService.getUserIdentity(subject, userPrincipal, _authenticateNewUsers);
        return _identityService.newUserIdentity(subject, userPrincipal, new String[0]);
    }

    @Override
    public boolean validate(UserIdentity user)
    {
        if (_loginService == null)
            return user != null;
        return _loginService.validate(user);
    }

    @Override
    public IdentityService getIdentityService()
    {
        return _loginService == null ? _identityService : _loginService.getIdentityService();
    }

    @Override
    public void setIdentityService(IdentityService service)
    {
        if (_loginService != null)
            _loginService.setIdentityService(service);
        else
            _identityService = service;
    }

    @Override
    public void logout(UserIdentity user)
    {
        if (_loginService != null)
            _loginService.logout(user);
    }
}
