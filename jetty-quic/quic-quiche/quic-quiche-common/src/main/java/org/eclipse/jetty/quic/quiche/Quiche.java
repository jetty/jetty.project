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

package org.eclipse.jetty.quic.quiche;

public interface Quiche
{
    // The current QUIC wire version.
    int QUICHE_PROTOCOL_VERSION = 0x00000001;
    // The maximum length of a connection ID.
    int QUICHE_MAX_CONN_ID_LEN = 20;
    // The minimum length of Initial packets sent by a client.
    int QUICHE_MIN_CLIENT_INITIAL_LEN = 1200;

    interface quiche_cc_algorithm
    {
        int QUICHE_CC_RENO = 0,
            QUICHE_CC_CUBIC = 1;
    }

    interface quiche_error
    {
        // There is no more work to do.
        long QUICHE_ERR_DONE = -1,

        // The provided buffer is too short.
        QUICHE_ERR_BUFFER_TOO_SHORT = -2,

        // The provided packet cannot be parsed because its version is unknown.
        QUICHE_ERR_UNKNOWN_VERSION = -3,

        // The provided packet cannot be parsed because it contains an invalid
        // frame.
        QUICHE_ERR_INVALID_FRAME = -4,

        // The provided packet cannot be parsed.
        QUICHE_ERR_INVALID_PACKET = -5,

        // The operation cannot be completed because the connection is in an
        // invalid state.
        QUICHE_ERR_INVALID_STATE = -6,

        // The operation cannot be completed because the stream is in an
        // invalid state.
        QUICHE_ERR_INVALID_STREAM_STATE = -7,

        // The peer's transport params cannot be parsed.
        QUICHE_ERR_INVALID_TRANSPORT_PARAM = -8,

        // A cryptographic operation failed.
        QUICHE_ERR_CRYPTO_FAIL = -9,

        // The TLS handshake failed.
        QUICHE_ERR_TLS_FAIL = -10,

        // The peer violated the local flow control limits.
        QUICHE_ERR_FLOW_CONTROL = -11,

        // The peer violated the local stream limits.
        QUICHE_ERR_STREAM_LIMIT = -12,

        // The specified stream was stopped by the peer.
        QUICHE_ERR_STREAM_STOPPED = -15,

        // The specified stream was reset by the peer.
        QUICHE_ERR_STREAM_RESET = -16,

        // The received data exceeds the stream's final size.
        QUICHE_ERR_FINAL_SIZE = -13,

        // Error in congestion control.
        QUICHE_ERR_CONGESTION_CONTROL = -14;

        static String errToString(long err)
        {
            if (err == QUICHE_ERR_DONE)
                return "QUICHE_ERR_DONE";
            if (err == QUICHE_ERR_BUFFER_TOO_SHORT)
                return "QUICHE_ERR_BUFFER_TOO_SHORT";
            if (err == QUICHE_ERR_UNKNOWN_VERSION)
                return "QUICHE_ERR_UNKNOWN_VERSION";
            if (err == QUICHE_ERR_INVALID_FRAME)
                return "QUICHE_ERR_INVALID_FRAME";
            if (err == QUICHE_ERR_INVALID_PACKET)
                return "QUICHE_ERR_INVALID_PACKET";
            if (err == QUICHE_ERR_INVALID_STATE)
                return "QUICHE_ERR_INVALID_STATE";
            if (err == QUICHE_ERR_INVALID_STREAM_STATE)
                return "QUICHE_ERR_INVALID_STREAM_STATE";
            if (err == QUICHE_ERR_INVALID_TRANSPORT_PARAM)
                return "QUICHE_ERR_INVALID_TRANSPORT_PARAM";
            if (err == QUICHE_ERR_CRYPTO_FAIL)
                return "QUICHE_ERR_CRYPTO_FAIL";
            if (err == QUICHE_ERR_TLS_FAIL)
                return "QUICHE_ERR_TLS_FAIL";
            if (err == QUICHE_ERR_FLOW_CONTROL)
                return "QUICHE_ERR_FLOW_CONTROL";
            if (err == QUICHE_ERR_STREAM_LIMIT)
                return "QUICHE_ERR_STREAM_LIMIT";
            if (err == QUICHE_ERR_FINAL_SIZE)
                return "QUICHE_ERR_FINAL_SIZE";
            if (err == QUICHE_ERR_CONGESTION_CONTROL)
                return "QUICHE_ERR_CONGESTION_CONTROL";
            if (err == QUICHE_ERR_STREAM_STOPPED)
                return "QUICHE_ERR_STREAM_STOPPED";
            if (err == QUICHE_ERR_STREAM_RESET)
                return "QUICHE_ERR_STREAM_RESET";
            return "?? " + err;
        }
    }
}
