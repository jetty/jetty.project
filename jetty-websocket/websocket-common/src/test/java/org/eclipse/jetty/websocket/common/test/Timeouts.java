//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.concurrent.TimeUnit;

/**
 * A central place for all of the various test timeouts within the websocket testing.
 */
public class Timeouts
{
    // establish a connection timeout
    public static final long CONNECT = 2;
    public static final TimeUnit CONNECT_UNIT = TimeUnit.SECONDS;

    // poll for an event timeout
    public static final long POLL_EVENT = 2;
    public static final TimeUnit POLL_EVENT_UNIT = TimeUnit.SECONDS;

    // send a message timeout
    public static final long SEND = 2;
    public static final TimeUnit SEND_UNIT = TimeUnit.SECONDS;
}
