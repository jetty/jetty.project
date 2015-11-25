//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.security.MappedLoginService.KnownUser;
import org.eclipse.jetty.security.PropertyUserStore.UserListener;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.security.Credential;

/* ------------------------------------------------------------ */
/**
 * Properties User Realm.
 * <p>
 * An implementation of UserRealm that stores users and roles in-memory in HashMaps.
 * <p>
 * Typically these maps are populated by calling the load() method or passing a properties resource to the constructor. The format of the properties file is:
 * 
 * <pre>
 *  username: password [,rolename ...]
 * </pre>
 * 
 * Passwords may be clear text, obfuscated or checksummed. The class com.eclipse.Util.Password should be used to generate obfuscated passwords or password
 * checksums.
 * <p>
 * If DIGEST Authentication is used, the password must be in a recoverable format, either plain text or OBF:.
 */
public class HashLoginService extends MappedLoginService implements UserListener
{
    private static final Logger LOG = Log.getLogger(HashLoginService.class);

    private PropertyUserStore _propertyUserStore;
    private String _config;
    private Resource _configResource;
    private Scanner _scanner;
    private boolean hotReload = false; // default is not to reload
    
    
    
    public class HashKnownUser extends KnownUser
    {
        String[] _roles;
        
        /**
         * @param name
         * @param credential
         */
        public HashKnownUser(String name, Credential credential)
        {
            super(name, credential);
        }
        
     
        
        public void setRoles (String[] roles)
        {
            _roles = roles;
        }
        
        public String[] getRoles()
        {
            return _roles;
        }
    }
    
    

    /* ------------------------------------------------------------ */
    public HashLoginService()
    {
    }

    /* ------------------------------------------------------------ */
    public HashLoginService(String name)
    {
        setName(name);
    }

    /* ------------------------------------------------------------ */
    public HashLoginService(String name, String config)
    {
        setName(name);
        setConfig(config);
    }

    /* ------------------------------------------------------------ */
    public String getConfig()
    {
        return _config;
    }

    /* ------------------------------------------------------------ */
    public void getConfig(String config)
    {
        _config = config;
    }

    /* ------------------------------------------------------------ */
    public Resource getConfigResource()
    {
        return _configResource;
    }

    /* ------------------------------------------------------------ */
    /**
     * Load realm users from properties file. The property file maps usernames to password specs followed by an optional comma separated list of role names.
     * 
     * @param config
     *            Filename or url of user properties file.
     */
    public void setConfig(String config)
    {
        _config = config;
    }
    
    /**
     * Is hot reload enabled on this user store
     * 
     * @return true if hot reload was enabled before startup
     */
    public boolean isHotReload()
    {
        return hotReload;
    }

    /**
     * Enable Hot Reload of the Property File
     * 
     * @param enable true to enable, false to disable
     */
    public void setHotReload(boolean enable)
    {
        if (isRunning())
        {
            throw new IllegalStateException("Cannot set hot reload while user store is running");
        }
        this.hotReload = enable;
    }

    /* ------------------------------------------------------------ */
    /**
     * sets the refresh interval (in seconds)
     * @param sec the refresh interval
     * @deprecated use {@link #setHotReload(boolean)} instead
     */
    @Deprecated
    public void setRefreshInterval(int sec)
    {
    }

    /* ------------------------------------------------------------ */
    /**
     * @return refresh interval in seconds for how often the properties file should be checked for changes
     * @deprecated use {@link #isHotReload()} instead
     */
    @Deprecated
    public int getRefreshInterval()
    {
        return (hotReload)?1:0;
    }

    /* ------------------------------------------------------------ */
    @Override
    protected UserIdentity loadUser(String username)
    {
        return null;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void loadUsers() throws IOException
    {
        // TODO: Consider refactoring MappedLoginService to not have to override with unused methods
    }



    @Override
    protected String[] loadRoleInfo(KnownUser user)
    {
        UserIdentity id = _propertyUserStore.getUserIdentity(user.getName());
        if (id == null)
            return null;


        Set<RolePrincipal> roles = id.getSubject().getPrincipals(RolePrincipal.class);
        if (roles == null)
            return null;

        List<String> list = new ArrayList<>();
        for (RolePrincipal r:roles)
            list.add(r.getName());

        return list.toArray(new String[roles.size()]);
    }

    @Override
    protected KnownUser loadUserInfo(String userName)
    {
        UserIdentity id = _propertyUserStore.getUserIdentity(userName);
        if (id != null)
        {
            return (KnownUser)id.getUserPrincipal();
        }
        
        return null;
    }
    
    

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        
        if (_propertyUserStore == null)
        {
            if(LOG.isDebugEnabled())
                LOG.debug("doStart: Starting new PropertyUserStore. PropertiesFile: " + _config + " hotReload: " + hotReload);
            
            _propertyUserStore = new PropertyUserStore();
            _propertyUserStore.setHotReload(hotReload);
            _propertyUserStore.setConfigPath(_config);
            _propertyUserStore.registerUserListener(this);
            _propertyUserStore.start();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        if (_scanner != null)
            _scanner.stop();
        _scanner = null;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void update(String userName, Credential credential, String[] roleArray)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("update: " + userName + " Roles: " + roleArray.length);
       //TODO need to remove and replace the authenticated user?
    }

    
    
    /* ------------------------------------------------------------ */
    @Override
    public void remove(String userName)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("remove: " + userName);
        removeUser(userName);
    }
}
