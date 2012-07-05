package org.eclipse.jetty.websocket.protocol;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;

import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.StringUtil;

/**
 * Logic for working with the <code>Sec-WebSocket-Accept</code> and <code>Sec-WebSocket-Accept</code> headers.
 * <p>
 * This is kept separate from Connection objects to facilitate difference in behavior between client and server, as well as making testing easier.
 */
public class AcceptHash
{
    /**
     * Globally Unique Identifier for use in WebSocket handshake within <code>Sec-WebSocket-Accept</code> and <code>Sec-WebSocket-Key</code> http headers.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-1.3">Opening Handshake (Section 1.3)</a>
     */
    private final static byte[] MAGIC;

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
     * 
     * @param key
     *            the key to hash
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
