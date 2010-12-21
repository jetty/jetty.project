package org.eclipse.jetty.jndi;

import java.lang.reflect.Method;

import javax.sql.DataSource;

import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.log.Log;

/**
 * Close a DataSource.
 * Some {@link DataSource}'s need to be close (eg. Atomikos).  This bean is a {@link Destroyable} and 
 * may be added to any {@link AggregateLifeCycle} so that {@link #destroy()}
 * will be called.   The {@link #destroy()} method calls any no-arg method called "close" on the passed DataSource.
 *
 */
public class DataSourceCloser implements Destroyable
{
    final DataSource _datasource;
    
    public DataSourceCloser(DataSource datasource)
    {
        _datasource=datasource;
    }
    
    public void destroy()
    {
        if (_datasource != null)
        {
            try
            {
                Method close = _datasource.getClass().getMethod("close", new Class[]{});
                close.invoke(_datasource, new Object[]{});
            }
            catch (Exception e)
            {
                Log.warn(e);
            }
        }
    }

}
