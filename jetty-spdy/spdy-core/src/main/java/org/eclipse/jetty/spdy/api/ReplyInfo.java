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
 * <p>A container for SYN_REPLY frames metadata and headers.</p>
 */
public class ReplyInfo extends Info
{
    /**
     * <p>Flag that indicates that this {@link ReplyInfo} is the last frame in the stream.</p>
     *
     * @see #isClose()
     * @see #getFlags()
     */
    public static final byte FLAG_CLOSE = 1;

    private final Fields headers;
    private final boolean close;

    /**
     * <p>Creates a new {@link ReplyInfo} instance with empty headers and the given close flag.</p>
     *
     * @param close the value of the close flag
     */
    public ReplyInfo(boolean close)
    {
        this(new Fields(), close);
    }

    /**
     * <p>Creates a {@link ReplyInfo} instance with the given headers and the given close flag.</p>
     *
     * @param headers the {@link Fields}
     * @param close   the value of the close flag
     */
    public ReplyInfo(Fields headers, boolean close)
    {
        this(0, TimeUnit.SECONDS, headers, close);
    }

    /**
     * <p>Creates a {@link ReplyInfo} instance with the given headers and the given close flag.</p>
     *
     * @param timeout the timeout
     * @param unit    the time unit for the timeout
     * @param headers the {@link Fields}
     * @param close   the value of the close flag
     */
    public ReplyInfo(long timeout, TimeUnit unit, Fields headers, boolean close)
    {
        super(timeout, unit);
        this.headers = headers;
        this.close = close;
    }

    /**
     * @return the {@link Fields}
     */
    public Fields getHeaders()
    {
        return headers;
    }

    /**
     * @return the value of the close flag
     */
    public boolean isClose()
    {
        return close;
    }

    /**
     * @return the close and reset compression flags as integer
     * @see #FLAG_CLOSE
     */
    public byte getFlags()
    {
        return isClose() ? FLAG_CLOSE : 0;
    }

    @Override
    public String toString()
    {
        return String.format("REPLY close=%b %s", close, headers);
    }
}
