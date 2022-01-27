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
