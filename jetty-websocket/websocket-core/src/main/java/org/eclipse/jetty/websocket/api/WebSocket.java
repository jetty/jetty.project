package org.eclipse.jetty.websocket.api;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;

import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.StringUtil;

/**
 * Constants for WebSocket protocol as-defined in <a href="https://tools.ietf.org/html/rfc6455">RFC-6455</a>.
 */
public class WebSocket
{
    /**
     * Per <a href="https://tools.ietf.org/html/rfc6455#section-1.3">RFC 6455, section 1.3</a> - Opening Handshake - this version is "13"
     */
    public final static int VERSION = 13;

    /**
     * Globally Unique Identifier for use in WebSocket handshake within <code>Sec-WebSocket-Accept</code> and <code>Sec-WebSocket-Key</code> http headers.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-1.3">Opening Handshake (Section 1.3)</a>
     */
    private final static byte[] MAGIC;

    public final static short CLOSE_NORMAL = 1000;
    public final static short CLOSE_SHUTDOWN = 1001;
    public final static short CLOSE_PROTOCOL = 1002;
    public final static short CLOSE_BAD_DATA = 1003;
    public final static short CLOSE_UNDEFINED = 1004;
    public final static short CLOSE_NO_CODE = 1005;
    public final static short CLOSE_NO_CLOSE = 1006;
    public final static short CLOSE_BAD_PAYLOAD = 1007;
    public final static short CLOSE_POLICY_VIOLATION = 1008;
    public final static short CLOSE_MESSAGE_TOO_LARGE = 1009;
    public final static short CLOSE_REQUIRED_EXTENSION = 1010;
    public final static short CLOSE_SERVER_ERROR = 1011;
    public final static short CLOSE_FAILED_TLS_HANDSHAKE = 1015;

    static
    {
        try
        {
            MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes(StringUtil.__ISO_8859_1);
        }
        catch (UnsupportedEncodingException e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Concatenate the provided key with the Magic GUID and return the Base64 encoded form.
     * @param key the key to hash
     * @return the <code>Sec-WebSocket-Accept</code> header response (per opening handshake spec)
     */
    public static String hashKey(String key)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA1");
            md.update(key.getBytes("UTF-8"));
            md.update(MAGIC);
            return new String(B64Code.encode(md.digest()));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
}
