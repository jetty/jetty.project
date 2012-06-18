package org.eclipse.jetty.websocket.api;

/**
 * Constants for WebSocket protocol as-defined in <a href="https://tools.ietf.org/html/rfc6455">RFC-6455</a>.
 */
public class WebSocket {
	/**
	 * Per <a href="https://tools.ietf.org/html/rfc6455#section-1.3">RFC 6455, section 1.3</a> - Opening Handshake - this version is "13"
	 */
    public final static int VERSION=13;
    
    public final static int CLOSE_NORMAL=1000;
    public final static int CLOSE_SHUTDOWN=1001;
    public final static int CLOSE_PROTOCOL=1002;
    public final static int CLOSE_BAD_DATA=1003;
    public final static int CLOSE_UNDEFINED=1004;
    public final static int CLOSE_NO_CODE=1005;
    public final static int CLOSE_NO_CLOSE=1006;
    public final static int CLOSE_BAD_PAYLOAD=1007;
    public final static int CLOSE_POLICY_VIOLATION=1008;
    public final static int CLOSE_MESSAGE_TOO_LARGE=1009;
    public final static int CLOSE_REQUIRED_EXTENSION=1010;
    public final static int CLOSE_SERVER_ERROR=1011;
    public final static int CLOSE_FAILED_TLS_HANDSHAKE=1015;
}
