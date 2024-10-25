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

package org.eclipse.jetty.security;

import java.util.function.Function;
import javax.security.auth.Subject;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Session;

/**
 * A {@link LoginService} which allows unknown users to be authenticated.
 * <p>
 * This can delegate to a nested {@link LoginService} if it is supplied to the constructor, it will first attempt to log in
 * with the nested {@link LoginService} and only create a new {@link UserIdentity} if none was found with
 * {@link LoginService#login(String, Object, Request, Function)}.
 * </p>
 * <p>This {@link LoginService} does not check credentials, a {@link UserIdentity} will be produced for any
 * username provided in {@link #login(String, Object, Request, Function)}.</p>
 */
public class AnyUserLoginService implements LoginService
{
    private final String _realm;
    private final LoginService _loginService;
    private IdentityService _identityService;

    /**
     * @param realm the realm name.
     * @param loginService optional {@link LoginService} which can be used to assign roles to known users.
     */
    public AnyUserLoginService(String realm, LoginService loginService)
    {
        _realm = realm;
        _loginService = loginService;
        _identityService = (loginService == null) ? new DefaultIdentityService() : null;
    }

    @Override
    public String getName()
    {
        return _realm;
    }

    @Override
    public UserIdentity login(String username, Object credentials, Request request, Function<Boolean, Session> getOrCreateSession)
    {
        if (_loginService != null)
        {
            UserIdentity login = _loginService.login(username, credentials, request, getOrCreateSession);
            if (login != null)
                return login;

            UserPrincipal userPrincipal = new UserPrincipal(username, null);
            Subject subject = new Subject();
            subject.getPrincipals().add(userPrincipal);
            if (credentials != null)
                subject.getPrivateCredentials().add(credentials);
            subject.setReadOnly();
            return _loginService.getUserIdentity(subject, userPrincipal, true);
        }

        UserPrincipal userPrincipal = new UserPrincipal(username, null);
        Subject subject = new Subject();
        subject.getPrincipals().add(userPrincipal);
        if (credentials != null)
            subject.getPrivateCredentials().add(credentials);
        subject.setReadOnly();
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
