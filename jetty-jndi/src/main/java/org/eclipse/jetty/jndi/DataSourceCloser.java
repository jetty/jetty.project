package org.eclipse.jetty.jndi;

import java.lang.reflect.Method;
import java.sql.Statement;

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
    
    public void destroy()
    {
        try
        {
            if (_shutdown!=null)
            {
                Log.info("Shutdown datasource {}",_datasource);
                Statement stmt = _datasource.getConnection().createStatement();
                stmt.executeUpdate(_shutdown);
                stmt.close();
            }
        }
        catch (Exception e)
        {
            Log.warn(e);
        }
        
        try
        {
            Method close = _datasource.getClass().getMethod("close", new Class[]{});
            Log.info("Close datasource {}",_datasource);
            close.invoke(_datasource, new Object[]{});
        }
        catch (Exception e)
        {
            Log.warn(e);
        }
    }
}
