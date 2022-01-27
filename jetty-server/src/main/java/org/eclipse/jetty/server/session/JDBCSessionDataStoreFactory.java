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

package org.eclipse.jetty.server.session;

/**
 * JDBCSessionDataStoreFactory
 */
public class JDBCSessionDataStoreFactory extends AbstractSessionDataStoreFactory
{

    /**
     *
     */
    DatabaseAdaptor _adaptor;

    /**
     *
     */
    JDBCSessionDataStore.SessionTableSchema _schema;

    @Override
    public SessionDataStore getSessionDataStore(SessionHandler handler)
    {
        JDBCSessionDataStore ds = new JDBCSessionDataStore();
        ds.setDatabaseAdaptor(_adaptor);
        ds.setSessionTableSchema(_schema);
        ds.setGracePeriodSec(getGracePeriodSec());
        ds.setSavePeriodSec(getSavePeriodSec());
        return ds;
    }

    /**
     * @param adaptor the {@link DatabaseAdaptor} to set
     */
    public void setDatabaseAdaptor(DatabaseAdaptor adaptor)
    {
        _adaptor = adaptor;
    }

    /**
     * @param schema the {@link JDBCSessionDataStoreFactory} to set
     */
    public void setSessionTableSchema(JDBCSessionDataStore.SessionTableSchema schema)
    {
        _schema = schema;
    }
}
