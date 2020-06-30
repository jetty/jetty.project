//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.jaas.spi;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;

import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.PropertyUserStore;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.security.Credential;

/**
 * PropertyFileLoginModule
 */
public class PropertyFileLoginModule extends AbstractLoginModule
{
    public static final String DEFAULT_FILENAME = "realm.properties";

    private static final Logger LOG = Log.getLogger(PropertyFileLoginModule.class);

    private static ConcurrentHashMap<String, PropertyUserStore> _propertyUserStores = new ConcurrentHashMap<String, PropertyUserStore>();

    private int _refreshInterval = 0;
    private String _filename = DEFAULT_FILENAME;

    /**
     * Read contents of the configured property file.
     *
     * @param subject the subject
     * @param callbackHandler the callback handler
     * @param sharedState the shared state map
     * @param options the options map
     * @see javax.security.auth.spi.LoginModule#initialize(javax.security.auth.Subject, javax.security.auth.callback.CallbackHandler, java.util.Map,
     * java.util.Map)
     */
    @Override
    public void initialize(Subject subject, CallbackHandler callbackHandler, Map<String, ?> sharedState, Map<String, ?> options)
    {
        super.initialize(subject, callbackHandler, sharedState, options);
        setupPropertyUserStore(options);
    }

    private void setupPropertyUserStore(Map<String, ?> options)
    {
        parseConfig(options);

        if (_propertyUserStores.get(_filename) == null)
        {
            PropertyUserStore propertyUserStore = new PropertyUserStore();
            propertyUserStore.setConfig(_filename);

            PropertyUserStore prev = _propertyUserStores.putIfAbsent(_filename, propertyUserStore);
            if (prev == null)
            {
                LOG.debug("setupPropertyUserStore: Starting new PropertyUserStore. PropertiesFile: " + _filename + " refreshInterval: " + _refreshInterval);

                try
                {
                    propertyUserStore.start();
                }
                catch (Exception e)
                {
                    LOG.warn("Exception while starting propertyUserStore: ", e);
                }
            }
        }
    }

    private void parseConfig(Map<String, ?> options)
    {
        String tmp = (String)options.get("file");
        _filename = (tmp == null ? DEFAULT_FILENAME : tmp);
        tmp = (String)options.get("refreshInterval");
        _refreshInterval = (tmp == null ? _refreshInterval : Integer.parseInt(tmp));
    }

    /**
     * @param userName the user name
     * @throws Exception if unable to get the user information
     */
    @Override
    public UserInfo getUserInfo(String userName) throws Exception
    {
        PropertyUserStore propertyUserStore = _propertyUserStores.get(_filename);
        if (propertyUserStore == null)
            throw new IllegalStateException("PropertyUserStore should never be null here!");

        LOG.debug("Checking PropertyUserStore " + _filename + " for " + userName);
        UserIdentity userIdentity = propertyUserStore.getUserIdentity(userName);
        if (userIdentity == null)
            return null;

        //TODO in future versions change the impl of PropertyUserStore so its not
        //storing Subjects etc, just UserInfo
        Set<AbstractLoginService.RolePrincipal> principals = userIdentity.getSubject().getPrincipals(AbstractLoginService.RolePrincipal.class);

        List<String> roles = principals.stream()
            .map(AbstractLoginService.RolePrincipal::getName)
            .collect(Collectors.toList());

        Credential credential = (Credential)userIdentity.getSubject().getPrivateCredentials().iterator().next();
        LOG.debug("Found: " + userName + " in PropertyUserStore " + _filename);
        return new UserInfo(userName, credential, roles);
    }
}
