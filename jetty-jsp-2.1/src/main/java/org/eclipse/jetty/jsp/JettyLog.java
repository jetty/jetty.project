package org.eclipse.jetty.jsp;

import com.sun.org.apache.commons.logging.LogFactory;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * @version $Revision$ $Date$
 */
public class JettyLog implements com.sun.org.apache.commons.logging.Log
{
    private static volatile boolean __initialized;

    /**
     * Called via reflection from WebXmlProcessor
     */
    public static synchronized void init()
    {
        if (!__initialized)
        {
            __initialized = true;
            LogFactory.setLogImplClassName(JettyLog.class.getName());
        }
    }

    private final Logger _logger;

    public JettyLog(String name)
    {
        _logger = Log.getLogger(name);
    }

    public void fatal(Object o)
    {
        error(o);
    }

    public void fatal(Object o, Throwable throwable)
    {
        error(o, throwable);
    }

    public boolean isErrorEnabled()
    {
        return true;
    }

    public void error(Object o)
    {
        warn(o);
    }

    public void error(Object o, Throwable throwable)
    {
        _logger.warn(String.valueOf(o), throwable);
    }

    public boolean isWarnEnabled()
    {
        return true;
    }

    public void warn(Object o)
    {
        _logger.warn(String.valueOf(o));
    }

    public boolean isInfoEnabled()
    {
        return true;
    }

    public void info(Object o)
    {
        _logger.info(String.valueOf(o));
    }

    public boolean isDebugEnabled()
    {
        return _logger.isDebugEnabled();
    }

    public void debug(Object o)
    {
        _logger.debug(String.valueOf(o));
    }

    public void debug(Object o, Throwable throwable)
    {
        _logger.debug(String.valueOf(o), throwable);
    }

    public boolean isTraceEnabled()
    {
        return isDebugEnabled();
    }

    public void trace(Object o)
    {
        debug(o);
    }
}
