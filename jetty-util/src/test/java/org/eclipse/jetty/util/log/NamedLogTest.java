package org.eclipse.jetty.util.log;

import static org.hamcrest.Matchers.*;
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

    @Before
    public void setUp()
    {
        orig = System.err;
        logstream = new ByteArrayOutputStream();
        perr = new PrintStream(logstream);
        System.setErr(perr);

        StdErrLog logger = new StdErrLog();
        logger.setDebugEnabled(true);
        logger.setHideStacks(false);
        Log.setLog(logger);
    }

    @After
    public void tearDown()
    {
        System.out.println(logstream.toString());
        System.setErr(orig);
    }

    @Test
    public void testNamedLogging()
    {
        Red red = new Red();
        Green green = new Green();
        Blue blue = new Blue();

        red.generateLogs();
        green.generateLogs();
        blue.generateLogs();

        String rawlog = logstream.toString();
        
        Assert.assertThat(rawlog,containsString(Red.class.getName()));
        Assert.assertThat(rawlog,containsString(Green.class.getName()));
        Assert.assertThat(rawlog,containsString(Blue.class.getName()));
    }
}
