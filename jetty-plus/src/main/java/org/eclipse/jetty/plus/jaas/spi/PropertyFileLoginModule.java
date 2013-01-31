//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.plus.jaas.spi;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;

import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.security.PropertyUserStore;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * PropertyFileLoginModule
 * 
 * 
 */
public class PropertyFileLoginModule extends AbstractLoginModule
{
    public static final String DEFAULT_FILENAME = "realm.properties";

    private static final Logger LOG = Log.getLogger(PropertyFileLoginModule.class);

    private static Map<String, PropertyUserStore> _propertyUserStores = new HashMap<String, PropertyUserStore>();

    private int _refreshInterval = 0;
    private String _filename = DEFAULT_FILENAME;

    /**
     * Read contents of the configured property file.
     * 
     * @see javax.security.auth.spi.LoginModule#initialize(javax.security.auth.Subject, javax.security.auth.callback.CallbackHandler, java.util.Map,
     *      java.util.Map)
     * @param subject
     * @param callbackHandler
     * @param sharedState
     * @param options
     */
    public void initialize(Subject subject, CallbackHandler callbackHandler, Map<String, ?> sharedState, Map<String, ?> options)
    {
        super.initialize(subject,callbackHandler,sharedState,options);
        setupPropertyUserStore(options);
    }

    private void setupPropertyUserStore(Map<String, ?> options)
    {
        if (_propertyUserStores.get(_filename) == null)
        {
            parseConfig(options);

            PropertyUserStore _propertyUserStore = new PropertyUserStore();
            _propertyUserStore.setConfig(_filename);
            _propertyUserStore.setRefreshInterval(_refreshInterval);
            LOG.debug("setupPropertyUserStore: Starting new PropertyUserStore. PropertiesFile: " + _filename + " refreshInterval: " + _refreshInterval);

            try
            {
                _propertyUserStore.start();
            }
            catch (Exception e)
            {
                LOG.warn("Exception while starting propertyUserStore: ",e);
            }

            _propertyUserStores.put(_filename,_propertyUserStore);
        }
    }

    private void parseConfig(Map<String, ?> options)
    {
        _filename = (String)options.get("file") != null?(String)options.get("file"):DEFAULT_FILENAME;
        String refreshIntervalString = (String)options.get("refreshInterval");
        _refreshInterval = refreshIntervalString == null?_refreshInterval:Integer.parseInt(refreshIntervalString);
    }

    /**
     * Don't implement this as we want to pre-fetch all of the users.
     * 
     * @param userName
     * @throws Exception
     */
    public UserInfo getUserInfo(String userName) throws Exception
    {
        PropertyUserStore propertyUserStore = _propertyUserStores.get(_filename);
        if (propertyUserStore == null)
            throw new IllegalStateException("PropertyUserStore should never be null here!");
        
        UserIdentity userIdentity = propertyUserStore.getUserIdentity(userName);
        if(userIdentity==null)
            return null;
        
        Set<Principal> principals = userIdentity.getSubject().getPrincipals();
        
        List<String> roles = new ArrayList<String>();
        
        for ( Principal principal : principals )
        {
            roles.add( principal.getName() );
        }
        
        Credential credential = (Credential)userIdentity.getSubject().getPrivateCredentials().iterator().next();
        LOG.debug("Found: " + userName + " in PropertyUserStore");
        return new UserInfo(userName, credential, roles);
    }

}
