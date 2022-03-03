//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.websocket.common;

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
