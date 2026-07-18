//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.common;

import org.eclipse.jetty.quic.util.VarLenInt;

/// Stream id utilities.
///
/// A stream id is made of a progressive value and 2 least significant bits
/// that indicate whether the stream is unidirectional (bit 1) and whether the
/// stream is server-initiated (bit 0).
///
/// Furthermore, stream id are encoded using [VarLenInt], which steals
/// the 2 most significant bits, leaving 60 bits for the progressive value,
/// whose max value is captured by constant [#MAX_PROGRESSIVE].
///
/// The encoded representation is therefore the following:
///
/// ```
/// 6                                                              0
/// 3                                                              0
/// XX------------------------------------------------------------US
/// Legend:
/// X : VarLenInt encoding bit
/// U : uni/bi directional bit
/// S : client/server bit
/// - : progressive bit
/// ```
public class StreamId
{
    /// The max value of the progressive part of a stream id.
    public static final long MAX_PROGRESSIVE = (1L << 60) - 1;

    /// The max count of stream ids.
    public static final long MAX_COUNT = MAX_PROGRESSIVE + 1;

    private static final long UNI_MASK = 0b10L;
    private static final long SERVER_MASK = 0b01L;

    /// Returns the encoded stream type, that is the value
    /// of the two least significant bits of the stream id.
    ///
    /// Returns:
    ///
    ///   - 0 - for bidirectional client stream ids
    ///   - 1 - for bidirectional server stream ids
    ///   - 2 - for unidirectional client stream ids
    ///   - 3 - for unidirectional server stream ids
    ///
    /// @param streamId the stream id to extract the type from
    /// @return the encoded stream type
    public static int type(long streamId)
    {
        return (int)(streamId & (UNI_MASK | SERVER_MASK));
    }

    /// Returns the progressive value of the stream id, that is
    /// progressive bits shifted by two bits to the right.
    ///
    /// For example, for unidirectional client (type 0b10)
    /// stream id `0b10110` returns `0b101 == 5`.
    ///
    /// @param streamId the stream id to extract the progressive from
    /// @return the progressive value of the stream id
    /// @see #type(long)
    /// @see #exceedsMaxProgressive(long)
    public static long progressive(long streamId)
    {
        return streamId >>> 2;
    }

    /// Tests whether the given stream id exceeds the
    /// [`max progressive value`][#MAX_PROGRESSIVE].
    ///
    /// @param streamId the stream id to test
    /// @return whether the stream id exceeds the max progressive value
    /// @see #progressive(long)
    public static boolean exceedsMaxProgressive(long streamId)
    {
        // Value -1 is, in binary, all ones.
        // Unsigned-shifting right by 2 yields 2^62-1, which is a positive value (4611686018427387903).
        // However, encoding with VarLenInt steals the 2 msb, so the max progressive number is 2^60-1.
        return progressive(streamId) > MAX_PROGRESSIVE;
    }

    /// Tests whether the given stream id is bidirectional.
    ///
    /// @param streamId the stream id to test
    /// @return whether the stream id is bidirectional
    public static boolean isBidirectional(long streamId)
    {
        return (streamId & UNI_MASK) == 0;
    }

    /// Generates and returns a new stream id, or -1 if the stream id cannot be generated.
    ///
    /// Stream ids are typically encoded via [VarLenInt] (which steals the 2 msb),
    /// and encode uni/bi directional and client/server (which steals the 2 lsb).
    ///
    /// Therefore the progressive number only has 60 bits available, or 2<sup>60</sup>-1 values,
    /// from 0 to 1152921504606846975; trying to generate a stream id with a larger progressive
    /// value results in `-1` to be returned by this method.
    ///
    /// @param progressive the stream id progressive number
    /// @param bidirectional whether the stream is bidirectional
    /// @param client whether the stream is client-initiated
    /// @return an encoded stream id, or -1 if the stream id cannot be generated
    public static long newStreamId(long progressive, boolean bidirectional, boolean client)
    {
        if (progressive > MAX_PROGRESSIVE)
            return -1;
        long streamId = progressive << 2;
        if (!bidirectional)
            streamId |= UNI_MASK;
        if (!client)
            streamId |= SERVER_MASK;
        return streamId;
    }

    /// Returns whether a stream id is local with
    /// respect to the given `client` parameter.
    ///
    /// @param streamId the stream id to test
    /// @param client the side asking for locality
    /// @return whether the stream id is local to the given side
    public static boolean isLocal(long streamId, boolean client)
    {
        boolean server = (streamId & SERVER_MASK) == SERVER_MASK;
        return (server && !client) || (!server && client);
    }

    private StreamId()
    {
    }
}
