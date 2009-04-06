// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
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

import java.security.Principal;

import javax.security.auth.Subject;

import org.eclipse.jetty.http.security.Constraint;
import org.eclipse.jetty.security.Authentication.Status;
import org.eclipse.jetty.server.UserIdentity;


/* ------------------------------------------------------------ */
/**
 * The default implementation of UserIdentity.
 *
 */
public class DefaultUserIdentity implements UserIdentity
{
    /* Cache successful authentications for BASIC and DIGEST to avoid creation on every request */
    public final Authentication SUCCESSFUL_BASIC = new DefaultAuthentication(Status.SUCCESS,Constraint.__BASIC_AUTH,this);
    public final Authentication SUCCESSFUL_DIGEST = new DefaultAuthentication(Status.SUCCESS,Constraint.__BASIC_AUTH,this);
    
    private final Subject _subject;
    private final Principal _userPrincipal;
    private final String[] _roles;
    
    public DefaultUserIdentity(Subject subject, Principal userPrincipal, String[] roles)
    {
        _subject=subject;
        _userPrincipal=userPrincipal;
        _roles=roles;
    }

    public String[] getRoles()
    {
        return _roles;
    }

    public Subject getSubject()
    {
        return _subject;
    }

    public Principal getUserPrincipal()
    {
        return _userPrincipal;
    }

    public boolean isUserInRole(String role)
    {
        for (String r :_roles)
            if (r.equals(role))
                return true;
        return false;
    }

    public String toString()
    {
        return DefaultUserIdentity.class.getSimpleName()+"('"+_userPrincipal+"')";
    }
    
}
