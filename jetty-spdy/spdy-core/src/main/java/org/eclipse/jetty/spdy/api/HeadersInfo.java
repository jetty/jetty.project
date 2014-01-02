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
 * <p>A container for HEADERS frame metadata and headers.</p>
 */
public class HeadersInfo extends Info
{
    /**
     * <p>Flag that indicates that this {@link HeadersInfo} is the last frame in the stream.</p>
     *
     * @see #isClose()
     * @see #getFlags()
     */
    public static final byte FLAG_CLOSE = 1;
    /**
     * <p>Flag that indicates that the compression of the stream must be reset.</p>
     *
     * @see #isResetCompression()
     * @see #getFlags()
     */
    public static final byte FLAG_RESET_COMPRESSION = 2;

    private final boolean close;
    private final boolean resetCompression;
    private final Fields headers;

    /**
     * <p>Creates a new {@link HeadersInfo} instance with the given headers, the given close flag and no reset
     * compression flag</p>
     *
     * @param headers the {@link Fields}
     * @param close   the value of the close flag
     */
    public HeadersInfo(Fields headers, boolean close)
    {
        this(headers, close, false);
    }

    /**
     * <p>Creates a new {@link HeadersInfo} instance with the given headers, the given close flag and the given reset
     * compression flag</p>
     *
     * @param headers          the {@link Fields}
     * @param close            the value of the close flag
     * @param resetCompression the value of the reset compression flag
     */
    public HeadersInfo(Fields headers, boolean close, boolean resetCompression)
    {
        this.headers = headers;
        this.close = close;
        this.resetCompression = resetCompression;
    }

    /**
     * <p>Creates a new {@link HeadersInfo} instance with the given headers, the given close flag and the given reset
     * compression flag</p>
     *
     * @param timeout          the operation's timeout
     * @param unit             the timeout's unit
     * @param headers          the {@link Fields}
     * @param close            the value of the close flag
     * @param resetCompression the value of the reset compression flag
     */
    public HeadersInfo(long timeout, TimeUnit unit, boolean close, boolean resetCompression, Fields headers)
    {
        super(timeout, unit);
        this.close = close;
        this.resetCompression = resetCompression;
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
     * @return the value of the reset compression flag
     */
    public boolean isResetCompression()
    {
        return resetCompression;
    }

    /**
     * @return the {@link Fields}
     */
    public Fields getHeaders()
    {
        return headers;
    }

    /**
     * @return the close and reset compression flags as integer
     * @see #FLAG_CLOSE
     * @see #FLAG_RESET_COMPRESSION
     */
    public byte getFlags()
    {
        byte flags = isClose() ? FLAG_CLOSE : 0;
        flags += isResetCompression() ? FLAG_RESET_COMPRESSION : 0;
        return flags;
    }

    @Override
    public String toString()
    {
        return String.format("HEADER close=%b %s", close, headers);
    }
}
