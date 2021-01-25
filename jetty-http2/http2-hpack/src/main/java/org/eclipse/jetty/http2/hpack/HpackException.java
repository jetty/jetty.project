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

package org.eclipse.jetty.http2.hpack;

@SuppressWarnings("serial")
public abstract class HpackException extends Exception
{
    HpackException(String messageFormat, Object... args)
    {
        super(String.format(messageFormat, args));
    }

    /**
     * A Stream HPACK exception.
     * <p>Stream exceptions are not fatal to the connection and the
     * hpack state is complete and able to continue handling other
     * decoding/encoding for the session.
     * </p>
     */
    public static class StreamException extends HpackException
    {
        StreamException(String messageFormat, Object... args)
        {
            super(messageFormat, args);
        }
    }

    /**
     * A Session HPACK Exception.
     * <p>Session exceptions are fatal for the stream and the HPACK
     * state is unable to decode/encode further. </p>
     */
    public static class SessionException extends HpackException
    {
        SessionException(String messageFormat, Object... args)
        {
            super(messageFormat, args);
        }
    }

    public static class CompressionException extends SessionException
    {
        public CompressionException(String messageFormat, Object... args)
        {
            super(messageFormat, args);
        }
    }
}
