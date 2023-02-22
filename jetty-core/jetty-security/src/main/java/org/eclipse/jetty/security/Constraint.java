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

import java.util.Arrays;
import java.util.Collection;

/**
 * A Security Constraint interface.
 */
public interface Constraint
{
    boolean isForbidden();

    boolean isAuthenticationMandatory();

    UserDataConstraint getUserDataConstraint();

    Authorization getAuthorization();

    Collection<String> getRoles();

    enum Authorization
    {
        AUTHENTICATED,
        AUTHENTICATED_IN_KNOWN_ROLE,
        AUTHENTICATED_IN_ROLE,
    }

    Constraint NONE = null;
    Constraint INTEGRAL = from(false, true, UserDataConstraint.Integral, null);
    Constraint CONFIDENTAL = from(false, true, UserDataConstraint.Confidential, null);
    Constraint AUTHENTICATED = from(false, true, null, Authorization.AUTHENTICATED);
    Constraint AUTHENTICATED_IN_KNOWN_ROLE = from(false, true, null, Authorization.AUTHENTICATED_IN_KNOWN_ROLE);

    static Constraint combine(Constraint... constraints)
    {
        // TODO
        return null;
    }

    static Constraint from(String... roles)
    {
        return from(false, true, null, Authorization.AUTHENTICATED_IN_ROLE, roles);
    }

    static Constraint from(boolean forbidden, boolean authMandatory, UserDataConstraint userDataConstraint, Authorization authorization, String... roles)
    {
        return new Constraint()
        {
            @Override
            public boolean isForbidden()
            {
                return forbidden;
            }

            @Override
            public boolean isAuthenticationMandatory()
            {
                return authMandatory;
            }

            @Override
            public UserDataConstraint getUserDataConstraint()
            {
                return userDataConstraint;
            }

            @Override
            public Authorization getAuthorization()
            {
                return authorization;
            }

            @Override
            public Collection<String> getRoles()
            {
                return Arrays.asList(roles);
            }
        };
    }
}

/*
{
    private boolean _isAnyAuth;
    private boolean _isAnyRole;
    private boolean _mandatory;
    private boolean _forbidden;
    private UserDataConstraint _userDataConstraint;

    private final Set<String> _roles = new CopyOnWriteArraySet<>();

    public Constraint()
    {
    }

    public boolean isMandatory()
    {
        return _mandatory;
    }

    public void setMandatory(boolean mandatory)
    {
        this._mandatory = mandatory;
        if (!mandatory)
        {
            _forbidden = false;
            _roles.clear();
            _isAnyRole = false;
            _isAnyAuth = false;
        }
    }

    public boolean isForbidden()
    {
        return _forbidden;
    }

    public void setForbidden(boolean forbidden)
    {
        this._forbidden = forbidden;
        if (forbidden)
        {
            _mandatory = true;
            _userDataConstraint = null;
            _isAnyRole = false;
            _isAnyAuth = false;
            _roles.clear();
        }
    }

    public boolean isAnyRole()
    {
        return _isAnyRole;
    }

    public void setAnyRole(boolean anyRole)
    {
        this._isAnyRole = anyRole;
        if (anyRole)
            _mandatory = true;
    }

    public boolean isAnyAuth()
    {
        return _isAnyAuth;
    }

    public void setAnyAuth(boolean anyAuth)
    {
        this._isAnyAuth = anyAuth;
        if (anyAuth)
            _mandatory = true;
    }

    public UserDataConstraint getUserDataConstraint()
    {
        return _userDataConstraint;
    }

    public void setUserDataConstraint(UserDataConstraint userDataConstraint)
    {
        if (userDataConstraint == null)
            throw new NullPointerException("Null UserDataConstraint");
        if (this._userDataConstraint == null)
        {

            this._userDataConstraint = userDataConstraint;
        }
        else
        {
            this._userDataConstraint = this._userDataConstraint.combine(userDataConstraint);
        }
    }

    public Set<String> getRoles()
    {
        return _roles;
    }

    public void addRole(String role)
    {
        _roles.add(role);
    }

    public void combine(Constraint other)
    {
        if (other._forbidden)
            setForbidden(true);
        else if (other._mandatory)
        {
            setMandatory(true);
            if (other._isAnyAuth)
                setAnyAuth(true);
            if (other._isAnyRole)
                setAnyRole(true);

            _roles.addAll(other._roles);
        }
        setUserDataConstraint(other._userDataConstraint);
    }

    @Override
    public String toString()
    {
        return String.format("RoleInfo@%x{%s%s%s%s,%s}",
            hashCode(),
            (_forbidden ? "Forbidden," : ""),
            (_mandatory ? "Checked," : ""),
            (_isAnyAuth ? "AnyAuth," : ""),
            (_isAnyRole ? "*" : _roles),
            _userDataConstraint);
    }

 */
