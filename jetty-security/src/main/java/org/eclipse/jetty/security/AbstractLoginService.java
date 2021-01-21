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

package org.eclipse.jetty.security;

import java.io.Serializable;
import java.security.Principal;
import javax.security.auth.Subject;
import javax.servlet.ServletRequest;

import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.security.Credential;

/**
 * AbstractLoginService
 */
public abstract class AbstractLoginService extends ContainerLifeCycle implements LoginService
{
    private static final Logger LOG = Log.getLogger(AbstractLoginService.class);

    protected IdentityService _identityService = new DefaultIdentityService();
    protected String _name;
    protected boolean _fullValidate = false;

    /**
     * RolePrincipal
     */
    public static class RolePrincipal implements Principal, Serializable
    {
        private static final long serialVersionUID = 2998397924051854402L;
        private final String _roleName;

        public RolePrincipal(String name)
        {
            _roleName = name;
        }

        @Override
        public String getName()
        {
            return _roleName;
        }
    }

    /**
     * UserPrincipal
     */
    public static class UserPrincipal implements Principal, Serializable
    {
        private static final long serialVersionUID = -6226920753748399662L;
        private final String _name;
        private final Credential _credential;

        public UserPrincipal(String name, Credential credential)
        {
            _name = name;
            _credential = credential;
        }

        public boolean authenticate(Object credentials)
        {
            return _credential != null && _credential.check(credentials);
        }

        public boolean authenticate(Credential c)
        {
            return (_credential != null && c != null && _credential.equals(c));
        }

        @Override
        public String getName()
        {
            return _name;
        }

        @Override
        public String toString()
        {
            return _name;
        }
    }

    protected abstract String[] loadRoleInfo(UserPrincipal user);

    protected abstract UserPrincipal loadUserInfo(String username);

    protected AbstractLoginService()
    {
        addBean(_identityService);
    }

    /**
     * @see org.eclipse.jetty.security.LoginService#getName()
     */
    @Override
    public String getName()
    {
        return _name;
    }

    /**
     * Set the identityService.
     *
     * @param identityService the identityService to set
     */
    @Override
    public void setIdentityService(IdentityService identityService)
    {
        if (isRunning())
            throw new IllegalStateException("Running");
        updateBean(_identityService, identityService);
        _identityService = identityService;
    }

    /**
     * Set the name.
     *
     * @param name the name to set
     */
    public void setName(String name)
    {
        if (isRunning())
            throw new IllegalStateException("Running");
        _name = name;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[%s]", this.getClass().getSimpleName(), hashCode(), _name);
    }

    /**
     * @see org.eclipse.jetty.security.LoginService#login(java.lang.String, java.lang.Object, javax.servlet.ServletRequest)
     */
    @Override
    public UserIdentity login(String username, Object credentials, ServletRequest request)
    {
        if (username == null)
            return null;

        UserPrincipal userPrincipal = loadUserInfo(username);
        if (userPrincipal != null && userPrincipal.authenticate(credentials))
        {
            //safe to load the roles
            String[] roles = loadRoleInfo(userPrincipal);

            Subject subject = new Subject();
            subject.getPrincipals().add(userPrincipal);
            subject.getPrivateCredentials().add(userPrincipal._credential);
            if (roles != null)
                for (String role : roles)
                {
                    subject.getPrincipals().add(new RolePrincipal(role));
                }
            subject.setReadOnly();
            return _identityService.newUserIdentity(subject, userPrincipal, roles);
        }

        return null;
    }

    /**
     * @see org.eclipse.jetty.security.LoginService#validate(org.eclipse.jetty.server.UserIdentity)
     */
    @Override
    public boolean validate(UserIdentity user)
    {
        if (!isFullValidate())
            return true; //if we have a user identity it must be valid

        //Do a full validation back against the user store     
        UserPrincipal fresh = loadUserInfo(user.getUserPrincipal().getName());
        if (fresh == null)
            return false; //user no longer exists

        if (user.getUserPrincipal() instanceof UserPrincipal)
        {
            return fresh.authenticate(((UserPrincipal)user.getUserPrincipal())._credential);
        }

        throw new IllegalStateException("UserPrincipal not KnownUser"); //can't validate
    }

    /**
     * @see org.eclipse.jetty.security.LoginService#getIdentityService()
     */
    @Override
    public IdentityService getIdentityService()
    {
        return _identityService;
    }

    /**
     * @see org.eclipse.jetty.security.LoginService#logout(org.eclipse.jetty.server.UserIdentity)
     */
    @Override
    public void logout(UserIdentity user)
    {
        //Override in subclasses

    }

    public boolean isFullValidate()
    {
        return _fullValidate;
    }

    public void setFullValidate(boolean fullValidate)
    {
        _fullValidate = fullValidate;
    }
}
