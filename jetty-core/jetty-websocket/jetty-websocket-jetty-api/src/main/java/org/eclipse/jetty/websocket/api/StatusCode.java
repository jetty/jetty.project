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

package org.eclipse.jetty.websocket.api;

/**
 * The <a href="https://www.rfc-editor.org/rfc/rfc6455.html#section-7.4">RFC 6455 specified status codes</a> and
 * <a href="https://www.iana.org/assignments/websocket/websocket.xml#close-code-number">IANA: WebSocket Close Code Number Registry</a>
 */
public final class StatusCode
{
    /**
     * 1000 indicates a normal closure, meaning that the purpose for which the connection was established has been fulfilled.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc6455.html#section-7.4.1">RFC 6455, Section 7.4.1 Defined Status Codes</a>.
     */
    public static final int NORMAL = 1000;

    /**
     * 1001 indicates that an endpoint is "going away", such as a server going down or a browser having navigated away from a page.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc6455.html#section-7.4.1">RFC 6455, Section 7.4.1 Defined Status Codes</a>.
     */
    public static final int SHUTDOWN = 1001;

    /**
     * 1002 indicates that an endpoint is terminating the connection due to a protocol error.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc6455.html#section-7.4.1">RFC 6455, Section 7.4.1 Defined Status Codes</a>.
     */
    public static final int PROTOCOL = 1002;

    /**
     * 1003 indicates that an endpoint is terminating the connection because it has received a type of data it cannot accept (e.g., an endpoint that understands
     * only text data MAY send this if it receives a binary message).
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc6455.html#section-7.4.1">RFC 6455, Section 7.4.1 Defined Status Codes</a>.
     */
    public static final int BAD_DATA = 1003;

    /**
     * Reserved. The specific meaning might be defined in the future.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc6455.html#section-7.4.1">RFC 6455, Section 7.4.1 Defined Status Codes</a>.
     */
    public static final int UNDEFINED = 1004;

    /**
     * 1005 is a reserved value and MUST NOT be set as a status code in a Close control frame by an endpoint. It is designated for use in applications expecting
     * a status code to indicate that no status code was actually present.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc6455.html#section-7.4.1">RFC 6455, Section 7.4.1 Defined Status Codes</a>.
     */
    public static final int NO_CODE = 1005;

    /**
     * 1006 is a reserved value and MUST NOT be set as a status code in a Close control frame by an endpoint. It is designated for use in applications expecting
     * a status code to indicate that the connection was closed abnormally, e.g., without sending or receiving a Close control frame.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc6455.html#section-7.4.1">RFC 6455, Section 7.4.1 Defined Status Codes</a>.
     */
    public static final int NO_CLOSE = 1006;

    /**
     * Abnormal Close is a synonym for {@link #NO_CLOSE}, used to indicate a close
     * condition where no close frame was processed from the remote side.
     */
    public static final int ABNORMAL = NO_CLOSE;

    /**
     * 1007 indicates that an endpoint is terminating the connection because it has received data within a message that was not consistent with the type of the
     * message (e.g., non-UTF-8 [<a href="https://www.rfc-editor.org/rfc/rfc3629.html">RFC3629</a>] data within a text message).
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc6455.html#section-7.4.1">RFC 6455, Section 7.4.1 Defined Status Codes</a>.
     */
    public static final int BAD_PAYLOAD = 1007;

    /**
     * 1008 indicates that an endpoint is terminating the connection because it has received a message that violates its policy. This is a generic status code
     * that can be returned when there is no other more suitable status code (e.g., 1003 or 1009) or if there is a need to hide specific details about the
     * policy.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc6455.html#section-7.4.1">RFC 6455, Section 7.4.1 Defined Status Codes</a>.
     */
    public static final int POLICY_VIOLATION = 1008;

    /**
     * 1009 indicates that an endpoint is terminating the connection because it has received a message that is too big for it to process.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc6455.html#section-7.4.1">RFC 6455, Section 7.4.1 Defined Status Codes</a>.
     */
    public static final int MESSAGE_TOO_LARGE = 1009;

    /**
     * 1010 indicates that an endpoint (client) is terminating the connection because it has expected the server to negotiate one or more extension, but the
     * server didn't return them in the response message of the WebSocket handshake. The list of extensions that are needed SHOULD appear in the /reason/ part
     * of the Close frame. Note that this status code is not used by the server, because it can fail the WebSocket handshake instead.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc6455.html#section-7.4.1">RFC 6455, Section 7.4.1 Defined Status Codes</a>.
     */
    public static final int REQUIRED_EXTENSION = 1010;

    /**
     * 1011 indicates that a server is terminating the connection because it encountered an unexpected condition that prevented it from fulfilling the request.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc6455.html#section-7.4.1">RFC 6455, Section 7.4.1 Defined Status Codes</a>.
     */
    public static final int SERVER_ERROR = 1011;

    /**
     * 1012 indicates that the service is restarted. a client may reconnect, and if it chooses to do, should reconnect using a randomized delay of 5 - 30s.
     *
     * @see <a href="https://mailarchive.ietf.org/arch/msg/hybi/76l9lV-PKcIFZF87EdPv1e65TBk/">[hybi] Additional WebSocket Close Error Codes</a>
     */
    public static final int SERVICE_RESTART = 1012;

    /**
     * 1013 indicates that the service is experiencing overload. a client should only connect to a different IP (when there are multiple for the target) or
     * reconnect to the same IP upon user action.
     *
     * @see <a href="https://mailarchive.ietf.org/arch/msg/hybi/76l9lV-PKcIFZF87EdPv1e65TBk/">[hybi] Additional WebSocket Close Error Codes</a>
     */
    public static final int TRY_AGAIN_LATER = 1013;

    /**
     * 1014 indicates that a gateway or proxy received and invalid upstream response.
     *
     * @see <a href="https://mailarchive.ietf.org/arch/msg/hybi/VOLI2xp4tzFnIFYespe6oOtpFXA/">[hybi] WebSocket Subprotocol Close Code: Bad Gateway</a>
     */
    public static final int INVALID_UPSTREAM_RESPONSE = 1014;

    /**
     * 1015 is a reserved value and MUST NOT be set as a status code in a Close control frame by an endpoint. It is designated for use in applications expecting
     * a status code to indicate that the connection was closed due to a failure to perform a TLS handshake (e.g., the server certificate can't be verified).
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc6455.html#section-7.4.1">RFC 6455, Section 7.4.1 Defined Status Codes</a>.
     */
    public static final int FAILED_TLS_HANDSHAKE = 1015;

    /**
     * Test if provided status code can be sent/received on a WebSocket close.
     * <p>
     * This honors the RFC6455 rules and IANA rules.
     * </p>
     *
     * @param statusCode the statusCode to test
     * @return true if transmittable
     */
    public static boolean isTransmittable(int statusCode)
    {
        return (statusCode == NORMAL) ||
            (statusCode == SHUTDOWN) ||
            (statusCode == PROTOCOL) ||
            (statusCode == BAD_DATA) ||
            (statusCode == BAD_PAYLOAD) ||
            (statusCode == POLICY_VIOLATION) ||
            (statusCode == MESSAGE_TOO_LARGE) ||
            (statusCode == REQUIRED_EXTENSION) ||
            (statusCode == SERVER_ERROR) ||
            (statusCode == SERVICE_RESTART) ||
            (statusCode == TRY_AGAIN_LATER) ||
            (statusCode == INVALID_UPSTREAM_RESPONSE);
    }
}
