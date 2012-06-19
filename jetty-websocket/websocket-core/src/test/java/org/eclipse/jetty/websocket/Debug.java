package org.eclipse.jetty.websocket;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StdErrLog;

/**
 * Utility class for aiding in debugging.
 */
public class Debug
{
    public static void dumpState(ByteBuffer buf)
    {
        System.out.printf("ByteBuffer @%d [%s]%n",buf.hashCode(),buf.toString());
        System.out.printf("  - capacity: %d%n",buf.capacity());
        System.out.printf("  - hasArray: %b%n",buf.hasArray());
        System.out.printf("  - hasRemaining: %b%n",buf.hasRemaining());
        System.out.printf("  - isDirect: %b%n",buf.isDirect());
        System.out.printf("  - isReadOnly: %b%n",buf.isReadOnly());
        System.out.printf("  - limit: %d%n",buf.limit());
        System.out.printf("  - order: %s%n",buf.order());
        System.out.printf("  - position: %d%n",buf.position());
        System.out.printf("  - remaining: %d%n",buf.remaining());
    }

    public static void enableDebugLogging(Class<?> clazz)
    {
        setLoggingLevel(clazz,StdErrLog.LEVEL_DEBUG);
    }

    public static void setLoggingLevel(Class<?> clazz, int level)
    {
        Logger log = Log.getLogger(clazz);
        if(log instanceof StdErrLog) {
            ((StdErrLog)log).setLevel(level);
        }
    }
}
