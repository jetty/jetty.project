//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

import java.util.regex.Pattern;

import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Assert;

@SuppressWarnings("serial")
public class EventCapture extends EventQueue<String>
{
    private static final Logger LOG = Log.getLogger(EventCapture.class);
    
    public static class Assertable
    {
        private final String event;

        public Assertable(String event)
        {
            this.event = event;
        }

        public void assertEventContains(String expected)
        {
            Assert.assertThat("Event",event,containsString(expected));
        }

        public void assertEventRegex(String regex)
        {
            Assert.assertTrue("Event: regex:[" + regex + "] in [" + event + "]",Pattern.matches(regex,event));
        }

        public void assertEventStartsWith(String expected)
        {
            Assert.assertThat("Event",event,startsWith(expected));
        }

        public void assertEvent(String expected)
        {
            Assert.assertThat("Event",event,is(expected));
        }
    }

    public void add(String format, Object... args)
    {
        String msg = String.format(format,args);
        if (LOG.isDebugEnabled())
            LOG.debug("EVENT: {}",msg);
        super.offer(msg);
    }

    public Assertable pop()
    {
        return new Assertable(super.poll());
    }

    public void assertEventCount(int expectedCount)
    {
        Assert.assertThat("Event Count",size(),is(expectedCount));
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
