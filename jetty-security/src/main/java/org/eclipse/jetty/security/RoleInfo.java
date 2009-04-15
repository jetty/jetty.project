// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.security;

import java.util.Arrays;

import org.eclipse.jetty.util.LazyList;

/**
 * 
 * Badly named class that holds the role and user data constraint info for a
 * path/http method combination, extracted and combined from security
 * constraints.
 * 
 * @version $Rev: 4793 $ $Date: 2009-03-19 00:00:01 +0100 (Thu, 19 Mar 2009) $
 */
public class RoleInfo
{
    private final static String[] NO_ROLES={};
    private boolean _isAnyRole;
    private boolean _checked;
    private boolean _forbidden;
    private UserDataConstraint _userDataConstraint;

    private String[] _roles = NO_ROLES;

    public RoleInfo()
    {    
    }
    
    public boolean isChecked()
    {
        return _checked;
    }

    public void setChecked(boolean checked)
    {
        this._checked = checked;
        if (!checked)
        {
            _forbidden=false;
            _roles=NO_ROLES;
            _isAnyRole=false;
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
            _checked = true;
            _userDataConstraint = null;
            _isAnyRole=false;
            _roles=NO_ROLES;
        }
    }

    public boolean isAnyRole()
    {
        return _isAnyRole;
    }

    public void setAnyRole(boolean anyRole)
    {
        this._isAnyRole=anyRole;
        if (anyRole)
        {
            _checked = true;
            _roles=NO_ROLES;
        }
    }

    public UserDataConstraint getUserDataConstraint()
    {
        return _userDataConstraint;
    }

    public void setUserDataConstraint(UserDataConstraint userDataConstraint)
    {
        if (userDataConstraint == null) throw new NullPointerException("Null UserDataConstraint");
        if (this._userDataConstraint == null)
        {
            this._userDataConstraint = userDataConstraint;
        }
        else
        {
            this._userDataConstraint = this._userDataConstraint.combine(userDataConstraint);
        }
    }

    public String[] getRoles()
    {
        return _roles;
    }
    
    public void addRole(String role)
    {
        _roles=(String[])LazyList.addToArray(_roles,role,String.class);
    }

    public void combine(RoleInfo other)
    {
        if (other._forbidden)
            setForbidden(true);
        else if (!other._checked) // TODO is this the right way around???
            setChecked(true);
        else if (other._isAnyRole)
            setAnyRole(true);
        else if (!_isAnyRole)
        {
            for (String r : other._roles)
                _roles=(String[])LazyList.addToArray(_roles,r,String.class);
        }
        
        setUserDataConstraint(other._userDataConstraint);
    }
    
    public String toString()
    {
        return "{RoleInfo"+(_forbidden?",F":"")+(_checked?",C":"")+(_isAnyRole?",*":Arrays.asList(_roles).toString())+"}";
    }
}
