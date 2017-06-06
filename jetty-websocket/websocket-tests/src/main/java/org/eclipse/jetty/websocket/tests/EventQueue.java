//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests;

import static org.eclipse.jetty.toolchain.test.matchers.RegexMatcher.matchesPattern;
import static org.junit.Assert.assertThat;

import java.util.Iterator;
import java.util.concurrent.LinkedBlockingDeque;

public class EventQueue extends LinkedBlockingDeque<String>
{
    public void add(String format, Object... args)
    {
        add(String.format(format, args));
    }
    
    public void assertEvents(String... regexEvents)
    {
        Iterator<String> capturedIterator = iterator();
        for (int i = 0; i < regexEvents.length; i++)
        {
            assertThat("Event [" + i + "]", capturedIterator.next(), matchesPattern(regexEvents[i]));
        }
    }
}
