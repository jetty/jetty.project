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

package org.eclipse.jetty.gcloud.session;

import org.eclipse.jetty.server.session.AbstractSessionDataStoreFactory;
import org.eclipse.jetty.server.session.SessionDataStore;
import org.eclipse.jetty.server.session.SessionHandler;

/**
 * GCloudSessionDataStoreFactory
 */
public class GCloudSessionDataStoreFactory extends AbstractSessionDataStoreFactory
{
    private String _namespace;
    private int _maxRetries;
    private int _backoffMs;
    private GCloudSessionDataStore.EntityDataModel _model;

    public GCloudSessionDataStore.EntityDataModel getEntityDataModel()
    {
        return _model;
    }

    public void setEntityDataModel(GCloudSessionDataStore.EntityDataModel model)
    {
        _model = model;
    }

    public int getMaxRetries()
    {
        return _maxRetries;
    }

    public void setMaxRetries(int maxRetries)
    {
        _maxRetries = maxRetries;
    }

    public int getBackoffMs()
    {
        return _backoffMs;
    }

    public void setBackoffMs(int backoffMs)
    {
        _backoffMs = backoffMs;
    }

    /**
     * @return the namespace
     */
    public String getNamespace()
    {
        return _namespace;
    }

    /**
     * @param namespace the namespace to set
     */
    public void setNamespace(String namespace)
    {
        _namespace = namespace;
    }

    @Override
    public SessionDataStore getSessionDataStore(SessionHandler handler) throws Exception
    {
        GCloudSessionDataStore ds = new GCloudSessionDataStore();
        ds.setBackoffMs(getBackoffMs());
        ds.setMaxRetries(getMaxRetries());
        ds.setGracePeriodSec(getGracePeriodSec());
        ds.setNamespace(_namespace);
        ds.setSavePeriodSec(getSavePeriodSec());
        return ds;
    }
}
