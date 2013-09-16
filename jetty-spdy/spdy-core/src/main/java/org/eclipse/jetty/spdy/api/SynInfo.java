//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

/**
 * <p>A container for SYN_STREAM frames metadata and data.</p>
 */
public class SynInfo
{
    /**
     * <p>Flag that indicates that this {@link DataInfo} is the last frame in the stream.</p>
     *
     * @see #isClose()
     * @see #getFlags()
     */
    public static final byte FLAG_CLOSE = 1;

    private final boolean close;
    private final byte priority;
    private final Headers headers;

    /**
     * <p>Creates a new {@link SynInfo} instance with empty headers and the given close flag,
     * not unidirectional, without associated stream, and with default priority.</p>
     *
     * @param close the value of the close flag
     */
    public SynInfo(boolean close)
    {
        this(new Headers(), close);
    }

    /**
     * <p>Creates a {@link ReplyInfo} instance with the given headers and the given close flag,
     * not unidirectional, without associated stream, and with default priority.</p>
     *
     * @param headers the {@link Headers}
     * @param close the value of the close flag
     */
    public SynInfo(Headers headers, boolean close)
    {
        this(headers, close, (byte)0);
    }

    /**
     * <p>
     * Creates a {@link ReplyInfo} instance with the given headers, the given close flag and with the given priority.
     * </p>
     * 
     * @param headers
     *            the {@link Headers}
     * @param close
     *            the value of the close flag
     * @param priority
     *            the priority
     */
    public SynInfo(Headers headers, boolean close, byte priority)
    {
        this.close = close;
        this.priority = priority;
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
     * @return the priority
     */
    public byte getPriority()
    {
        return priority;
    }

    /**
     * @return the {@link Headers}
     */
    public Headers getHeaders()
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
        return String.format("SYN close=%b headers=%s", close, headers);
    }
}
