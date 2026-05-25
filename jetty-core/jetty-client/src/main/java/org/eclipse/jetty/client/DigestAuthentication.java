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

package org.eclipse.jetty.client;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;

/**
 * <p>Implementation of the HTTP "Digest" authentication defined in RFC 7616.</p>
 * <p>Applications should create objects of this class and add them to the
 * {@link AuthenticationStore} retrieved from the {@link HttpClient}
 * via {@link HttpClient#getAuthenticationStore()}.</p>
 */
public class DigestAuthentication extends AbstractAuthentication
{
    private final Random random;
    private final String user;
    private final String password;
    private boolean userHashing;

    /**
     * @param uri the URI to match for the authentication
     * @param realm the realm to match for the authentication
     * @param user the user that wants to authenticate
     * @param password the password of the user
     */
    public DigestAuthentication(URI uri, String realm, String user, String password)
    {
        this(uri, realm, user, password, new SecureRandom());
    }

    /**
     * @param uri the URI to match for the authentication
     * @param realm the realm to match for the authentication
     * @param user the user that wants to authenticate
     * @param password the password of the user
     * @param random the Random generator to use for nonces.
     */
    public DigestAuthentication(URI uri, String realm, String user, String password, Random random)
    {
        super(uri, realm);
        Objects.requireNonNull(random);
        this.random = random;
        this.user = user;
        this.password = password;
    }

    @Override
    public String getType()
    {
        return "Digest";
    }

    /**
     * @return whether hashing of the username is supported
     */
    public boolean isUserHashing()
    {
        return userHashing;
    }

    /**
     * @param userHashing whether hashing of the username is supported
     */
    public void setUserHashing(boolean userHashing)
    {
        this.userHashing = userHashing;
    }

    @Override
    public boolean matches(String type, URI uri, String realm)
    {
        // Digest authentication requires a realm.
        if (realm == null)
            return false;

        return super.matches(type, uri, realm);
    }

    @Override
    public Result authenticate(Request request, ContentResponse response, HeaderInfo headerInfo, Attributes context)
    {
        Map<String, String> params = headerInfo.getParameters();
        String nonce = params.get("nonce");
        if (nonce == null || nonce.isEmpty())
            return null;
        String opaque = params.get("opaque");
        String algorithm = params.get("algorithm");
        if (algorithm == null)
            algorithm = "MD5";
        MessageDigest digester = getMessageDigest(algorithm);
        if (digester == null)
            return null;
        String serverQOP = params.get("qop");
        String clientQOP = null;
        if (serverQOP != null)
        {
            List<String> serverQOPValues = StringUtil.csvSplit(null, serverQOP, 0, serverQOP.length());
            if (serverQOPValues.contains("auth"))
                clientQOP = "auth";
            else if (serverQOPValues.contains("auth-int"))
                clientQOP = "auth-int";
        }
        String charsetName = params.get("charset");
        Charset charset = StringUtil.isBlank(charsetName) ? null : Charset.forName(charsetName);
        if (!"UTF-8".equals(charsetName))
            charset = null;
        boolean userHash = Boolean.parseBoolean(params.get("userhash"));

        String realm = getRealm();
        if (ANY_REALM.equals(realm))
            realm = headerInfo.getRealm();

        return new DigestResult(headerInfo.getHeader(), realm, user, password, algorithm, nonce, clientQOP, opaque, charset, userHash);
    }

    private MessageDigest getMessageDigest(String algorithm)
    {
        try
        {
            return MessageDigest.getInstance(algorithm);
        }
        catch (NoSuchAlgorithmException x)
        {
            return null;
        }
    }

    private class DigestResult implements Result
    {
        private final AtomicInteger nonceCount = new AtomicInteger();
        private final HttpHeader header;
        private final String realm;
        private final String user;
        private final String password;
        private final String algorithm;
        private final String nonce;
        private final String qop;
        private final String opaque;
        private final Charset charset;
        private final boolean userHash;

        private DigestResult(HttpHeader header, String realm, String user, String password, String algorithm, String nonce, String qop, String opaque, Charset charset, boolean userHash)
        {
            this.header = header;
            this.realm = realm;
            this.user = user;
            this.password = password;
            this.algorithm = algorithm;
            this.nonce = nonce;
            this.qop = qop;
            this.opaque = opaque;
            this.charset = charset;
            this.userHash = userHash;
        }

        @Override
        public URI getURI()
        {
            return DigestAuthentication.this.getURI();
        }

        @Override
        public void apply(Request request)
        {
            MessageDigest digester = getMessageDigest(algorithm);
            if (digester == null)
                throw new IllegalArgumentException("Unsupported digest algorithm: " + algorithm);
            if (!"auth".equals(qop))
                throw new IllegalArgumentException("Unsupported quality of protection: " + qop);

            // Retain ISO-8859-1 for old servers that don't send the charset parameter.
            Charset cs = Objects.requireNonNullElse(charset, StandardCharsets.ISO_8859_1);

            String a1 = user + ":" + realm + ":" + password;
            String hashA1 = toHexString(digester.digest(strictEncode(cs, a1)));

            String query = request.getQuery();
            String path = request.getPath();
            String uri = (query == null) ? path : path + "?" + query;
            String a2 = request.getMethod() + ":" + uri;
            String hashA2 = toHexString(digester.digest(strictEncode(cs, a2)));

            String nonceCount;
            String clientNonce;
            nonceCount = nextNonceCount();
            clientNonce = newClientNonce();
            String a3 = hashA1 + ":" + nonce + ":" + nonceCount + ":" + clientNonce + ":" + qop + ":" + hashA2;
            String hashA3 = toHexString(digester.digest(strictEncode(cs, a3)));

            // RFC-7616[3.4]: only username, realm, nonce, uri, response, cnonce, and opaque must be quoted.
            // Parameters algorithm, username*, qop, and nc must not be quoted.
            String digest = "Digest";
            if (isUserHashing() && userHash)
            {
                digest += " username=\"%s\"".formatted(toHexString(digester.digest(strictEncode(cs, user + ":" + realm))));
                digest += ", userhash=true";
            }
            else if (userNameNeedsEncoding(user))
            {
                if (charset != null)
                    digest += " username*=%s".formatted(encodeUserName(user, charset));
                else
                    throw new IllegalArgumentException("Unsupported username: " + user);
            }
            else
            {
                digest += " username=\"%s\"".formatted(user);
            }
            digest += ", realm=\"%s\"".formatted(realm);
            digest += ", nonce=\"%s\"".formatted(nonce);
            if (opaque != null)
                digest += ", opaque=\"%s\"".formatted(opaque);
            digest += ", algorithm=%s".formatted(algorithm);
            digest += ", uri=\"%s\"".formatted(uri);
            digest += ", qop=%s".formatted(qop);
            digest += ", nc=%s".formatted(nonceCount);
            digest += ", cnonce=\"%s\"".formatted(clientNonce);
            digest += ", response=\"%s\"".formatted(hashA3);
            String value = digest;

            request.headers(headers -> headers.add(header, value));
        }

        private static byte[] strictEncode(Charset charset, String value)
        {
            try
            {
                ByteBuffer byteBuffer = charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
                byte[] bytes = new byte[byteBuffer.remaining()];
                byteBuffer.get(bytes);
                return bytes;
            }
            catch (CharacterCodingException x)
            {
                throw new RuntimeException(x);
            }
        }

        private static boolean userNameNeedsEncoding(String user)
        {
            // Should be RFC 9110 quoted-string,
            // but use here a simplified version.
            for (int i = 0; i < user.length(); ++i)
            {
                char c = user.charAt(i);
                if (c < 0x20 || c > 0x7E || c == '"' || c == '\\')
                    return true;
            }
            return false;
        }

        private static String encodeUserName(String user, Charset charset)
        {
            byte[] bytes = strictEncode(charset, user);
            StringBuilder builder = new StringBuilder(charset.name()).append("''");
            for (byte b : bytes)
            {
                int c = b & 0xFF;
                boolean unreserved = (c >= 'A' && c <= 'Z') ||
                        (c >= 'a' && c <= 'z') ||
                        (c >= '0' && c <= '9') ||
                        c == '-' || c == '.' || c == '_' || c == '~';
                if (unreserved)
                    builder.append((char)c);
                else
                    builder.append(String.format("%%%02X", c));
            }
            return builder.toString();
        }

        private String nextNonceCount()
        {
            String padding = "00000000";
            String next = Integer.toHexString(nonceCount.incrementAndGet()).toLowerCase(Locale.ROOT);
            return padding.substring(0, padding.length() - next.length()) + next;
        }

        private String newClientNonce()
        {
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            return toHexString(bytes);
        }

        private String toHexString(byte[] bytes)
        {
            return TypeUtil.toString(bytes, 16);
        }
    }
}
