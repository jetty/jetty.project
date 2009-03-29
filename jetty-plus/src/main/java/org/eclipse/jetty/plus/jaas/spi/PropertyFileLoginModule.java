// ========================================================================
// Copyright (c) 1999-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.plus.jaas.spi;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;

import org.eclipse.jetty.http.security.Credential;
import org.eclipse.jetty.util.log.Log;

/**
 * PropertyFileLoginModule
 *
 *
 */
public class PropertyFileLoginModule extends AbstractLoginModule
{
    public static final String DEFAULT_FILENAME = "realm.properties";
    public static final Map fileMap = new HashMap(); 
    
    private String propertyFileName;
    
    

    /** 
     * Read contents of the configured property file.
     * 
     * @see javax.security.auth.spi.LoginModule#initialize(javax.security.auth.Subject, javax.security.auth.callback.CallbackHandler, java.util.Map, java.util.Map)
     * @param subject
     * @param callbackHandler
     * @param sharedState
     * @param options
     */
    public void initialize(Subject subject, CallbackHandler callbackHandler,
            Map sharedState, Map options)
    {
        super.initialize(subject, callbackHandler, sharedState, options);
        loadProperties((String)options.get("file"));
    }
    
  
    
    public void loadProperties (String filename)
    {
        File propsFile;
        
        if (filename == null)
        {
            propsFile = new File(System.getProperty("user.dir"), DEFAULT_FILENAME);
            //look for a file called realm.properties in the current directory
            //if that fails, look for a file called realm.properties in $jetty.home/etc
            if (!propsFile.exists())
                propsFile = new File(System.getProperty("jetty.home"), DEFAULT_FILENAME);
        }
        else
        {
            propsFile = new File(filename);
        }
        
        //give up, can't find a property file to load
        if (!propsFile.exists())
        {
            Log.warn("No property file found");
            throw new IllegalStateException ("No property file specified in login module configuration file");
        }
            
        
     
        try
        {
            this.propertyFileName = propsFile.getCanonicalPath();
            if (fileMap.get(propertyFileName) != null)
            {
                if (Log.isDebugEnabled()) {Log.debug("Properties file "+propertyFileName+" already in cache, skipping load");}
                return;
            }
            
            Map userInfoMap = new HashMap();
            Properties props = new Properties();
            props.load(new FileInputStream(propsFile));
            Iterator iter = props.entrySet().iterator();
            while(iter.hasNext())
            {
                
                Map.Entry entry = (Map.Entry)iter.next();
                String username=entry.getKey().toString().trim();
                String credentials=entry.getValue().toString().trim();
                String roles=null;
                int c=credentials.indexOf(',');
                if (c>0)
                {
                    roles=credentials.substring(c+1).trim();
                    credentials=credentials.substring(0,c).trim();
                }

                if (username!=null && username.length()>0 &&
                    credentials!=null && credentials.length()>0)
                {
                    ArrayList roleList = new ArrayList();
                    if(roles!=null && roles.length()>0)
                    {
                        StringTokenizer tok = new StringTokenizer(roles,", ");
                        
                        while (tok.hasMoreTokens())
                            roleList.add(tok.nextToken());
                    }
                    
                    userInfoMap.put(username, (new UserInfo(username, Credential.getCredential(credentials.toString()), roleList)));
                }
            }
            
            fileMap.put(propertyFileName, userInfoMap);
        }
        catch (Exception e)
        {
            Log.warn("Error loading properties from file", e);
            throw new RuntimeException(e);
        }
    }

    /** 
     * Don't implement this as we want to pre-fetch all of the
     * users.
     * @see org.eclipse.jetty.plus.jaas.spi.AbstractLoginModule#lazyLoadUser(java.lang.String)
     * @param username
     * @throws Exception
     */
    public UserInfo getUserInfo (String username) throws Exception
    {
        Map userInfoMap = (Map)fileMap.get(propertyFileName);
        if (userInfoMap == null)
            return null;
        return (UserInfo)userInfoMap.get(username);
    }

}
