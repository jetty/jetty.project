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

package org.eclipse.jetty.security.authentication;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionActivationListener;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionEvent;
import org.eclipse.jetty.security.AbstractUserAuthentication;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.UserIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SessionAuthentication
 *
 * When a user has been successfully authenticated with some types
 * of Authenticator, the Authenticator stashes a SessionAuthentication
 * into an HttpSession to remember that the user is authenticated.
 */
public class SessionAuthentication extends AbstractUserAuthentication
    implements Serializable, HttpSessionActivationListener, HttpSessionBindingListener
{
    private static final Logger LOG = LoggerFactory.getLogger(SessionAuthentication.class);

    private static final long serialVersionUID = -4643200685888258706L;

    public static final String __J_AUTHENTICATED = "org.eclipse.jetty.security.UserIdentity";

    private final String _name;
    private final Object _credentials;
    private transient HttpSession _session;

    public SessionAuthentication(String method, UserIdentity userIdentity, Object credentials)
    {
        super(method, userIdentity);
        _name = userIdentity.getUserPrincipal().getName();
        _credentials = credentials;
    }

    @Override
    public UserIdentity getUserIdentity()
    {
        if (_userIdentity == null)
            throw new IllegalStateException("!UserIdentity");
        return super.getUserIdentity();
    }

    private void readObject(ObjectInputStream stream)
        throws IOException, ClassNotFoundException
    {
        stream.defaultReadObject();

        SecurityHandler security = SecurityHandler.getCurrentSecurityHandler();
        if (security == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("!SecurityHandler");
            return;
        }

        LoginService loginService;
        Authenticator authenticator = security.getAuthenticator();
        if (authenticator instanceof LoginAuthenticator)
            loginService = ((LoginAuthenticator)authenticator).getLoginService();
        else
            loginService = security.getLoginService();

        if (loginService == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("!LoginService");
            return;
        }

        _userIdentity = loginService.login(_name, _credentials, null);
        LOG.debug("Deserialized and relogged in {}", this);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s,%s}", this.getClass().getSimpleName(), hashCode(), _session == null ? "-" : _session.getId(), _userIdentity);
    }

    @Override
    public void sessionWillPassivate(HttpSessionEvent se)
    {
    }

    @Override
    public void sessionDidActivate(HttpSessionEvent se)
    {
        if (_session == null)
        {
            _session = se.getSession();
        }
    }
}
