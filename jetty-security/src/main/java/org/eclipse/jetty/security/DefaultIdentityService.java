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

import java.security.Principal;
import java.util.Map;

import javax.security.auth.Subject;

import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.server.UserIdentity.Scope;


/* ------------------------------------------------------------ */
/**
 * Default Identity Service implementation.
 * This service handles only role reference maps passed in an
 * associated {@link UserIdentity.Scope}.  If there are roles
 * refs present, then associate will wrap the UserIdentity with one
 * that uses the role references in the {@link UserIdentity#isUserInRole(String)}
 * implementation. All other operations are effectively noops.
 *
 */
public class DefaultIdentityService implements IdentityService
{
    public DefaultIdentityService()
    {
    }
    
    /* ------------------------------------------------------------ */
    /** 
     * If there are roles refs present in the scope, then wrap the UserIdentity 
     * with one that uses the role references in the {@link UserIdentity#isUserInRole(String)}
     */
    public UserIdentity associate(UserIdentity user, Scope scope)
    {
        Map<String,String> roleRefMap=scope.getRoleRefMap();
        if (roleRefMap!=null && roleRefMap.size()>0)
            return new RoleRefUserIdentity(user,roleRefMap);
        return user;
    }

    public void disassociate(UserIdentity scoped)
    {
    }

    public Object associateRunAs(UserIdentity user, RunAsToken token)
    {
        return token;
    }

    public void disassociateRunAs(Object lastToken)
    {
    }
    
    public RunAsToken newRunAsToken(String runAsName)
    {
        return new RoleRunAsToken(runAsName);
    }

    public UserIdentity getSystemUserIdentity()
    {
        return null;
    }

    public UserIdentity newUserIdentity(final Subject subject, final Principal userPrincipal, final String[] roles)
    {
        return new DefaultUserIdentity(subject,userPrincipal,roles);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Wrapper UserIdentity used to apply RoleRef map.
     *
     */
    public static class RoleRefUserIdentity implements UserIdentity
    {
        final private UserIdentity _delegate;
        final private Map<String,String> _roleRefMap;

        public RoleRefUserIdentity(final UserIdentity user, final Map<String, String> roleRefMap)
        {
            _delegate=user;
            _roleRefMap=roleRefMap;
        }

        public String[] getRoles()
        {
            return _delegate.getRoles();
        }
        
        public Subject getSubject()
        {
            return _delegate.getSubject();
        }

        public Principal getUserPrincipal()
        {
            return _delegate.getUserPrincipal();
        }

        public boolean isUserInRole(String role)
        {
            String link=_roleRefMap.get(role);
            return _delegate.isUserInRole(link==null?role:link);
        }
    }
}
