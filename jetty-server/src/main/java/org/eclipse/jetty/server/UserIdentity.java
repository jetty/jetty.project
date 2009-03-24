// ========================================================================
// Copyright (c) 1996-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;
import java.security.Principal;
import java.util.Map;

import javax.security.auth.Subject;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

/* ------------------------------------------------------------ */
/** User object that encapsulates user identity and operations such as run-as-role actions, 
 * checking isUserInRole and getUserPrincipal.
 *
 * Implementations of UserIdentity should be immutable so that they may be
 * cached by Authenticators and LoginServices.
 *
 */
public interface UserIdentity
{
    final static String[] NO_ROLES = new String[]{}; 
    
    /* ------------------------------------------------------------ */
    /**
     * @return The user subject
     */
    Subject getSubject();

    /* ------------------------------------------------------------ */
    /**
     * @return The user principal
     */
    Principal getUserPrincipal();

    /* ------------------------------------------------------------ */
    /**
     * @return The users roles
     */
    String[] getRoles();

    /* ------------------------------------------------------------ */
    /** Check if the user is in a role.
     * This call is used to satisfy authorization calls from 
     * container code which will be using translated role names.
     * @param role A role name.
     * @return True if the user can act in that role.
     */
    boolean isUserInRole(String role);
    

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     * A UserIdentity Scope.
     * A scope is the environment in which a User Identity is to 
     * be interpreted. Typically it is set by the target servlet of 
     * a request.
     * @see org.eclipse.jetty.servlet.ServletHolder
     */
    interface Scope
    {
        /* ------------------------------------------------------------ */
        /**
         * @return The context path that the identity is being considered within
         */
        String getContextPath();
        
        /* ------------------------------------------------------------ */
        /**
         * @return The name of the identity context. Typically this is the servlet name.
         */
        String getName();
        
        /* ------------------------------------------------------------ */
        /**
         * @return The name of a runAs entity. Typically this is a runAs role applied to a servlet.
         */
        String getRunAsRole();
        
        /* ------------------------------------------------------------ */
        /**
         * @return A map of role reference names that converts from names used by application code
         * to names used by the context deployment.
         */
        Map<String,String> getRoleRefMap();
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public interface UnauthticatedUserIdentity extends UserIdentity
    {
        UserIdentity login(ServletRequest request, ServletResponse response);
        UserIdentity login(String username, String password);
    };
    
    public static final UserIdentity UNAUTHENTICATED_IDENTITY = new UserIdentity()
    {
        public Subject getSubject()
        {
            return null;
        }
        
        public Principal getUserPrincipal()
        {
            return null;
        }
        
        public String[] getRoles()
        {
            return NO_ROLES;
        }
        
        public boolean isUserInRole(String role)
        {
            return false;
        }
        
        public String toString()
        {
            return "UNAUTHENTICATED";
        }
    };
}
