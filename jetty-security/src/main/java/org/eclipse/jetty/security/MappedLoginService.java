//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.io.Serializable;
import java.security.Principal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.security.auth.Subject;

import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.security.Credential;



/* ------------------------------------------------------------ */
/**
 * A login service that keeps UserIdentities in a concurrent map
 * either as the source or a cache of the users.
 * 
 */
public abstract class MappedLoginService extends AbstractLifeCycle implements LoginService
{
    private static final Logger LOG = Log.getLogger(MappedLoginService.class);

    protected IdentityService _identityService=new DefaultIdentityService();
    protected String _name;
    protected final ConcurrentMap<String, UserIdentity> _users=new ConcurrentHashMap<String, UserIdentity>();

    /* ------------------------------------------------------------ */
    protected MappedLoginService()
    {
    }
    
    /* ------------------------------------------------------------ */
    /** Get the name.
     * @return the name
     */
    public String getName()
    {
        return _name;
    }
    
    /* ------------------------------------------------------------ */
    /** Get the identityService.
     * @return the identityService
     */
    public IdentityService getIdentityService()
    {
        return _identityService;
    }
    
    /* ------------------------------------------------------------ */
    /** Get the users.
     * @return the users
     */
    public ConcurrentMap<String, UserIdentity> getUsers()
    {
        return _users;
    }
    
    /* ------------------------------------------------------------ */
    /** Set the identityService.
     * @param identityService the identityService to set
     */
    public void setIdentityService(IdentityService identityService)
    {
        if (isRunning())
            throw new IllegalStateException("Running");
        _identityService = identityService;
    }

    /* ------------------------------------------------------------ */
    /** Set the name.
     * @param name the name to set
     */
    public void setName(String name)
    {
        if (isRunning())
            throw new IllegalStateException("Running");
        _name = name;
    }

    /* ------------------------------------------------------------ */
    /** Set the users.
     * @param users the users to set
     */
    public void setUsers(Map<String, UserIdentity> users)
    {
        if (isRunning())
            throw new IllegalStateException("Running");
        _users.clear();
        _users.putAll(users);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        loadUsers();
        super.doStart();
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
    }

    /* ------------------------------------------------------------ */
    public void logout(UserIdentity identity)
    {   
        LOG.debug("logout {}",identity);
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return this.getClass().getSimpleName()+"["+_name+"]";
    }
    
    /* ------------------------------------------------------------ */
    /** Put user into realm.
     * Called by implementations to put the user data loaded from
     * file/db etc into the user structure.
     * @param userName User name
     * @param info a UserIdentity instance, or a String password or Credential instance
     * @return User instance
     */
    protected synchronized UserIdentity putUser(String userName, Object info)
    {
        final UserIdentity identity;
        if (info instanceof UserIdentity)
            identity=(UserIdentity)info;
        else
        {
            Credential credential = (info instanceof Credential)?(Credential)info:Credential.getCredential(info.toString());
            
            Principal userPrincipal = new KnownUser(userName,credential);
            Subject subject = new Subject();
            subject.getPrincipals().add(userPrincipal);
            subject.getPrivateCredentials().add(credential);
            subject.setReadOnly();
            identity=_identityService.newUserIdentity(subject,userPrincipal,IdentityService.NO_ROLES);
        }
        
        _users.put(userName,identity);
        return identity;
    }
    
    /* ------------------------------------------------------------ */
    /** Put user into realm.
     * @param userName The user to add
     * @param credential The users Credentials
     * @param roles The users roles
     * @return UserIdentity
     */
    public synchronized UserIdentity putUser(String userName, Credential credential, String[] roles)
    {
        Principal userPrincipal = new KnownUser(userName,credential);
        Subject subject = new Subject();
        subject.getPrincipals().add(userPrincipal);
        subject.getPrivateCredentials().add(credential);
        
        if (roles!=null)
            for (String role : roles)
                subject.getPrincipals().add(new RolePrincipal(role));

        subject.setReadOnly();
        UserIdentity identity=_identityService.newUserIdentity(subject,userPrincipal,roles);
        _users.put(userName,identity);
        return identity;
    } 
    
    /* ------------------------------------------------------------ */
    public void removeUser(String username)
    {
        _users.remove(username);
    }   

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.security.LoginService#login(java.lang.String, java.lang.Object)
     */
    public UserIdentity login(String username, Object credentials)
    {
        UserIdentity user = _users.get(username);
        
        if (user==null)
            user = loadUser(username);
        
        if (user!=null)
        {
            UserPrincipal principal = (UserPrincipal)user.getUserPrincipal();
            if (principal.authenticate(credentials))
                return user;
        }
        return null;
    }

    /* ------------------------------------------------------------ */
    public boolean validate(UserIdentity user)
    {
        if (_users.containsKey(user.getUserPrincipal().getName()))
            return true;
        
        if (loadUser(user.getUserPrincipal().getName())!=null)
            return true;
                
        return false;
    }

    /* ------------------------------------------------------------ */
    protected abstract UserIdentity loadUser(String username);
    
    /* ------------------------------------------------------------ */
    protected abstract void loadUsers() throws IOException;


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public interface UserPrincipal extends Principal,Serializable
    {
        boolean authenticate(Object credentials);
        public boolean isAuthenticated();
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public static class RolePrincipal implements Principal,Serializable
    {
        private static final long serialVersionUID = 2998397924051854402L;
        private final String _roleName;
        public RolePrincipal(String name)
        {
            _roleName=name;
        }
        public String getName()
        {
            return _roleName;
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public static class Anonymous implements UserPrincipal,Serializable
    {
        private static final long serialVersionUID = 1097640442553284845L;

        public boolean isAuthenticated()
        {
            return false;
        }

        public String getName()
        {
            return "Anonymous";
        }
        
        public boolean authenticate(Object credentials)
        {
            return false;
        }
        
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public static class KnownUser implements UserPrincipal,Serializable
    {
        private static final long serialVersionUID = -6226920753748399662L;
        private final String _name;
        private final Credential _credential;
        
        /* -------------------------------------------------------- */
        public KnownUser(String name,Credential credential)
        {
            _name=name;
            _credential=credential;
        }

        /* -------------------------------------------------------- */
        public boolean authenticate(Object credentials)
        {
            return _credential!=null && _credential.check(credentials);
        }
        
        /* ------------------------------------------------------------ */
        public String getName()
        {
            return _name;
        }
        
        /* -------------------------------------------------------- */
        public boolean isAuthenticated()
        {
            return true;
        }

        /* -------------------------------------------------------- */
        @Override
        public String toString()
        {
            return _name;
        }
    }
}

