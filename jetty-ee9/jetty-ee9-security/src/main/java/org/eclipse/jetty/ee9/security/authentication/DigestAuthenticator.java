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

package org.eclipse.jetty.ee9.security.authentication;

import java.io.IOException;
import java.io.Serial;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.BitSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.nested.Authentication;
import org.eclipse.jetty.ee9.nested.Authentication.User;
import org.eclipse.jetty.ee9.nested.Request;
import org.eclipse.jetty.ee9.security.SecurityHandler;
import org.eclipse.jetty.ee9.security.ServerAuthException;
import org.eclipse.jetty.ee9.security.UserAuthentication;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The nonce max age in ms can be set with the {@link SecurityHandler#setInitParameter(String, String)}
 * using the name "maxNonceAge".  The nonce max count can be set with {@link SecurityHandler#setInitParameter(String, String)}
 * using the name "maxNonceCount".  When the age or count is exceeded, the nonce is considered stale.
 */
public class DigestAuthenticator extends LoginAuthenticator
{
    private static final Logger LOG = LoggerFactory.getLogger(DigestAuthenticator.class);
    private static final QuotedStringTokenizer TOKENIZER = QuotedStringTokenizer.builder().delimiters("=, ").returnDelimiters().allowEmbeddedQuotes().build();

    private final SecureRandom _random = new SecureRandom();
    private final Map<String, Nonce> _nonces = new ConcurrentHashMap<>();
    private final SecretKey _secretKey;
    private long _maxNonceAgeMs = 60 * 1000;
    private int _maxNonceCount = 1024;
    private String _algorithm = "SHA-256";
    private boolean _userHashing;

    public DigestAuthenticator()
    {
        byte[] bytes = new byte[32];
        _random.nextBytes(bytes);
        _secretKey = new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Override
    public void setConfiguration(AuthConfiguration configuration)
    {
        super.setConfiguration(configuration);

        String mna = configuration.getInitParameter("maxNonceAge");
        if (mna != null)
            setMaxNonceAge(Long.parseLong(mna));
        String mnc = configuration.getInitParameter("maxNonceCount");
        if (mnc != null)
            setMaxNonceCount(Integer.parseInt(mnc));
    }

    /**
     * @return the max number of times a nonce is used
     */
    public int getMaxNonceCount()
    {
        return _maxNonceCount;
    }

    /**
     * @param maxNonceCount the max number of times a nonce is used
     */
    public void setMaxNonceCount(int maxNonceCount)
    {
        _maxNonceCount = maxNonceCount;
    }

    /**
     * @return the max age of a nonce in milliseconds
     */
    public long getMaxNonceAge()
    {
        return _maxNonceAgeMs;
    }

    /**
     * @param maxNonceAgeInMillis the max age of a nonce in milliseconds
     */
    public void setMaxNonceAge(long maxNonceAgeInMillis)
    {
        _maxNonceAgeMs = maxNonceAgeInMillis;
    }

    /**
     * @return the {@link MessageDigest} algorithm
     */
    public String getAlgorithm()
    {
        return _algorithm;
    }

    /**
     * @param algorithm the {@link MessageDigest} algorithm
     */
    public void setAlgorithm(String algorithm)
    {
        _algorithm = Objects.requireNonNull(algorithm);
    }

    /**
     * @return whether the username is hashed
     */
    public boolean isUserHashing()
    {
        return _userHashing;
    }

    /**
     * @param userHashing whether the username is hashed
     */
    public void setUserHashing(boolean userHashing)
    {
        _userHashing = userHashing;
    }

    @Override
    public String getAuthMethod()
    {
        return DIGEST_AUTH;
    }

    @Override
    public boolean secureResponse(ServletRequest req, ServletResponse res, boolean mandatory, User validatedUser) throws ServerAuthException
    {
        return true;
    }

    @Override
    public Authentication validateRequest(ServletRequest req, ServletResponse res, boolean mandatory) throws ServerAuthException
    {
        if (!mandatory)
            return new DeferredAuthentication(this);

        HttpServletRequest request = (HttpServletRequest)req;
        HttpServletResponse response = (HttpServletResponse)res;
        String credentials = request.getHeader(getAuthorizationHeader().asString());

        try
        {
            Request baseRequest = Request.getBaseRequest(request);
            if (baseRequest == null)
                return Authentication.UNAUTHENTICATED;

            boolean stale = false;
            if (credentials != null)
            {
                Digest digest = parseDigest(request.getMethod(), credentials);
                if (digest != null)
                {
                    if (verifyOpaque(digest))
                    {
                        int n = checkNonce(digest);
                        if (n > 0)
                        {
                            // Nonce correctness is a prerequisite for trusting digest.uri.
                            // Check that the request URI matches the credential's URI.
                            if (Objects.equals(digest.uri, baseRequest.getHttpURI().getPathQuery()))
                            {
                                UserIdentity user = login(digest.resolvedUserName, digest, request);
                                if (user != null)
                                    return new UserAuthentication(getAuthMethod(), user);
                            }
                        }
                        else if (n == 0)
                        {
                            // Only good nonces can be stale.
                            stale = true;
                        }
                    }
                }
            }

            if (DeferredAuthentication.isDeferred(response))
                return Authentication.UNAUTHENTICATED;

            // Requests that don't have the Authorization header
            // or that have it but it's invalid (e.g. missing/bad
            // nonce, bad opaque, etc.) are replied with 401.

            String domain = request.getContextPath();
            if (domain == null)
                domain = "/";

            // RFC 7616[3.3]: only realm, domain, nonce, opaque, and qop must be quoted.
            // Parameters stale and algorithm must not be quoted.
            String value = "Digest realm=\"%s\"".formatted(_loginService.getName());
            if (!isProxyMode())
                value += ", domain=\"%s\"".formatted(domain);
            String nonce = newNonce(baseRequest);
            value += ", nonce=\"%s\"".formatted(nonce);
            value += ", opaque=\"%s\"".formatted(newOpaque(nonce));
            value += ", stale=%s".formatted(stale);
            value += ", algorithm=%s".formatted(getAlgorithm());
            value += ", qop=\"auth\"";
            value += ", charset=UTF-8";
            value += ", userhash=%s".formatted(isUserHashing());
            response.setHeader(getChallengeHeader().asString(), value);

            response.sendError(getUnauthorizedStatusCode());
            return Authentication.SEND_CONTINUE;
        }
        catch (IOException e)
        {
            throw new ServerAuthException(e);
        }
    }

    private Digest parseDigest(String method, String credentials)
    {
        try
        {
            String name = null;
            String value = null;
            Digest digest = new Digest(method, getAlgorithm());
            Iterator<String> i = TOKENIZER.tokenize(credentials);
            while (i.hasNext())
            {
                String tok = i.next();
                char c = (tok.length() == 1) ? tok.charAt(0) : '\0';
                switch (c)
                {
                    case '=' -> name = value;
                    case ',' -> name = null;
                    case ' ' ->
                    {
                    }
                    default ->
                    {
                        value = tok;
                        if (name != null)
                        {
                            switch (name.toLowerCase(Locale.ROOT))
                            {
                                case "response" -> digest.response = tok;
                                case "username" -> digest.username = tok;
                                case "userhash" -> digest.userhash = Boolean.parseBoolean(tok);
                                case "username*" -> digest.usernameStar = tok;
                                case "realm" -> digest.realm = tok;
                                case "uri" -> digest.uri = tok;
                                case "algorithm" -> digest.algorithm = tok;
                                case "qop" -> digest.qop = tok;
                                case "nonce" -> digest.nonce = tok;
                                case "cnonce" -> digest.cnonce = tok;
                                case "nc" -> digest.nc = tok;
                                case "opaque" -> digest.opaque = tok;
                            }
                            name = null;
                        }
                    }
                }
            }

            if (digest.username != null && digest.usernameStar != null)
                return null;

            if (digest.nc == null || digest.nc.length() != 8)
                return null;

            if (!getAlgorithm().equalsIgnoreCase(digest.algorithm))
                return null;

            if (!"auth".equalsIgnoreCase(digest.qop))
                return null;

            String resolvedUserName;
            if (digest.userhash && isUserHashing())
            {
                if (digest.username == null)
                    return null;
                resolvedUserName = resolveHashedUserName(digest.username);
            }
            else if (digest.usernameStar != null)
            {
                resolvedUserName = resolveEncodedUserName(digest.usernameStar);
            }
            else
            {
                resolvedUserName = digest.username;
            }
            digest.resolvedUserName = resolvedUserName;

            return digest;
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to parse digest", x);
            return null;
        }
    }

    /**
     * <p>Resolves the hashed username received in the {@code Authorization} header.</p>
     * <p>This method should look up the plain username from the username hash.</p>
     * <p>By default, this method throws {@link UnsupportedOperationException}.</p>
     *
     * @param hashedUserName the hashed username
     * @return the plain username
     */
    protected String resolveHashedUserName(String hashedUserName)
    {
        throw new UnsupportedOperationException();
    }

    /**
     * <p>Resolves the encoded username received in the {@code Authorization} header.</p>
     * <p>The username is encoded with RFC-5987 and this method should decode it.</p>
     * <p>By default, this method only decodes RFC-5987 usernames encoded with UTF-8
     * and no language; for example username: {@code UTF-8''caf%E2%82%AC} is decoded
     * as {@code caf€}.</p>
     *
     * @param encodedUserName the encoded username
     * @return the decoded username
     */
    protected String resolveEncodedUserName(String encodedUserName)
    {
        try
        {
            if (encodedUserName == null)
                return null;
            String encodedPrefix = "UTF-8''";
            if (encodedUserName.startsWith(encodedPrefix))
                return URI.create("scheme:" + encodedUserName.substring(encodedPrefix.length())).getSchemeSpecificPart();
            return encodedUserName;
        }
        catch (Throwable x)
        {
            // Likely badly encoded username.
            return null;
        }
    }

    @Override
    public UserIdentity login(String username, Object credentials, ServletRequest request)
    {
        Digest digest = (Digest)credentials;
        if (!Objects.equals(digest.realm, _loginService.getName()))
            return null;
        return super.login(username, credentials, request);
    }

    public String newNonce(Request request)
    {
        try
        {
            // The format of the nonce is such that it does
            // not need server-side storage to be checked.
            // nonce = base64(timestamp | random | hmac(timestamp | random))
            Mac mac = newMac();
            int dataLength = 2 * Long.BYTES;
            byte[] bytes = new byte[dataLength + mac.getMacLength()];
            ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
            byteBuffer.putLong(request.getTimeStamp()).putLong(_random.nextLong());
            mac.update(bytes, 0, dataLength);
            mac.doFinal(bytes, dataLength);
            return Base64.getEncoder().encodeToString(bytes);
        }
        catch (GeneralSecurityException x)
        {
            throw new RuntimeException(x);
        }
    }

    /**
     * @param digest the digest data to check
     * @return -1 for a bad nonce, 0 for a stale nonce, 1 for a good nonce
     */
    private int checkNonce(Digest digest)
    {
        try
        {
            String nonce = digest.nonce;
            if (nonce == null)
                return -1;

            String nc = digest.nc;
            if (nc == null)
                return -1;
            long nonceNumber = Long.parseLong(digest.nc, 16);
            if (nonceNumber >= getMaxNonceCount())
                return 0;

            byte[] nonceBytes = Base64.getDecoder().decode(nonce);
            ByteBuffer byteBuffer = ByteBuffer.wrap(nonceBytes);

            long timestamp = byteBuffer.getLong();
            long random = byteBuffer.getLong();

            Mac mac = newMac();
            int dataLength = 2 * Long.BYTES;
            byte[] expected = new byte[dataLength + mac.getMacLength()];
            ByteBuffer.wrap(expected).putLong(timestamp).putLong(random);
            mac.update(expected, 0, dataLength);
            mac.doFinal(expected, dataLength);

            if (!MessageDigest.isEqual(expected, nonceBytes))
                return -1;

            long elapsed = System.currentTimeMillis() - timestamp;
            if (elapsed < 0)
                return -1;

            if (elapsed > getMaxNonceAge())
                return 0;

            // Store the nonce to keep track of digest.nc, the nonce count.

            // First, expire old nonces.
            Iterator<Nonce> iterator = _nonces.values().iterator();
            while (iterator.hasNext())
            {
                Nonce n = iterator.next();
                elapsed = System.currentTimeMillis() - n._timestamp;
                if (elapsed > getMaxNonceAge())
                    iterator.remove();
            }
            // Store the used nonce.
            Nonce n = _nonces.computeIfAbsent(nonce, k -> new Nonce(timestamp, getMaxNonceCount()));
            // Check the nonce number to counter replay attacks.
            if (n.seen((int)nonceNumber))
                return -1;

            return 1;
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to verify nonce", x);
            return -1;
        }
    }

    private String newOpaque(String nonce)
    {
        // The format of the opaque parameter is tied to the nonce:
        // opaque = base64(hmac(nonce))
        byte[] bytes = Base64.getDecoder().decode(nonce);
        Mac mac = newMac();
        byte[] macBytes = mac.doFinal(bytes);
        return Base64.getEncoder().encodeToString(macBytes);
    }

    private boolean verifyOpaque(Digest digest)
    {
        try
        {
            // RFC-7616[3.3]: the opaque parameter SHOULD
            // be sent by the client, but it may not.
            if (digest.opaque == null)
                return true;

            String nonce = digest.nonce;
            if (nonce == null)
                return false;

            Mac mac = newMac();
            byte[] expected = mac.doFinal(Base64.getDecoder().decode(nonce));

            byte[] actual = Base64.getDecoder().decode(digest.opaque);
            return MessageDigest.isEqual(expected, actual);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to verify opaque", x);
            return false;
        }
    }

    private Mac newMac()
    {
        try
        {
            Mac mac = Mac.getInstance(_secretKey.getAlgorithm());
            mac.init(_secretKey);
            return mac;
        }
        catch (Throwable x)
        {
            throw new RuntimeException(x);
        }
    }

    private static class Nonce
    {
        private final AutoLock _lock = new AutoLock();
        private final long _timestamp;
        private final BitSet _seen;

        private Nonce(long timestamp, int size)
        {
            _timestamp = timestamp;
            _seen = new BitSet(size);
        }

        private boolean seen(int number)
        {
            try (AutoLock ignored = _lock.lock())
            {
                if (number >= _seen.size())
                    return true;
                boolean s = _seen.get(number);
                _seen.set(number);
                return s;
            }
        }
    }

    // This class must remain {@code static} to avoid the hidden
    // reference to the outer class, that would make it non-serializable.
    private static class Digest extends Credential
    {
        @Serial
        private static final long serialVersionUID = -2484639019549527724L;

        private final String method;
        private String algorithm;
        private String username;
        private String usernameStar;
        private String resolvedUserName;
        private String realm;
        private String nonce;
        private String nc;
        private String cnonce;
        private String qop;
        private String uri;
        private String response;
        private boolean userhash;
        private String opaque;

        private Digest(String method, String algorithm)
        {
            this.method = method;
            this.algorithm = algorithm;
        }

        private String getAlgorithm()
        {
            return algorithm;
        }

        @Override
        public boolean check(Object credentials)
        {
            if (credentials instanceof char[])
                credentials = new String((char[])credentials);
            String password = (credentials instanceof String) ? (String)credentials : credentials.toString();

            try
            {
                MessageDigest md = MessageDigest.getInstance(getAlgorithm());
                byte[] ha1;
                if (credentials instanceof MD5 md5)
                {
                    // Credentials are already a MD5 digest - assume it's in
                    // form user:realm:password (we have no way to know since
                    // it's a digest, alright?)
                    ha1 = md5.getDigest();
                }
                else
                {
                    // Calculate H(A1).
                    String a1 = resolvedUserName + ":" + realm + ":" + password;
                    ha1 = md.digest(a1.getBytes(UTF_8));
                }

                // Calculate H(A2).
                String a2 = method + ":" + uri;
                byte[] ha2 = md.digest(a2.getBytes(UTF_8));

                // Calculate response, must match what the client sent.
                String expected = TypeUtil.toString(ha1, 16) + ":" +
                    nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" +
                    TypeUtil.toString(ha2, 16);
                expected = TypeUtil.toString(md.digest(expected.getBytes(UTF_8)), 16);

                // Check digest.
                return stringEquals(expected, response == null ? null : response.toLowerCase(Locale.ROOT));
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Unable to process digest", x);
                return false;
            }
        }

        @Override
        public String toString()
        {
            return "%s@%x[u=%s]".formatted(TypeUtil.toShortName(getClass()), hashCode(), resolvedUserName);
        }
    }
}
