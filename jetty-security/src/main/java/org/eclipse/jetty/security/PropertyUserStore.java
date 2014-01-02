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

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.security.auth.Subject;

import org.eclipse.jetty.security.MappedLoginService.KnownUser;
import org.eclipse.jetty.security.MappedLoginService.RolePrincipal;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.Scanner.BulkListener;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.security.Credential;

/**
 * PropertyUserStore
 * 
 * This class monitors a property file of the format mentioned below and notifies registered listeners of the changes to the the given file.
 * 
 * <PRE>
 *  username: password [,rolename ...]
 * </PRE>
 * 
 * Passwords may be clear text, obfuscated or checksummed. The class com.eclipse.Util.Password should be used to generate obfuscated passwords or password
 * checksums.
 * 
 * If DIGEST Authentication is used, the password must be in a recoverable format, either plain text or OBF:.
 */
public class PropertyUserStore extends AbstractLifeCycle
{
    private static final Logger LOG = Log.getLogger(PropertyUserStore.class);

    private String _config;
    private Resource _configResource;
    private Scanner _scanner;
    private int _refreshInterval = 0;// default is not to reload

    private IdentityService _identityService = new DefaultIdentityService();
    private boolean _firstLoad = true; // true if first load, false from that point on
    private final List<String> _knownUsers = new ArrayList<String>();
    private final Map<String, UserIdentity> _knownUserIdentities = new HashMap<String, UserIdentity>();
    private List<UserListener> _listeners;

    /* ------------------------------------------------------------ */
    public String getConfig()
    {
        return _config;
    }

    /* ------------------------------------------------------------ */
    public void setConfig(String config)
    {
        _config = config;
    }
    
    /* ------------------------------------------------------------ */
        public UserIdentity getUserIdentity(String userName)
        {
            return _knownUserIdentities.get(userName);
        }

    /* ------------------------------------------------------------ */
    /**
     * returns the resource associated with the configured properties file, creating it if necessary
     */
    public Resource getConfigResource() throws IOException
    {
        if (_configResource == null)
        {
            _configResource = Resource.newResource(_config);
        }

        return _configResource;
    }

    /* ------------------------------------------------------------ */
    /**
     * sets the refresh interval (in seconds)
     */
    public void setRefreshInterval(int msec)
    {
        _refreshInterval = msec;
    }

    /* ------------------------------------------------------------ */
    /**
     * refresh interval in seconds for how often the properties file should be checked for changes
     */
    public int getRefreshInterval()
    {
        return _refreshInterval;
    }

    /* ------------------------------------------------------------ */
    private void loadUsers() throws IOException
    {
        if (_config == null)
            return;

        if (LOG.isDebugEnabled())
            LOG.debug("Load " + this + " from " + _config);
        Properties properties = new Properties();
        if (getConfigResource().exists())
            properties.load(getConfigResource().getInputStream());
        Set<String> known = new HashSet<String>();

        for (Map.Entry<Object, Object> entry : properties.entrySet())
        {
            String username = ((String)entry.getKey()).trim();
            String credentials = ((String)entry.getValue()).trim();
            String roles = null;
            int c = credentials.indexOf(',');
            if (c > 0)
            {
                roles = credentials.substring(c + 1).trim();
                credentials = credentials.substring(0,c).trim();
            }

            if (username != null && username.length() > 0 && credentials != null && credentials.length() > 0)
            {
                String[] roleArray = IdentityService.NO_ROLES;
                if (roles != null && roles.length() > 0)
                {
                    roleArray = roles.split(",");
                }
                known.add(username);
                Credential credential = Credential.getCredential(credentials);
                
                Principal userPrincipal = new KnownUser(username,credential);
                Subject subject = new Subject();
                subject.getPrincipals().add(userPrincipal);
                subject.getPrivateCredentials().add(credential);

                if (roles != null)
                {
                    for (String role : roleArray)
                    {
                        subject.getPrincipals().add(new RolePrincipal(role));
                    }
                }
                
                subject.setReadOnly();
                
                _knownUserIdentities.put(username,_identityService.newUserIdentity(subject,userPrincipal,roleArray));
                notifyUpdate(username,credential,roleArray);
            }
        }

        synchronized (_knownUsers)
        {
            /*
             * if its not the initial load then we want to process removed users
             */
            if (!_firstLoad)
            {
                Iterator<String> users = _knownUsers.iterator();
                while (users.hasNext())
                {
                    String user = users.next();
                    if (!known.contains(user))
                    {
                        _knownUserIdentities.remove(user);
                        notifyRemove(user);
                    }
                }
            }

            /*
             * reset the tracked _users list to the known users we just processed
             */

            _knownUsers.clear();
            _knownUsers.addAll(known);

        }

        /*
         * set initial load to false as there should be no more initial loads
         */
        _firstLoad = false;
    }

    /* ------------------------------------------------------------ */
    /**
     * Depending on the value of the refresh interval, this method will either start up a scanner thread that will monitor the properties file for changes after
     * it has initially loaded it. Otherwise the users will be loaded and there will be no active monitoring thread so changes will not be detected.
     * 
     * 
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    protected void doStart() throws Exception
    {
        super.doStart();

        if (getRefreshInterval() > 0)
        {
            _scanner = new Scanner();
            _scanner.setScanInterval(getRefreshInterval());
            List<File> dirList = new ArrayList<File>(1);
            dirList.add(getConfigResource().getFile().getParentFile());
            _scanner.setScanDirs(dirList);
            _scanner.setFilenameFilter(new FilenameFilter()
            {
                public boolean accept(File dir, String name)
                {
                    File f = new File(dir,name);
                    try
                    {
                        if (f.compareTo(getConfigResource().getFile()) == 0)
                        {
                            return true;
                        }
                    }
                    catch (IOException e)
                    {
                        return false;
                    }

                    return false;
                }

            });

            _scanner.addListener(new BulkListener()
            {
                public void filesChanged(List<String> filenames) throws Exception
                {
                    if (filenames == null)
                        return;
                    if (filenames.isEmpty())
                        return;
                    if (filenames.size() == 1)
                    {
                        Resource r = Resource.newResource(filenames.get(0));
                        if (r.getFile().equals(_configResource.getFile()))
                            loadUsers();
                    }
                }

                public String toString()
                {
                    return "PropertyUserStore$Scanner";
                }

            });

            _scanner.setReportExistingFilesOnStartup(true);
            _scanner.setRecursive(false);
            _scanner.start();
        }
        else
        {
            loadUsers();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStop()
     */
    protected void doStop() throws Exception
    {
        super.doStop();
        if (_scanner != null)
            _scanner.stop();
        _scanner = null;
    }

    /**
     * Notifies the registered listeners of potential updates to a user
     * 
     * @param username
     * @param credential
     * @param roleArray
     */
    private void notifyUpdate(String username, Credential credential, String[] roleArray)
    {
        if (_listeners != null)
        {
            for (Iterator<UserListener> i = _listeners.iterator(); i.hasNext();)
            {
                i.next().update(username,credential,roleArray);
            }
        }
    }

    /**
     * notifies the registered listeners that a user has been removed.
     * 
     * @param username
     */
    private void notifyRemove(String username)
    {
        if (_listeners != null)
        {
            for (Iterator<UserListener> i = _listeners.iterator(); i.hasNext();)
            {
                i.next().remove(username);
            }
        }
    }

    /**
     * registers a listener to be notified of the contents of the property file
     */
    public void registerUserListener(UserListener listener)
    {
        if (_listeners == null)
        {
            _listeners = new ArrayList<UserListener>();
        }
        _listeners.add(listener);
    }

    /**
     * UserListener
     */
    public interface UserListener
    {
        public void update(String username, Credential credential, String[] roleArray);

        public void remove(String username);
    }
}
