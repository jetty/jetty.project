package org.eclipse.jetty.util.log;

import java.util.logging.Handler;
import java.util.logging.LogManager;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class JavaUtilLogTest
{
    private static Handler[] originalHandlers;
    private static CapturingJULHandler jul;

    @BeforeClass
    public static void setJUL()
    {
        LogManager lmgr = LogManager.getLogManager();
        java.util.logging.Logger root = lmgr.getLogger("");
        // Remember original handlers
        originalHandlers = root.getHandlers();
        // Remove original handlers
        for (Handler existing : originalHandlers)
        {
            root.removeHandler(existing);
        }
        // Set test/capturing handler
        jul = new CapturingJULHandler();
        root.addHandler(jul);
    }

    @AfterClass
    public static void restoreJUL()
    {
        LogManager lmgr = LogManager.getLogManager();
        java.util.logging.Logger root = lmgr.getLogger("");
        // Remove test handlers
        for (Handler existing : root.getHandlers())
        {
            root.removeHandler(existing);
        }
        // Restore original handlers
        for (Handler original : originalHandlers)
        {
            root.addHandler(original);
        }
    }

    @Test
    public void testNamedLogger()
    {
        jul.clear();
        JavaUtilLog log = new JavaUtilLog("test");
        log.info("Info test");

        jul.assertContainsLine("INFO|test|Info test");
    }
}
