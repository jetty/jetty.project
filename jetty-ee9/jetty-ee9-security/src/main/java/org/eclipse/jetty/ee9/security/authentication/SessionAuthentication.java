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

package org.eclipse.jetty.ee9.security.authentication;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import javax.security.auth.Subject;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionActivationListener;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionEvent;
import org.eclipse.jetty.ee9.security.AbstractUserAuthentication;
import org.eclipse.jetty.ee9.security.Authenticator;
import org.eclipse.jetty.ee9.security.SecurityHandler;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.NamePrincipal;
import org.eclipse.jetty.security.RolePrincipal;
import org.eclipse.jetty.security.UserIdentity;
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

    @Serial
    private static final long serialVersionUID = -4643200685888258706L;

    public static final String __J_AUTHENTICATED = "org.eclipse.jetty.security.UserIdentity";

    private transient HttpSession _session;
    private transient boolean _persistAuthenticationCredentials;
    private final String _name;
    private final Object _credentials;
    private final String[] _roles;

    public SessionAuthentication(String method, UserIdentity userIdentity, Object credentials)
    {
        this(method, userIdentity, credentials, false);
    }

    public SessionAuthentication(String method, UserIdentity userIdentity, Object credentials, boolean serializeCredentials)
    {
        super(method, userIdentity);
        _name = userIdentity.getUserPrincipal().getName();
        _roles = userIdentity.getRoles();
        _credentials = credentials;
        _persistAuthenticationCredentials = serializeCredentials;
    }

    @Override
    public UserIdentity getUserIdentity()
    {
        if (_userIdentity == null)
            throw new IllegalStateException("!UserIdentity");
        return super.getUserIdentity();
    }

    @Serial
    private void readObject(ObjectInputStream stream)
        throws IOException, ClassNotFoundException
    {
        stream.defaultReadObject();

        SecurityHandler securityHandler = SecurityHandler.getCurrentSecurityHandler();
        if (securityHandler == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("!SecurityHandler");
            return;
        }

        LoginService loginService;
        Authenticator authenticator = securityHandler.getAuthenticator();
        if (authenticator instanceof LoginAuthenticator loginAuthenticator)
        {
            loginService = loginAuthenticator.getLoginService();
            _persistAuthenticationCredentials = loginAuthenticator.isPersistAuthenticationCredentials();
        }
        else
        {
            loginService = securityHandler.getLoginService();
            _persistAuthenticationCredentials = securityHandler.isPersistAuthenticationCredentials();
        }

        // If _persistAuthenticationCredentials is true we must always try to re-login with the loginService.
        // If _roles is null, it is an old SessionAuthentication, and we should re-login with the LoginService.
        if (_persistAuthenticationCredentials || _roles == null)
        {
            if (loginService == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("!LoginService");
                return;
            }

            _userIdentity = loginService.login(_name, _credentials, null, null);
            if (LOG.isDebugEnabled())
                LOG.debug("Deserialized and relogged in {}", this);
        }
        else
        {
            IdentityService identityService = (loginService == null) ? securityHandler.getIdentityService() : loginService.getIdentityService();
            if (identityService == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("!IdentityService");
                return;
            }

            Subject subject = new Subject();
            NamePrincipal principal = new NamePrincipal(_name);
            subject.getPrincipals().add(principal);
            for (String role : _roles)
            {
                if (role != null)
                    subject.getPrincipals().add(new RolePrincipal(role));
            }
            subject.setReadOnly();

            _userIdentity = identityService.newUserIdentity(subject, principal, _roles);
            if (LOG.isDebugEnabled())
                LOG.debug("Deserialized {}", this);
        }
    }

    @Serial
    protected Object readResolve()
    {
        // A SessionAuthentication without a UserIdentity is invalid, and should be deserialized as null instead
        // of throwing ISE when getUserIdentity() is called, which would result in a 500 response.
        if (_userIdentity == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Dropping unrestorable authentication for {}", _name);
            return null;
        }
        return this;
    }

    @Serial
    private void writeObject(ObjectOutputStream out) throws IOException
    {
        ObjectOutputStream.PutField fields = out.putFields();
        fields.put("_name", _name);
        fields.put("_credentials", _persistAuthenticationCredentials ? _credentials : null);
        fields.put("_roles", _roles);
        out.writeFields();
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
