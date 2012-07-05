package org.eclipse.jetty.websocket.driver;

import static org.hamcrest.Matchers.*;

import java.util.ArrayList;
import java.util.regex.Pattern;

import org.junit.Assert;

@SuppressWarnings("serial")
public class EventCapture extends ArrayList<String>
{
    public void add(String format, Object... args)
    {
        super.add(String.format(format,args));
    }

    public void assertEvent(int eventNum, String expected)
    {
        Assert.assertThat("Event[" + eventNum + "]",get(eventNum),is(expected));
    }

    public void assertEventContains(int eventNum, String expected)
    {
        Assert.assertThat("Event[" + eventNum + "]",get(eventNum),containsString(expected));
    }

    public void assertEventCount(int expectedCount)
    {
        Assert.assertThat("Event Count",size(),is(expectedCount));
    }

    public void assertEventRegex(int eventNum, String regex)
    {
        String event = get(eventNum);
        Assert.assertTrue("Event[" + eventNum + "]: regex:[" + regex + "] in [" + event + "]",Pattern.matches(regex,event));
    }

    public void assertEventStartsWith(int eventNum, String expected)
    {
        Assert.assertThat("Event[" + eventNum + "]",get(eventNum),startsWith(expected));
    }

    public String q(String str)
    {
        if (str == null)
        {
            return "<null>";
        }
        return '"' + str + '"';
    }
}
