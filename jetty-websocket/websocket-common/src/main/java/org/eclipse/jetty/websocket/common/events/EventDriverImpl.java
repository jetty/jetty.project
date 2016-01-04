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

import org.eclipse.jetty.websocket.api.WebSocketPolicy;

/**
 * A specific implementation of a EventDriver.
 */
public interface EventDriverImpl
{
    /**
     * Create the EventDriver based on this implementation.
     * 
     * @param websocket
     *            the websocket to wrap
     * @param policy
     *            the policy to use
     * @return the created EventDriver
     * @throws Throwable
     *             if unable to create the EventDriver
     */
    EventDriver create(Object websocket, WebSocketPolicy policy) throws Throwable;

    /**
     * human readable string describing the rule that would support this EventDriver.
     * <p>
     * Used to help developer with possible object annotations, listeners, or base classes.
     * 
     * @return the human readable description of this event driver rule(s).
     */
    String describeRule();

    /**
     * Test for if this implementation can support the provided websocket.
     * 
     * @param websocket
     *            the possible websocket to test
     * @return true if implementation can support it, false if otherwise.
     */
    boolean supports(Object websocket);
}
