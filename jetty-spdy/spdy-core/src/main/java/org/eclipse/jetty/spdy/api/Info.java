//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.api;

import java.util.concurrent.TimeUnit;

/**
 * A base class for all *Info classes providing timeout and unit and api to access them
 */
public class Info
{
    private final long timeout;
    private final TimeUnit unit;

    public Info(long timeout, TimeUnit unit)
    {
        this.timeout = timeout;
        this.unit = unit;
    }

    public Info()
    {
        timeout = 0;
        unit = TimeUnit.SECONDS;
    }

    public long getTimeout()
    {
        return timeout;
    }

    public TimeUnit getUnit()
    {
        return unit;
    }
}
