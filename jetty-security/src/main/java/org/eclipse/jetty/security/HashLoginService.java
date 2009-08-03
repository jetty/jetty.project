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

package org.eclipse.jetty.security;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.eclipse.jetty.http.security.Credential;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.Scanner.BulkListener;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;

/* ------------------------------------------------------------ */
/**
 * Properties User Realm.
 * 
 * An implementation of UserRealm that stores users and roles in-memory in
 * HashMaps.
 * <P>
 * Typically these maps are populated by calling the load() method or passing a
 * properties resource to the constructor. The format of the properties file is:
 * 
 * <PRE>
 *  username: password [,rolename ...]
 * </PRE>
 * 
 * Passwords may be clear text, obfuscated or checksummed. The class
 * com.eclipse.Util.Password should be used to generate obfuscated passwords or
 * password checksums.
 * 
 * If DIGEST Authentication is used, the password must be in a recoverable
 * format, either plain text or OBF:.
 * 
 * @see org.eclipse.jetty.security.Password
 * 
 */
public class HashLoginService extends MappedLoginService
{
    private String _config;
    private Resource _configResource;
    private Scanner _scanner;
    private int _refreshInterval = 0;// default is not to reload

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
        _config=config;
    }

    /* ------------------------------------------------------------ */
    public Resource getConfigResource()
    {
        return _configResource;
    }

    /* ------------------------------------------------------------ */
    /**
     * Load realm users from properties file. The property file maps usernames
     * to password specs followed by an optional comma separated list of role
     * names.
     * 
     * @param config Filename or url of user properties file.
     * @exception java.io.IOException if user properties file could not be
     *                    loaded
     */
    public void setConfig(String config)
    {
        _config = config;
    }

    /* ------------------------------------------------------------ */
    public void setRefreshInterval(int msec)
    {
        _refreshInterval = msec;
    }

    /* ------------------------------------------------------------ */
    public int getRefreshInterval()
    {
        return _refreshInterval;
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
        if (_config==null)
            return;
        _configResource = Resource.newResource(_config);
        
        if (Log.isDebugEnabled()) Log.debug("Load " + this + " from " + _config);
        Properties properties = new Properties();
        properties.load(_configResource.getInputStream());
        Set<String> known = new HashSet<String>();

        for (Map.Entry<Object, Object> entry : properties.entrySet())
        {
            String username = ((String) entry.getKey()).trim();
            String credentials = ((String) entry.getValue()).trim();
            String roles = null;
            int c = credentials.indexOf(',');
            if (c > 0)
            {
                roles = credentials.substring(c + 1).trim();
                credentials = credentials.substring(0, c).trim();
            }

            if (username != null && username.length() > 0 && credentials != null && credentials.length() > 0)
            {
                String[] roleArray = IdentityService.NO_ROLES;
                if (roles != null && roles.length() > 0)
                    roleArray = roles.split(",");
                known.add(username);
                putUser(username,Credential.getCredential(credentials),roleArray);
            }
        }
        
        Iterator<String> users = _users.keySet().iterator();
        while(users.hasNext())
        {
            String user=users.next();
            if (!known.contains(user))
                users.remove();
        }
    }


    /* ------------------------------------------------------------ */
    /**
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
            dirList.add(_configResource.getFile());
            _scanner.setScanDirs(dirList);
            _scanner.setFilenameFilter(new FilenameFilter()
            {
                public boolean accept(File dir, String name)
                {
                    File f = new File(dir, name);
                    try
                    {
                        if (f.compareTo(_configResource.getFile()) == 0) return true;
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
                public void filesChanged(List filenames) throws Exception
                {
                    if (filenames == null) return;
                    if (filenames.isEmpty()) return;
                    if (filenames.size() == 1 && filenames.get(0).equals(_config)) loadUsers();
                }

                public String toString()
                {
                    return "HashLoginService$Scanner";
                }

            });
            _scanner.setReportExistingFilesOnStartup(false);
            _scanner.setRecursive(false);
            _scanner.start();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStop()
     */
    protected void doStop() throws Exception
    {
        super.doStop();
        if (_scanner != null) _scanner.stop();
        _scanner = null;
    }


}
