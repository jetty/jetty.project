//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.Resources;
import org.eclipse.jetty.util.security.Credential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>This class monitors a property file of the format mentioned below
 * and notifies registered listeners of the changes to the the given file.</p>
 *
 * <pre>
 *  username: password [,rolename ...]
 * </pre>
 *
 * <p>Passwords may be clear text, obfuscated or checksummed.
 * The class {@link org.eclipse.jetty.util.security.Password} should be used
 * to generate obfuscated passwords or password checksums.</p>
 *
 * <p>If DIGEST Authentication is used, the password must be in a recoverable
 * format, either plain text or obfuscated.</p>
 */
public class PropertyUserStore extends UserStore implements Scanner.DiscreteListener
{
    private static final Logger LOG = LoggerFactory.getLogger(PropertyUserStore.class);

    protected Resource _configResource;
    protected Scanner _scanner;
    protected int _refreshInterval = 0;
    protected boolean _firstLoad = true; // true if first load, false from that point on
    protected List<UserListener> _listeners;

    /**
     * Get the config (as a string)
     *
     * @return the config path as a string
     */
    public Resource getConfig()
    {
        return _configResource;
    }

    /**
     * Set the Config Path from a String reference to a file
     *
     * @param config the config file
     * TODO: reintroduce setConfig(String) and internal ResourceFactory usage
     */
    public void setConfig(Resource config)
    {
        _configResource = config;
    }

    /**
     * @return the resource associated with the configured properties file, creating it if necessary
     * @deprecated
     */
    @Deprecated(forRemoval = true)
    public Resource getConfigResource()
    {
        return getConfig();
    }

    /**
     * Is hot reload enabled on this user store
     *
     * @return true if hot reload was enabled before startup
     * @deprecated use {@link #getRefreshInterval()}
     */
    @Deprecated
    public boolean isHotReload()
    {
        return getRefreshInterval() > 0;
    }

    /**
     * Enable Hot Reload of the Property File
     *
     * @param enable true to enable to a 1 second scan, false to disable
     * @deprecated use {@link #setRefreshInterval(int)}
     */
    @Deprecated
    public void setHotReload(boolean enable)
    {
        setRefreshInterval(enable ? 1 : 0);
    }

    /**
     * Enable Hot Reload of the Property File
     *
     * @param scanSeconds the period in seconds to scan for property file changes, or 0 for no scanning
     */
    public void setRefreshInterval(int scanSeconds)
    {
        if (isRunning())
        {
            throw new IllegalStateException("Cannot set scan period while user store is running");
        }
        this._refreshInterval = scanSeconds;
    }

    /**
     * @return the period in seconds to scan for property file changes, or 0 for no scanning
     */
    public int getRefreshInterval()
    {
        return _refreshInterval;
    }

    @Override
    public String toString()
    {
        return String.format("%s[cfg=%s]", super.toString(), _configResource);
    }

    /**
     * Load the user data from the property file.
     * @throws IOException If the users cannot be loaded
     */
    protected void loadUsers() throws IOException
    {
        Resource config = getConfig();

        if (config == null)
            throw new IllegalStateException("No config path set");

        if (LOG.isDebugEnabled())
            LOG.debug("Loading {} from {}", this, config);

        if (Resources.missing(config))
            throw new IllegalStateException("Config does not exist: " + config);

        Properties properties = new Properties();
        try (InputStream inputStream = config.newInputStream())
        {
            if (inputStream == null)
                throw new IllegalStateException("Config does have properties: " + config);
            properties.load(inputStream);
        }

        Set<String> known = new HashSet<>();

        for (Map.Entry<Object, Object> entry : properties.entrySet())
        {
            String username = ((String)entry.getKey()).trim();
            String credentials = ((String)entry.getValue()).trim();
            String roles = null;
            int c = credentials.indexOf(',');
            if (c >= 0)
            {
                roles = credentials.substring(c + 1).trim();
                credentials = credentials.substring(0, c).trim();
            }

            if (username.length() > 0)
            {
                String[] roleArray = IdentityService.NO_ROLES;
                if (roles != null && roles.length() > 0)
                    roleArray = StringUtil.csvSplit(roles);
                known.add(username);
                Credential credential = Credential.getCredential(credentials);
                addUser(username, credential, roleArray);
                notifyUpdate(username, credential, roleArray);
            }
        }

        List<String> currentlyKnownUsers = new ArrayList<>(_users.keySet());
        // if its not the initial load then we want to process removed users
        if (!_firstLoad)
        {
            for (String user : currentlyKnownUsers)
            {
                if (!known.contains(user))
                {
                    removeUser(user);
                    notifyRemove(user);
                }
            }
        }

        // set initial load to false as there should be no more initial loads
        _firstLoad = false;

        if (LOG.isDebugEnabled())
            LOG.debug("Loaded {} from {}", this,  config);
    }

    /**
     * Depending on the value of the refresh interval, this method will either start
     * up a scanner thread that will monitor the properties file for changes after
     * it has initially loaded it. Otherwise the users will be loaded and there will
     * be no active monitoring thread so changes will not be detected.
     */
    @Override
    protected void doStart() throws Exception
    {
        Resource config = getConfig();
        if (getRefreshInterval() > 0 && (config != null))
        {
            _scanner = new Scanner(null, false);
            _scanner.addFile(config.getPath());
            _scanner.setScanInterval(_refreshInterval);
            _scanner.setReportExistingFilesOnStartup(false);
            _scanner.addListener(this);
            addBean(_scanner);
        }

        loadUsers();
        super.doStart();
    }

    @Override
    public void pathChanged(Path path) throws Exception
    {
        loadUsers();
    }

    @Override
    public void pathAdded(Path path) throws Exception
    {
        loadUsers();
    }

    @Override
    public void pathRemoved(Path path) throws Exception
    {
        loadUsers();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        removeBean(_scanner);
        _scanner = null;
    }

    /**
     * Notifies the registered listeners of potential updates to a user
     *
     * @param username the user that was updated
     * @param credential the updated credentials
     * @param roleArray the updated roles
     */
    private void notifyUpdate(String username, Credential credential, String[] roleArray)
    {
        if (_listeners != null)
        {
            for (UserListener listener : _listeners)
            {
                listener.update(username, credential, roleArray);
            }
        }
    }

    /**
     * Notifies the registered listeners that a user has been removed.
     *
     * @param username the user that was removed
     */
    private void notifyRemove(String username)
    {
        if (_listeners != null)
        {
            for (UserListener listener : _listeners)
            {
                listener.remove(username);
            }
        }
    }

    /**
     * Registers a listener to be notified of the contents of the property file
     *
     * @param listener the user listener
     */
    public void registerUserListener(UserListener listener)
    {
        if (_listeners == null)
            _listeners = new ArrayList<>();
        _listeners.add(listener);
    }

    public interface UserListener
    {
        void update(String username, Credential credential, String[] roleArray);

        void remove(String username);
    }
}
