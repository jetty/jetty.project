//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.util.security;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Constraint
 *
 * Describe an auth and/or data constraint.
 */
public class Constraint implements Cloneable, Serializable
{

    public static final String __BASIC_AUTH = "BASIC";
    public static final String __FORM_AUTH = "FORM";
    public static final String __DIGEST_AUTH = "DIGEST";
    public static final String __CERT_AUTH = "CLIENT_CERT";
    public static final String __CERT_AUTH2 = "CLIENT-CERT";
    public static final String __SPNEGO_AUTH = "SPNEGO";
    public static final String __NEGOTIATE_AUTH = "NEGOTIATE";
    public static final String __OPENID_AUTH = "OPENID";

    public static boolean validateMethod(String method)
    {
        if (method == null)
            return false;
        method = method.trim();
        return (method.equals(__FORM_AUTH) ||
            method.equals(__BASIC_AUTH) ||
            method.equals(__DIGEST_AUTH) ||
            method.equals(__CERT_AUTH) ||
            method.equals(__CERT_AUTH2) ||
            method.equals(__SPNEGO_AUTH) ||
            method.equals(__NEGOTIATE_AUTH) ||
            method.equals(__OPENID_AUTH));
    }

    public static final int DC_UNSET = -1;
    public static final int DC_NONE = 0;
    public static final int DC_INTEGRAL = 1;
    public static final int DC_CONFIDENTIAL = 2;
    public static final int DC_FORBIDDEN = 3;

    public static final String NONE = "NONE";

    public static final String ANY_ROLE = "*";

    public static final String ANY_AUTH = "**"; //Servlet Spec 3.1 pg 140

    private String _name;

    private String[] _roles;

    private int _dataConstraint = DC_UNSET;

    private boolean _anyRole = false;

    private boolean _anyAuth = false;

    private boolean _authenticate = false;

    /**
     * Constructor.
     */
    public Constraint()
    {
    }

    /**
     * Convenience Constructor.
     *
     * @param name the name
     * @param role the role
     */
    public Constraint(String name, String role)
    {
        setName(name);
        setRoles(new String[]{role});
    }

    @Override
    public Object clone() throws CloneNotSupportedException
    {
        return super.clone();
    }

    /**
     * @param name the name
     */
    public void setName(String name)
    {
        _name = name;
    }

    public String getName()
    {
        return _name;
    }

    public void setRoles(String[] roles)
    {
        _roles = roles;
        _anyRole = false;
        _anyAuth = false;
        if (roles != null)
        {
            for (int i = roles.length; i-- > 0; )
            {
                _anyRole |= ANY_ROLE.equals(roles[i]);
                _anyAuth |= ANY_AUTH.equals(roles[i]);
            }
        }
    }

    /**
     * @return True if any user role is permitted.
     */
    public boolean isAnyRole()
    {
        return _anyRole;
    }

    /**
     * Servlet Spec 3.1, pg 140
     *
     * @return True if any authenticated user is permitted (ie a role "**" was specified in the constraint).
     */
    public boolean isAnyAuth()
    {
        return _anyAuth;
    }

    /**
     * @return List of roles for this constraint.
     */
    public String[] getRoles()
    {
        return _roles;
    }

    /**
     * @param role the role
     * @return True if the constraint contains the role.
     */
    public boolean hasRole(String role)
    {
        if (_anyRole)
            return true;
        if (_roles != null)
            for (int i = _roles.length; i-- > 0; )
            {
                if (role.equals(_roles[i]))
                    return true;
            }
        return false;
    }

    /**
     * @param authenticate True if users must be authenticated
     */
    public void setAuthenticate(boolean authenticate)
    {
        _authenticate = authenticate;
    }

    /**
     * @return True if the constraint requires request authentication
     */
    public boolean getAuthenticate()
    {
        return _authenticate;
    }

    /**
     * @return True if authentication required but no roles set
     */
    public boolean isForbidden()
    {
        return _authenticate && !_anyRole && (_roles == null || _roles.length == 0);
    }

    /**
     * @param c Data constrain indicator: 0=DC+NONE, 1=DC_INTEGRAL &amp;
     * 2=DC_CONFIDENTIAL
     */
    public void setDataConstraint(int c)
    {
        if (c < 0 || c > DC_CONFIDENTIAL)
            throw new IllegalArgumentException("Constraint out of range");
        _dataConstraint = c;
    }

    /**
     * @return Data constrain indicator: 0=DC+NONE, 1=DC_INTEGRAL &amp;
     * 2=DC_CONFIDENTIAL
     */
    public int getDataConstraint()
    {
        return _dataConstraint;
    }

    /**
     * @return True if a data constraint has been set.
     */
    public boolean hasDataConstraint()
    {
        return _dataConstraint >= DC_NONE;
    }

    @Override
    public String toString()
    {
        return "SC{" +
            _name + "," +
            (_anyRole ? "*" : (_roles == null ? "-" : Arrays.asList(_roles).toString())) + "," +
            (_dataConstraint == DC_UNSET ? "DC_UNSET}" : (_dataConstraint == DC_NONE ? "NONE}" : (_dataConstraint == DC_INTEGRAL ? "INTEGRAL}" : "CONFIDENTIAL}")));
    }
}
