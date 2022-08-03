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

package org.eclipse.jetty.ee10.servlet.security;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.PathWatcher;
import org.eclipse.jetty.util.PathWatcher.PathWatchEvent;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
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
public class PropertyUserStore extends UserStore implements PathWatcher.Listener
{
    private static final Logger LOG = LoggerFactory.getLogger(PropertyUserStore.class);

    protected Path _configPath;
    protected PathWatcher _pathWatcher;
    protected boolean _hotReload = false; // default is not to reload
    protected boolean _firstLoad = true; // true if first load, false from that point on
    protected List<UserListener> _listeners;

    /**
     * Get the config (as a string)
     *
     * @return the config path as a string
     */
    public String getConfig()
    {
        if (_configPath != null)
            return _configPath.toString();
        return null;
    }

    /**
     * Set the Config Path from a String reference to a file
     *
     * @param config the config file
     */
    public void setConfig(String config)
    {
        if (config == null)
        {
            _configPath = null;
            return;
        }

        URI uri = URI.create(config);
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            if (!FileID.isArchive(uri))
            {
                _configPath = Path.of(config);
            }
            else
            {
                Resource configResource = resourceFactory.newResource(uri);
                _configPath = extractPackedFile(configResource);
            }
        }
        catch (Exception e)
        {
            _configPath = null;
            throw new IllegalStateException(e);
        }
    }

    /**
     * Get the Config {@link Path} reference.
     *
     * @return the config path
     */
    public Path getConfigPath()
    {
        return _configPath;
    }

    private Path extractPackedFile(Resource configResource) throws IOException
    {
        String uri = configResource.getURI().toASCIIString();
        int colon = uri.lastIndexOf(":");
        int bangSlash = uri.indexOf("!/");
        if (colon < 0 || bangSlash < 0 || colon > bangSlash)
            throw new IllegalArgumentException("Not resolved JarFile resource: " + uri);

        String entryPath = StringUtil.sanitizeFileSystemName(uri.substring(colon + 2));

        Path tmpDirectory = Files.createTempDirectory("users_store");
        tmpDirectory.toFile().deleteOnExit();
        Path extractedPath = Paths.get(tmpDirectory.toString(), entryPath);
        Files.deleteIfExists(extractedPath);
        extractedPath.toFile().deleteOnExit();
        try (InputStream is = Files.newInputStream(configResource.getPath());
             OutputStream os = Files.newOutputStream(extractedPath))
        {
            IO.copy(is, os);
        }
        if (isHotReload())
        {
            LOG.warn("Cannot hot reload from packed configuration: {}", configResource);
            setHotReload(false);
        }
        return extractedPath;
    }

    /**
     * Set the Config Path from a {@link File} reference
     *
     * @param configFile the config file
     */
    public void setConfigFile(File configFile)
    {
        if (configFile == null)
            _configPath = null;
        else
            _configPath = configFile.toPath();
    }

    /**
     * Set the Config Path
     *
     * @param configPath the config path
     */
    public void setConfigPath(Path configPath)
    {
        _configPath = configPath;
    }

    /**
     * @return the resource associated with the configured properties file, creating it if necessary
     */
    public Resource getConfigResource()
    {
        if (_configPath == null)
            return null;
        return ResourceFactory.root().newResource(_configPath);
    }

    /**
     * Is hot reload enabled on this user store
     *
     * @return true if hot reload was enabled before startup
     */
    public boolean isHotReload()
    {
        return _hotReload;
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
        this._hotReload = enable;
    }

    @Override
    public String toString()
    {
        return String.format("%s[cfg=%s]", super.toString(), _configPath);
    }

    protected void loadUsers() throws IOException
    {
        if (_configPath == null)
            throw new IllegalStateException("No config path set");

        if (LOG.isDebugEnabled())
            LOG.debug("Loading {} from {}", this, _configPath);

        Resource config = getConfigResource();

        if (!config.exists())
            throw new IllegalStateException("Config does not exist: " + config);

        Properties properties = new Properties();
        try (InputStream inputStream = config.newInputStream())
        {
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
            LOG.debug("Loaded {} from {}", this,  _configPath);
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
        super.doStart();

        loadUsers();
        if (isHotReload() && (_configPath != null))
        {
            this._pathWatcher = new PathWatcher();
            this._pathWatcher.watch(_configPath);
            this._pathWatcher.addListener(this);
            this._pathWatcher.setNotifyExistingOnStart(false);
            this._pathWatcher.start();
        }
    }

    @Override
    public void onPathWatchEvent(PathWatchEvent event)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Path watch event: {}", event.getType());
            loadUsers();
        }
        catch (IOException e)
        {
            LOG.warn("Unable to load users", e);
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        if (this._pathWatcher != null)
            this._pathWatcher.stop();
        this._pathWatcher = null;
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
        public void update(String username, Credential credential, String[] roleArray);

        public void remove(String username);
    }
}
