package org.eclipse.jetty.websocket;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StdErrLog;

/**
 * Utility class for managing logging levels during unit testing
 */
public class TestLogging
{
    public static void enableDebug(Class<?> clazz)
    {
        setLevel(clazz,StdErrLog.LEVEL_DEBUG);
    }

    public static void setLevel(Class<?> clazz, int level)
    {
        Logger log = Log.getLogger(clazz);
        if(log instanceof StdErrLog) {
            ((StdErrLog)log).setLevel(level);
        }
    }
}
