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

package org.eclipse.jetty.gcloud.session;

import org.eclipse.jetty.session.AbstractSessionDataStoreFactory;
import org.eclipse.jetty.session.SessionDataStore;
import org.eclipse.jetty.session.SessionManager;

/**
 * GCloudSessionDataStoreFactory
 */
public class GCloudSessionDataStoreFactory extends AbstractSessionDataStoreFactory
{
    private String _namespace = GCloudSessionDataStore.DEFAULT_NAMESPACE;
    private int _maxRetries = GCloudSessionDataStore.DEFAULT_MAX_RETRIES;
    private int _backoffMs = GCloudSessionDataStore.DEFAULT_BACKOFF_MS;
    private GCloudSessionDataStore.EntityDataModel _model;
    private String _host;
    private String _projectId;

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

    public void setHost(String host)
    {
        _host = host;
    }

    public String getHost()
    {
        return _host;
    }

    public void setProjectId(String projectId)
    {
        _projectId = projectId;
    }

    public String getProjectId()
    {
        return _projectId;
    }

    @Override
    public SessionDataStore getSessionDataStore(SessionManager sessionManager) throws Exception
    {
        GCloudSessionDataStore ds = new GCloudSessionDataStore();
        ds.setBackoffMs(getBackoffMs());
        ds.setMaxRetries(getMaxRetries());
        ds.setGracePeriodSec(getGracePeriodSec());
        ds.setNamespace(getNamespace());
        ds.setSavePeriodSec(getSavePeriodSec());
        ds.setEntityDataModel(getEntityDataModel());
        ds.setHost(getHost());
        ds.setProjectId(getProjectId());
        return ds;
    }
}
