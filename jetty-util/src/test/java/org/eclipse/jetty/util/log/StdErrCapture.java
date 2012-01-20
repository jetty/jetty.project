package org.eclipse.jetty.util.log;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.Assert;

public class StdErrCapture
{
    private ByteArrayOutputStream test;
    private PrintStream err;

    public StdErrCapture(StdErrLog log)
    {
        this();
        log.setStdErrStream(err);
    }

    public StdErrCapture()
    {
        test = new ByteArrayOutputStream();
        err = new PrintStream(test);
    }

    public void capture(StdErrLog log)
    {
        log.setStdErrStream(err);
    }

    public void assertContains(String expectedString)
    {
        err.flush();
        String output = new String(test.toByteArray());
        Assert.assertThat(output,containsString(expectedString));
    }

    public void assertNotContains(String unexpectedString)
    {
        err.flush();
        String output = new String(test.toByteArray());
        Assert.assertThat(output,not(containsString(unexpectedString)));
    }
    
    public String toString()
    {
        err.flush();
        return new String(test.toByteArray());
    }
}
