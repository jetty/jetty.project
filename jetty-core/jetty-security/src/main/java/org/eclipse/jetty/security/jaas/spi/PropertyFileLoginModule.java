//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.security.jaas.spi;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;

import org.eclipse.jetty.security.PropertyUserStore;
import org.eclipse.jetty.security.RolePrincipal;
import org.eclipse.jetty.security.UserPrincipal;
import org.eclipse.jetty.security.jaas.JAASLoginService;
import org.eclipse.jetty.security.jaas.PropertyUserStoreManager;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PropertyFileLoginModule
 */
public class PropertyFileLoginModule extends AbstractLoginModule
{
    public static final String DEFAULT_FILENAME = "realm.properties";
    private static final Logger LOG = LoggerFactory.getLogger(PropertyFileLoginModule.class);

    private PropertyUserStore _store;

    /**
     * Use a PropertyUserStore to read the authentication and authorizaton information contained in
     * the file named by the option "file".
     *
     * @param subject the subject
     * @param callbackHandler the callback handler
     * @param sharedState the shared state map
     * @param options the options map
     * @see javax.security.auth.spi.LoginModule#initialize(Subject, CallbackHandler, Map,
     * Map)
     */
    @Override
    public void initialize(Subject subject, CallbackHandler callbackHandler, Map<String, ?> sharedState, Map<String, ?> options)
    {
        super.initialize(subject, callbackHandler, sharedState, options);
        setupPropertyUserStore(options);
    }

    /**
     * Get an existing, or create a new PropertyUserStore to read the 
     * authentication and authorization information from the file named by
     * the option "file".
     * 
     * @param options configuration options
     */
    private void setupPropertyUserStore(Map<String, ?> options)
    {
        String filename = (String)options.get("file");
        filename = (filename == null ? DEFAULT_FILENAME : filename);

        PropertyUserStoreManager mgr = JAASLoginService.INSTANCE.get().getBean(PropertyUserStoreManager.class);
        if (mgr == null)
            throw new IllegalStateException("No PropertyUserStoreManager");

        _store = mgr.getPropertyUserStore(filename);
        if (_store == null)
        {
            int reloadInterval = 0;
            String tmp = (String)options.get("reloadInterval");
            if (tmp != null)
            {
                try
                {
                    reloadInterval = Integer.parseInt(tmp);
                }
                catch (NumberFormatException e)
                {
                    LOG.warn("'reloadInterval' is not an integer");
                }
            }
            else
            {
                tmp = (String)options.get("hotReload");
                if (tmp != null)
                {
                    LOG.warn("Use 'reloadInterval' boolean property instead of 'hotReload'");
                    reloadInterval = Boolean.parseBoolean(tmp) ? 1 : 0;
                }
            }
            PropertyUserStore newStore = new PropertyUserStore();
            ResourceFactory resourceFactory = ResourceFactory.of(newStore);
            Resource config = resourceFactory.newResource(filename);
            newStore.setConfig(config);
            newStore.setReloadInterval(reloadInterval);
            _store = mgr.addPropertyUserStore(filename, newStore);
            try
            {
                _store.start();
            }
            catch (Exception e)
            {
                LOG.warn("Exception starting propertyUserStore {} ", config, e);
            }
        }
    }

    /**
     * @param userName the user name
     * @throws Exception if unable to get the user information
     */
    @Override
    public JAASUser getUser(String userName) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Checking PropertyUserStore {} for {}", _store.getConfig(), userName);
        UserPrincipal up = _store.getUserPrincipal(userName);
        if (up == null)
            return null;

        List<RolePrincipal> rps = _store.getRolePrincipals(userName);
        List<String> roles = rps == null ? Collections.emptyList() : rps.stream().map(RolePrincipal::getName).collect(Collectors.toList());
        return new JAASUser(up)
        {
            @Override
            public List<String> doFetchRoles()
            {
                return roles;
            }
        };
    }
}
