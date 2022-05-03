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
