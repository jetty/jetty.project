//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.LinkedBlockingDeque;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.matchesRegex;
import static org.junit.jupiter.api.Assertions.fail;

public class EventQueue extends LinkedBlockingDeque<String>
{
    public void add(String format, Object... args)
    {
        add(String.format(format, args));
    }

    public void assertEvents(String... regexEvents)
    {
        int i = 0;
        try
        {
            Iterator<String> capturedIterator = iterator();
            for (i = 0; i < regexEvents.length; i++)
            {
                assertThat("Event [" + i + "]", capturedIterator.next(), matchesRegex(regexEvents[i]));
            }
        }
        catch (NoSuchElementException e)
        {
            fail("Event [" + (i) + "] not found: " + regexEvents[i]);
        }
    }
}
