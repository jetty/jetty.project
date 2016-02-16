//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.jndi;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Statement;

import javax.sql.DataSource;

import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Close a DataSource.
 * Some {@link DataSource}'s need to be close (eg. Atomikos).  This bean is a {@link Destroyable} and
 * may be added to any {@link ContainerLifeCycle} so that {@link #destroy()}
 * will be called.   The {@link #destroy()} method calls any no-arg method called "close" on the passed DataSource.
 *
 */
public class DataSourceCloser implements Destroyable
{
    private static final Logger LOG = Log.getLogger(DataSourceCloser.class);

    final DataSource _datasource;
    final String _shutdown;

    public DataSourceCloser(DataSource datasource)
    {
        if (datasource==null)
            throw new IllegalArgumentException();
        _datasource=datasource;
        _shutdown=null;
    }

    public DataSourceCloser(DataSource datasource,String shutdownSQL)
    {
        if (datasource==null)
            throw new IllegalArgumentException();
        _datasource=datasource;
        _shutdown=shutdownSQL;
    }

    @Override
    public void destroy()
    {
        try
        {
            if (_shutdown!=null)
            {
                LOG.info("Shutdown datasource {}",_datasource);
                try (Connection connection = _datasource.getConnection();
                        Statement stmt = connection.createStatement())
                {
                    stmt.executeUpdate(_shutdown);
                }
            }
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }

        try
        {
            Method close = _datasource.getClass().getMethod("close", new Class[]{});
            LOG.info("Close datasource {}",_datasource);
            close.invoke(_datasource, new Object[]{});
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }
}
