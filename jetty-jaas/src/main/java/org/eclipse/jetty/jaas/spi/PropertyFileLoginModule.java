//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.jaas.spi;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;

import org.eclipse.jetty.jaas.JAASLoginService;
import org.eclipse.jetty.jaas.PropertyUserStoreManager;
import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.PropertyUserStore;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.security.Credential;
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
     * @see javax.security.auth.spi.LoginModule#initialize(javax.security.auth.Subject, javax.security.auth.callback.CallbackHandler, java.util.Map,
     * java.util.Map)
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
            boolean hotReload = false;  
            String tmp = (String)options.get("hotReload");
            if (tmp != null)
                hotReload = Boolean.parseBoolean(tmp);
            else
            {
                //refreshInterval is deprecated, use hotReload instead
                tmp = (String)options.get("refreshInterval");
                if (tmp != null)
                {
                    LOG.warn("Use 'hotReload' boolean property instead of 'refreshInterval'");
                    try
                    {
                        hotReload = (Integer.parseInt(tmp) > 0);
                    }
                    catch (NumberFormatException e)
                    {
                        LOG.warn("'refreshInterval' is not an integer");
                    }
                }
            }
            PropertyUserStore newStore = new PropertyUserStore();
            newStore.setConfig(filename);
            newStore.setHotReload(hotReload);
            _store = mgr.addPropertyUserStore(filename, newStore);
            try
            {
                _store.start();
            }
            catch (Exception e)
            {
                LOG.warn("Exception starting propertyUserStore {} ", filename, e);
            }
        }
    }

    /**
     * @param userName the user name
     * @throws Exception if unable to get the user information
     */
    @Override
    public UserInfo getUserInfo(String userName) throws Exception
    {
        LOG.debug("Checking PropertyUserStore {} for {}", _store.getConfig(), userName);
        UserIdentity userIdentity = _store.getUserIdentity(userName);
        if (userIdentity == null)
            return null;

        //TODO in future versions change the impl of PropertyUserStore so its not
        //storing Subjects etc, just UserInfo
        Set<AbstractLoginService.RolePrincipal> principals = userIdentity.getSubject().getPrincipals(AbstractLoginService.RolePrincipal.class);

        List<String> roles = principals.stream()
            .map(AbstractLoginService.RolePrincipal::getName)
            .collect(Collectors.toList());

        Credential credential = (Credential)userIdentity.getSubject().getPrivateCredentials().iterator().next();
        return new UserInfo(userName, credential, roles);
    }
}
