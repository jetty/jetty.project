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

import org.eclipse.jetty.util.Fields;

/**
 * <p>A container for PUSH_SYN_STREAM frames metadata and data.</p>
 */
public class PushInfo extends Info
{
    /**
     * <p>Flag that indicates that this {@link PushInfo} is the last frame in the stream.</p>
     *
     * @see #isClose()
     * @see #getFlags()
     */
    public static final byte FLAG_CLOSE = 1;

    private final boolean close;
    private final Fields headers;

    /**
     * <p>Creates a {@link PushInfo} instance with the given headers and the given close flag,
     * not unidirectional, without associated stream, and with default priority.</p>
     *
     * @param headers the {@link Fields}
     * @param close the value of the close flag
     */
    public PushInfo(Fields headers, boolean close)
    {
        this(0, TimeUnit.SECONDS, headers, close);
        // either builder or setters for timeout
    }

    /**
     * <p>
     * Creates a {@link PushInfo} instance with the given headers, the given close flag and with the given priority.
     * </p>
     * @param timeout the timeout value
     * @param unit the TimeUnit of the timeout
     * @param headers
 *            the {@link Fields}
     * @param close
     */
    public PushInfo(long timeout, TimeUnit unit, Fields headers, boolean close)
    {
        super(timeout, unit);
        this.close = close;
        this.headers = headers;
    }

    /**
     * @return the value of the close flag
     */
    public boolean isClose()
    {
        return close;
    }

    /**
     * @return the {@link Fields}
     */
    public Fields getHeaders()
    {
        return headers;
    }

    /**
     * @return the close flag as integer
     * @see #FLAG_CLOSE
     */
    public byte getFlags()
    {
        return isClose() ? FLAG_CLOSE : 0;
    }

    @Override
    public String toString()
    {
        return String.format("SYN push close=%b headers=%s", close, headers);
    }
}
