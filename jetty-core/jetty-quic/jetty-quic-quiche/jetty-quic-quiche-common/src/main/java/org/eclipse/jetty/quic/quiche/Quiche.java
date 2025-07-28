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
            QUICHE_CC_CUBIC = 1,
            QUICHE_CC_BBR = 2;
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

        // The received data exceeds the stream's final size.
        QUICHE_ERR_FINAL_SIZE = -13,

        // Error in congestion control.
        QUICHE_ERR_CONGESTION_CONTROL = -14,

        // The specified stream was stopped by the peer.
        QUICHE_ERR_STREAM_STOPPED = -15,

        // The specified stream was reset by the peer.
        QUICHE_ERR_STREAM_RESET = -16,

        // Too many identifiers were provided.
        QUICHE_ERR_ID_LIMIT = -17,

        // Not enough available identifiers.
        QUICHE_ERR_OUT_OF_IDENTIFIERS = -18,

        // Error in key update.
        QUICHE_ERR_KEY_UPDATE = -19,

        // The peer sent more data in CRYPTO frames than we can buffer.
        QUICHE_ERR_CRYPTO_BUFFER_EXCEEDED = -20,

        // The peer sent an ACK frame with an invalid range.
        QUICHE_ERR_INVALID_ACK_RANGE = -21,

        // The peer send an ACK frame for a skipped packet used for Optimistic ACK
        // mitigation.
        QUICHE_ERR_OPTIMISTIC_ACK_DETECTED = -22;

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
            if (err == QUICHE_ERR_ID_LIMIT)
                return "QUICHE_ERR_ID_LIMIT";
            if (err == QUICHE_ERR_OUT_OF_IDENTIFIERS)
                return "QUICHE_ERR_OUT_OF_IDENTIFIERS";
            if (err == QUICHE_ERR_KEY_UPDATE)
                return "QUICHE_ERR_KEY_UPDATE";
            if (err == QUICHE_ERR_CRYPTO_BUFFER_EXCEEDED)
                return "QUICHE_ERR_CRYPTO_BUFFER_EXCEEDED";
            if (err == QUICHE_ERR_INVALID_ACK_RANGE)
                return "QUICHE_ERR_INVALID_ACK_RANGE";
            if (err == QUICHE_ERR_OPTIMISTIC_ACK_DETECTED)
                return "QUICHE_ERR_OPTIMISTIC_ACK_DETECTED";
            return "?? " + err;
        }
    }

    // QUIC Transport Error Codes: https://www.iana.org/assignments/quic/quic.xhtml#quic-transport-error-codes
    interface quic_error
    {
        long NO_ERROR = 0,
            INTERNAL_ERROR = 1,
            CONNECTION_REFUSED = 2,
            FLOW_CONTROL_ERROR = 3,
            STREAM_LIMIT_ERROR = 4,
            STREAM_STATE_ERROR = 5,
            FINAL_SIZE_ERROR = 6,
            FRAME_ENCODING_ERROR = 7,
            TRANSPORT_PARAMETER_ERROR = 8,
            CONNECTION_ID_LIMIT_ERROR = 9,
            PROTOCOL_VIOLATION = 10,
            INVALID_TOKEN = 11,
            APPLICATION_ERROR = 12,
            CRYPTO_BUFFER_EXCEEDED = 13,
            KEY_UPDATE_ERROR = 14,
            AEAD_LIMIT_REACHED = 15,
            NO_VIABLE_PATH = 16,
            VERSION_NEGOTIATION_ERROR = 17;

        static String errToString(long err)
        {
            if (err == NO_ERROR)
                return "NO_ERROR";
            if (err == INTERNAL_ERROR)
                return "INTERNAL_ERROR";
            if (err == CONNECTION_REFUSED)
                return "CONNECTION_REFUSED";
            if (err == FLOW_CONTROL_ERROR)
                return "FLOW_CONTROL_ERROR";
            if (err == STREAM_LIMIT_ERROR)
                return "STREAM_LIMIT_ERROR";
            if (err == STREAM_STATE_ERROR)
                return "STREAM_STATE_ERROR";
            if (err == FINAL_SIZE_ERROR)
                return "FINAL_SIZE_ERROR";
            if (err == FRAME_ENCODING_ERROR)
                return "FRAME_ENCODING_ERROR";
            if (err == TRANSPORT_PARAMETER_ERROR)
                return "TRANSPORT_PARAMETER_ERROR";
            if (err == CONNECTION_ID_LIMIT_ERROR)
                return "CONNECTION_ID_LIMIT_ERROR";
            if (err == PROTOCOL_VIOLATION)
                return "PROTOCOL_VIOLATION";
            if (err == INVALID_TOKEN)
                return "INVALID_TOKEN";
            if (err == APPLICATION_ERROR)
                return "APPLICATION_ERROR";
            if (err == CRYPTO_BUFFER_EXCEEDED)
                return "CRYPTO_BUFFER_EXCEEDED";
            if (err == KEY_UPDATE_ERROR)
                return "KEY_UPDATE_ERROR";
            if (err == AEAD_LIMIT_REACHED)
                return "AEAD_LIMIT_REACHED";
            if (err == NO_VIABLE_PATH)
                return "NO_VIABLE_PATH";
            if (err == VERSION_NEGOTIATION_ERROR)
                return "VERSION_NEGOTIATION_ERROR";
            if (err >= 0x100 && err <= 0x01FF)
                return "CRYPTO_ERROR " + tls_alert.errToString(err - 0x100);
            return "?? " + err;
        }
    }

    // TLS Alerts: https://www.iana.org/assignments/tls-parameters/tls-parameters.xhtml#tls-parameters-6
    interface tls_alert
    {
        long CLOSE_NOTIFY = 0,
            UNEXPECTED_MESSAGE = 10,
            BAD_RECORD_MAC = 20,
            RECORD_OVERFLOW = 22,
            HANDSHAKE_FAILURE = 40,
            BAD_CERTIFICATE = 42,
            UNSUPPORTED_CERTIFICATE = 43,
            CERTIFICATE_REVOKED = 44,
            CERTIFICATE_EXPIRED = 45,
            CERTIFICATE_UNKNOWN = 46,
            ILLEGAL_PARAMETER = 47,
            UNKNOWN_CA = 48,
            ACCESS_DENIED = 49,
            DECODE_ERROR = 50,
            DECRYPT_ERROR = 51,
            TOO_MANY_CIDS_REQUESTED = 52,
            PROTOCOL_VERSION = 70,
            INSUFFICIENT_SECURITY = 71,
            INTERNAL_ERROR = 80,
            INAPPROPRIATE_FALLBACK = 86,
            USER_CANCELED = 90,
            MISSING_EXTENSION = 109,
            UNSUPPORTED_EXTENSION = 110,
            UNRECOGNIZED_NAME = 112,
            BAD_CERTIFICATE_STATUS_RESPONSE = 113,
            UNKNOWN_PSK_IDENTITY = 115,
            CERTIFICATE_REQUIRED = 116,
            NO_APPLICATION_PROTOCOL = 120;

        static String errToString(long err)
        {
            if (err == CLOSE_NOTIFY)
                return "CLOSE_NOTIFY";
            if (err == UNEXPECTED_MESSAGE)
                return "UNEXPECTED_MESSAGE";
            if (err == BAD_RECORD_MAC)
                return "BAD_RECORD_MAC";
            if (err == RECORD_OVERFLOW)
                return "RECORD_OVERFLOW";
            if (err == HANDSHAKE_FAILURE)
                return "HANDSHAKE_FAILURE";
            if (err == BAD_CERTIFICATE)
                return "BAD_CERTIFICATE";
            if (err == UNSUPPORTED_CERTIFICATE)
                return "UNSUPPORTED_CERTIFICATE";
            if (err == CERTIFICATE_REVOKED)
                return "CERTIFICATE_REVOKED";
            if (err == CERTIFICATE_EXPIRED)
                return "CERTIFICATE_EXPIRED";
            if (err == CERTIFICATE_UNKNOWN)
                return "CERTIFICATE_UNKNOWN";
            if (err == ILLEGAL_PARAMETER)
                return "ILLEGAL_PARAMETER";
            if (err == UNKNOWN_CA)
                return "UNKNOWN_CA";
            if (err == ACCESS_DENIED)
                return "ACCESS_DENIED";
            if (err == DECODE_ERROR)
                return "DECODE_ERROR";
            if (err == DECRYPT_ERROR)
                return "DECRYPT_ERROR";
            if (err == TOO_MANY_CIDS_REQUESTED)
                return "TOO_MANY_CIDS_REQUESTED";
            if (err == PROTOCOL_VERSION)
                return "PROTOCOL_VERSION";
            if (err == INSUFFICIENT_SECURITY)
                return "INSUFFICIENT_SECURITY";
            if (err == INTERNAL_ERROR)
                return "INTERNAL_ERROR";
            if (err == INAPPROPRIATE_FALLBACK)
                return "INAPPROPRIATE_FALLBACK";
            if (err == USER_CANCELED)
                return "USER_CANCELED";
            if (err == MISSING_EXTENSION)
                return "MISSING_EXTENSION";
            if (err == UNSUPPORTED_EXTENSION)
                return "UNSUPPORTED_EXTENSION";
            if (err == UNRECOGNIZED_NAME)
                return "UNRECOGNIZED_NAME";
            if (err == BAD_CERTIFICATE_STATUS_RESPONSE)
                return "BAD_CERTIFICATE_STATUS_RESPONSE";
            if (err == UNKNOWN_PSK_IDENTITY)
                return "UNKNOWN_PSK_IDENTITY";
            if (err == CERTIFICATE_REQUIRED)
                return "CERTIFICATE_REQUIRED";
            if (err == NO_APPLICATION_PROTOCOL)
                return "NO_APPLICATION_PROTOCOL";
            return "?? " + err;
        }
    }
}
