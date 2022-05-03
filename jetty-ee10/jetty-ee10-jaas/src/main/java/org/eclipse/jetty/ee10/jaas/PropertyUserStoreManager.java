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

package org.eclipse.jetty.ee10.jaas;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.ee10.servlet.security.PropertyUserStore;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PropertyUserStoreManager
 *
 * Maintains a map of PropertyUserStores, keyed off the location of the property file containing
 * the authentication and authorization information.
 * 
 * This class is used to enable the PropertyUserStores to be cached and shared. This is essential
 * for the PropertyFileLoginModules, whose lifecycle is controlled by the JAAS api and instantiated
 * afresh whenever a user needs to be authenticated. Without this class, every PropertyFileLoginModule
 * instantiation would re-read and reload in all the user information just to authenticate a single user.
 */
public class PropertyUserStoreManager extends AbstractLifeCycle
{
    private static final Logger LOG = LoggerFactory.getLogger(PropertyUserStoreManager.class);
    /**
     * Map of user authentication and authorization information loaded in from a property file.
     * The map is keyed off the location of the file.
     */
    private Map<String, PropertyUserStore> _propertyUserStores; 

    public PropertyUserStore getPropertyUserStore(String file)
    {
        synchronized (this)
        {
            if (_propertyUserStores == null)
                return null;
            
            return _propertyUserStores.get(file);
        }
    }
    
    public PropertyUserStore addPropertyUserStore(String file, PropertyUserStore store)
    {
        synchronized (this)
        {
            Objects.requireNonNull(_propertyUserStores);
            PropertyUserStore existing = _propertyUserStores.get(file);
            if (existing != null)
                return existing;
            
            _propertyUserStores.put(file, store);
            return store;
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        _propertyUserStores = new HashMap<String, PropertyUserStore>();
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        for (Map.Entry<String, PropertyUserStore> entry : _propertyUserStores.entrySet())
        {
            try
            {
                entry.getValue().stop();
            }
            catch (Exception e)
            {
                LOG.warn("Error stopping PropertyUserStore at {}", entry.getKey(), e);
            }
        }
        _propertyUserStores = null;
        super.doStop();
    }
}
