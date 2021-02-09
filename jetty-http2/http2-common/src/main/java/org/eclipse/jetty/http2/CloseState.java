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

package org.eclipse.jetty.http2;

/**
 * The set of close states for a stream or a session.
 * <pre>
 *                rcv hc
 * NOT_CLOSED ---------------&gt; REMOTELY_CLOSED
 *      |                             |
 *   gen|                             |gen
 *    hc|                             |hc
 *      |                             |
 *      v              rcv hc         v
 * LOCALLY_CLOSING --------------&gt; CLOSING
 *      |                             |
 *   snd|                             |gen
 *    hc|                             |hc
 *      |                             |
 *      v              rcv hc         v
 * LOCALLY_CLOSED ----------------&gt; CLOSED
 * </pre>
 */
public enum CloseState
{
    /**
     * Fully open.
     */
    NOT_CLOSED,
    /**
     * A half-close frame has been generated.
     */
    LOCALLY_CLOSING,
    /**
     * A half-close frame has been generated and sent.
     */
    LOCALLY_CLOSED,
    /**
     * A half-close frame has been received.
     */
    REMOTELY_CLOSED,
    /**
     * A half-close frame has been received and a half-close frame has been generated, but not yet sent.
     */
    CLOSING,
    /**
     * Fully closed.
     */
    CLOSED;

    public enum Event
    {
        RECEIVED,
        BEFORE_SEND,
        AFTER_SEND
    }
}
