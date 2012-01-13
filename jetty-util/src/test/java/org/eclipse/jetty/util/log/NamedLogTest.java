package org.eclipse.jetty.util.log;

import org.junit.Test;

public class NamedLogTest
{
    @Test
    public void testNamedLogging()
    {
        Red red = new Red();
        Green green = new Green();
        Blue blue = new Blue();

        StdErrCapture output = new StdErrCapture();

        setLoggerOptions(Red.class,output);
        setLoggerOptions(Green.class,output);
        setLoggerOptions(Blue.class,output);

        red.generateLogs();
        green.generateLogs();
        blue.generateLogs();
        
        output.assertContains(Red.class.getName());
        output.assertContains(Green.class.getName());
        output.assertContains(Blue.class.getName());
    }

    private void setLoggerOptions(Class<?> clazz, StdErrCapture output)
    {
        Logger logger = Log.getLogger(clazz);
        logger.setDebugEnabled(true);

        if (logger instanceof StdErrLog)
        {
            StdErrLog sel = (StdErrLog)logger;
            sel.setPrintLongNames(true);
            output.capture(sel);
        }
    }
}
