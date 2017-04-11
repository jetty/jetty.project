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

package org.eclipse.jetty.websocket.common.test;

import static org.eclipse.jetty.toolchain.test.matchers.RegexMatcher.matchesPattern;
import static org.junit.Assert.assertThat;

import java.util.Iterator;

public abstract class EventTracker
{
    private EventCapture captured = new EventCapture();

    protected void addEvent(String format, Object... args)
    {
        captured.add(format, args);
    }

    public void assertCaptured(String... regexEvents)
    {
        Iterator<String> capturedIterator = captured.iterator();
        for (int i = 0; i < regexEvents.length; i++)
        {
            assertThat("Event [" + i + "]", capturedIterator.next(), matchesPattern(regexEvents[i]));
        }
    }
}
