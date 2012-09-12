//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.TypeUtil;

public class DigestAuthentication implements Authentication
{
    private static final Pattern PARAM_PATTERN = Pattern.compile("([^=]+)=(.*)");

    private final String uri;
    private final String realm;
    private final String user;
    private final String password;

    public DigestAuthentication(String uri, String realm, String user, String password)
    {
        this.uri = uri;
        this.realm = realm;
        this.user = user;
        this.password = password;
    }

    @Override
    public boolean matches(String type, String uri, String realm)
    {
        if (!"digest".equalsIgnoreCase(type))
            return false;

        if (!uri.startsWith(this.uri))
            return false;

        return this.realm.equals(realm);
    }

    @Override
    public boolean authenticate(Request request, String paramString, Attributes context)
    {
        Map<String, String> params = parseParams(paramString);
        String nonce = params.get("nonce");
        if (nonce == null || nonce.length() == 0)
            return false;
        String opaque = params.get("opaque");
        String algorithm = params.get("algorithm");
        if (algorithm == null)
            algorithm = "MD5";
        MessageDigest digester = getMessageDigest(algorithm);
        if (digester == null)
            return false;
        String serverQOP = params.get("qop");
        String clientQOP = null;
        if (serverQOP != null)
        {
            List<String> serverQOPValues = Arrays.asList(serverQOP.split(","));
            if (serverQOPValues.contains("auth"))
                clientQOP = "auth";
            else if (serverQOPValues.contains("auth-int"))
                clientQOP = "auth-int";
        }

        String hash = compute(digester, clientQOP, content, nonce);

        StringBuilder value = new StringBuilder("Digest");
        value.append(" username=\"").append(user).append("\"");
        value.append(", realm=\"").append(realm).append("\"");
        value.append(", nonce=\"").append(nonce).append("\"");
        if (opaque != null)
            value.append(", opaque=\"").append(opaque).append("\"");
        value.append(", algorithm=\"").append(algorithm).append("\"");
        value.append(", uri=\"").append(request.uri()).append("\"");
        if (clientQOP != null)
            value.append(", qop=\"").append(clientQOP).append("\"");
        value.append(", response=\"").append(hash).append("\"");

        request.header(HttpHeader.AUTHORIZATION.asString(), value.toString());
    }

    private Map<String, String> parseParams(String paramString)
    {
        Map<String, String> result = new HashMap<>();
        List<String> parts = splitParams(paramString);
        for (String part : parts)
        {
            Matcher matcher = PARAM_PATTERN.matcher(part);
            if (matcher.matches())
                result.put(matcher.group(1).trim().toLowerCase(), matcher.group(2).trim());
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
                case '"':
                    ++quotes;
                    break;
                case ',':
                    if (quotes % 2 == 0)
                    {
                        result.add(paramString.substring(start, i).trim());
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

    private String compute(Request request, MessageDigest digester, String qop, byte[] content, String serverNonce)
    {
        Charset charset = Charset.forName("ISO-8859-1");
        String A1 = user + ":" + realm + ":" + password;
        String hashA1 = TypeUtil.toHexString(digester.digest(A1.getBytes(charset)));

        String A2 = request.method().asString() + ":" + request.uri();
        if ("auth-int".equals(qop))
            A2 += ":" + TypeUtil.toHexString(digester.digest(content));
        String hashA2 = TypeUtil.toHexString(digester.digest(A2.getBytes(charset)));

        String A3;
        if (qop != null)
            A3 = hashA1 + ":" + serverNonce + ":" + nonceCount + ":" + clientNonce + ":" + qop + ":" + hashA2;
        else
            A3 = hashA1 + ":" + serverNonce + ":" + hashA2;

        return TypeUtil.toHexString(digester.digest(A3.getBytes(charset)));
    }
}
