//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client.util;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;

/**
 * Implementation of the HTTP "Digest" authentication defined in RFC 2617.
 * <p>
 * Applications should create objects of this class and add them to the
 * {@link AuthenticationStore} retrieved from the {@link HttpClient}
 * via {@link HttpClient#getAuthenticationStore()}.
 */
public class DigestAuthentication extends AbstractAuthentication
{
    private static final Pattern PARAM_PATTERN = Pattern.compile("([^=]+)=(.*)");

    private final String user;
    private final String password;

    /**
     * @param uri the URI to match for the authentication
     * @param realm the realm to match for the authentication
     * @param user the user that wants to authenticate
     * @param password the password of the user
     */
    public DigestAuthentication(URI uri, String realm, String user, String password)
    {
        super(uri, realm);
        this.user = user;
        this.password = password;
    }

    @Override
    public String getType()
    {
        return "Digest";
    }

    @Override
    public Result authenticate(Request request, ContentResponse response, HeaderInfo headerInfo, Attributes context)
    {
        Map<String, String> params = parseParameters(headerInfo.getParameters());
        String nonce = params.get("nonce");
        if (nonce == null || nonce.length() == 0)
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
            List<String> serverQOPValues = StringUtil.csvSplit(null,serverQOP,0,serverQOP.length());
            if (serverQOPValues.contains("auth"))
                clientQOP = "auth";
            else if (serverQOPValues.contains("auth-int"))
                clientQOP = "auth-int";
        }

        String realm = getRealm();
        if (ANY_REALM.equals(realm))
            realm = headerInfo.getRealm();
        return new DigestResult(headerInfo.getHeader(), response.getContent(), realm, user, password, algorithm, nonce, clientQOP, opaque);
    }

    private Map<String, String> parseParameters(String wwwAuthenticate)
    {
        Map<String, String> result = new HashMap<>();
        List<String> parts = splitParams(wwwAuthenticate);
        for (String part : parts)
        {
            Matcher matcher = PARAM_PATTERN.matcher(part);
            if (matcher.matches())
            {
                String name = matcher.group(1).trim().toLowerCase(Locale.ENGLISH);
                String value = matcher.group(2).trim();
                if (value.startsWith("\"") && value.endsWith("\""))
                    value = value.substring(1, value.length() - 1);
                result.put(name, value);
            }
        }
        return result;
    }

    private List<String> splitParams(String paramString)
    {
        List<String> result = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < paramString.length(); ++i)
        {
            int quotes = 0;
            char ch = paramString.charAt(i);
            switch (ch)
            {
                case '\\':
                    ++i;
                    break;
                case '"':
                    ++quotes;
                    break;
                case ',':
                    if (quotes % 2 == 0)
                    {
                        String element = paramString.substring(start, i).trim();
                        if (element.length() > 0)
                            result.add(element);
                        start = i + 1;
                    }
                    break;
                default:
                    break;
            }
        }
        result.add(paramString.substring(start, paramString.length()).trim());
        return result;
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
        private final byte[] content;
        private final String realm;
        private final String user;
        private final String password;
        private final String algorithm;
        private final String nonce;
        private final String qop;
        private final String opaque;

        public DigestResult(HttpHeader header, byte[] content, String realm, String user, String password, String algorithm, String nonce, String qop, String opaque)
        {
            this.header = header;
            this.content = content;
            this.realm = realm;
            this.user = user;
            this.password = password;
            this.algorithm = algorithm;
            this.nonce = nonce;
            this.qop = qop;
            this.opaque = opaque;
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
                return;

            String A1 = user + ":" + realm + ":" + password;
            String hashA1 = toHexString(digester.digest(A1.getBytes(StandardCharsets.ISO_8859_1)));

            URI uri = request.getURI();
            String A2 = request.getMethod() + ":" + uri;
            if ("auth-int".equals(qop))
                A2 += ":" + toHexString(digester.digest(content));
            String hashA2 = toHexString(digester.digest(A2.getBytes(StandardCharsets.ISO_8859_1)));

            String nonceCount;
            String clientNonce;
            String A3;
            if (qop != null)
            {
                nonceCount = nextNonceCount();
                clientNonce = newClientNonce();
                A3 = hashA1 + ":" + nonce + ":" +  nonceCount + ":" + clientNonce + ":" + qop + ":" + hashA2;
            }
            else
            {
                nonceCount = null;
                clientNonce = null;
                A3 = hashA1 + ":" + nonce + ":" + hashA2;
            }
            String hashA3 = toHexString(digester.digest(A3.getBytes(StandardCharsets.ISO_8859_1)));

            StringBuilder value = new StringBuilder("Digest");
            value.append(" username=\"").append(user).append("\"");
            value.append(", realm=\"").append(realm).append("\"");
            value.append(", nonce=\"").append(nonce).append("\"");
            if (opaque != null)
                value.append(", opaque=\"").append(opaque).append("\"");
            value.append(", algorithm=\"").append(algorithm).append("\"");
            value.append(", uri=\"").append(uri).append("\"");
            if (qop != null)
            {
                value.append(", qop=\"").append(qop).append("\"");
                value.append(", nc=\"").append(nonceCount).append("\"");
                value.append(", cnonce=\"").append(clientNonce).append("\"");
            }
            value.append(", response=\"").append(hashA3).append("\"");

            request.header(header, value.toString());
        }

        private String nextNonceCount()
        {
            String padding = "00000000";
            String next = Integer.toHexString(nonceCount.incrementAndGet()).toLowerCase(Locale.ENGLISH);
            return padding.substring(0, padding.length() - next.length()) + next;
        }

        private String newClientNonce()
        {
            Random random = new Random();
            byte[] bytes = new byte[8];
            random.nextBytes(bytes);
            return toHexString(bytes);
        }

        private String toHexString(byte[] bytes)
        {
            return TypeUtil.toHexString(bytes).toLowerCase(Locale.ENGLISH);
        }
    }
}
