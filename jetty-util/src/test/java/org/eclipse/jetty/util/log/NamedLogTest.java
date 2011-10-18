package org.eclipse.jetty.util.log;

import static org.hamcrest.Matchers.containsString;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class NamedLogTest
{
    private PrintStream orig;
    private ByteArrayOutputStream logstream;
    private PrintStream perr;
    private Logger origLogger;

    @Before
    public void setUp()
    {
        origLogger = Log.getRootLogger();
        
        orig = System.err;
        logstream = new ByteArrayOutputStream();
        perr = new PrintStream(logstream);
        System.setErr(perr);

        StdErrLog logger = new StdErrLog();
        Log.setLog(logger);
    }

    @After
    public void tearDown()
    {
        System.out.println(logstream.toString());
        System.setErr(orig);
        
        Log.setLog(origLogger);
    }

    @Test
    public void testNamedLogging()
    {
        Red red = new Red();
        Green green = new Green();
        Blue blue = new Blue();
        
        setLoggerOptions(Red.class);
        setLoggerOptions(Green.class);
        setLoggerOptions(Blue.class);

        red.generateLogs();
        green.generateLogs();
        blue.generateLogs();

        String rawlog = logstream.toString();
        
        Assert.assertThat(rawlog,containsString(Red.class.getName()));
        Assert.assertThat(rawlog,containsString(Green.class.getName()));
        Assert.assertThat(rawlog,containsString(Blue.class.getName()));
    }

    private void setLoggerOptions(Class<?> clazz)
    {
        Logger logger = Log.getLogger(clazz);
        logger.setDebugEnabled(true);
        
        if(logger instanceof StdErrLog) {
            ((StdErrLog)logger).setPrintLongNames(true);
        }
    }
}
