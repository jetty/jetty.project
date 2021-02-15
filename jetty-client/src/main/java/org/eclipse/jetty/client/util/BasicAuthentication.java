//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.Attributes;

/**
 * Implementation of the HTTP "Basic" authentication defined in RFC 2617.
 * <p>
 * Applications should create objects of this class and add them to the
 * {@link AuthenticationStore} retrieved from the {@link HttpClient}
 * via {@link HttpClient#getAuthenticationStore()}.
 */
public class BasicAuthentication extends AbstractAuthentication
{
    private final String user;
    private final String password;

    /**
     * @param uri the URI to match for the authentication
     * @param realm the realm to match for the authentication
     * @param user the user that wants to authenticate
     * @param password the password of the user
     */
    public BasicAuthentication(URI uri, String realm, String user, String password)
    {
        super(uri, realm);
        this.user = user;
        this.password = password;
    }

    @Override
    public String getType()
    {
        return "Basic";
    }

    @Override
    public Result authenticate(Request request, ContentResponse response, HeaderInfo headerInfo, Attributes context)
    {
        String charsetParam = headerInfo.getParameter("charset");
        Charset charset = charsetParam == null ? null : Charset.forName(charsetParam);
        return new BasicResult(getURI(), headerInfo.getHeader(), user, password, charset);
    }

    /**
     * Basic authentication result.
     * <p>
     * Application may utilize this class directly via
     * {@link org.eclipse.jetty.client.api.AuthenticationStore#addAuthenticationResult(Result)}
     * to perform preemptive authentication, that is immediately
     * sending the authorization header based on the fact that the
     * URI is known to require authentication and that username
     * and password are known a priori.
     */
    public static class BasicResult implements Result
    {
        private final URI uri;
        private final HttpHeader header;
        private final String value;

        public BasicResult(URI uri, String user, String password)
        {
            this(uri, HttpHeader.AUTHORIZATION, user, password);
        }

        public BasicResult(URI uri, HttpHeader header, String user, String password)
        {
            this(uri, header, user, password, StandardCharsets.ISO_8859_1);
        }

        public BasicResult(URI uri, HttpHeader header, String user, String password, Charset charset)
        {
            this.uri = uri;
            this.header = header;
            if (charset == null)
                charset = StandardCharsets.ISO_8859_1;
            byte[] authBytes = (user + ":" + password).getBytes(charset);
            this.value = "Basic " + Base64.getEncoder().encodeToString(authBytes);
        }

        @Override
        public URI getURI()
        {
            return uri;
        }

        @Override
        public void apply(Request request)
        {
            if (!request.getHeaders().contains(header, value))
                request.header(header, value);
        }

        @Override
        public String toString()
        {
            return String.format("Basic authentication result for %s", getURI());
        }
    }
}
