//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.common.events;

import java.util.ArrayList;
import java.util.regex.Pattern;

import org.junit.Assert;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

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
