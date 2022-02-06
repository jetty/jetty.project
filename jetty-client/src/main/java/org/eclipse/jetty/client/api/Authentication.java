//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client.api;

import java.net.URI;
import java.util.Map;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.StringUtil;

/**
 * {@link Authentication} represents a mechanism to authenticate requests for protected resources.
 * <p>
 * {@link Authentication}s are added to an {@link AuthenticationStore}, which is then
 * {@link #matches(String, URI, String) queried} to find the right
 * {@link Authentication} mechanism to use based on its type, URI and realm, as returned by
 * {@code WWW-Authenticate} response headers.
 * <p>
 * If an {@link Authentication} mechanism is found, it is then
 * {@link #authenticate(Request, ContentResponse, HeaderInfo, Attributes) executed} for the given request,
 * returning an {@link Authentication.Result}, which is then stored in the {@link AuthenticationStore}
 * so that subsequent requests can be preemptively authenticated.
 */
public interface Authentication
{
    /**
     * Constant used to indicate that any realm will match.
     *
     * @see #matches(String, URI, String)
     */
    public static final String ANY_REALM = "<<ANY_REALM>>";

    /**
     * Matches {@link Authentication}s based on the given parameters
     *
     * @param type the {@link Authentication} type such as "Basic" or "Digest"
     * @param uri the request URI
     * @param realm the authentication realm as provided in the {@code WWW-Authenticate} response header
     * @return true if this authentication matches, false otherwise
     */
    boolean matches(String type, URI uri, String realm);

    /**
     * Executes the authentication mechanism for the given request, returning a {@link Result} that can be
     * used to actually authenticate the request via {@link org.eclipse.jetty.client.api.Authentication.Result#apply(Request)}.
     * <p>
     * If a request for {@code "/secure"} returns a {@link Result}, then the result may be used for other
     * requests such as {@code "/secure/foo"} or {@code "/secure/bar"}, unless those resources are protected
     * by other realms.
     *
     * @param request the request to execute the authentication mechanism for
     * @param response the 401 response obtained in the previous attempt to request the protected resource
     * @param headerInfo the {@code WWW-Authenticate} (or {@code Proxy-Authenticate}) header chosen for this
     * authentication (among the many that the response may contain)
     * @param context the conversation context in case the authentication needs multiple exchanges
     * to be completed and information needs to be stored across exchanges
     * @return the authentication result, or null if the authentication could not be performed
     */
    Result authenticate(Request request, ContentResponse response, HeaderInfo headerInfo, Attributes context);

    /**
     * Structure holding information about the {@code WWW-Authenticate} (or {@code Proxy-Authenticate}) header.
     */
    public static class HeaderInfo
    {
        private final HttpHeader header;
        private final String type;
        private final Map<String, String> params;

        public HeaderInfo(HttpHeader header, String type, Map<String, String> params) throws IllegalArgumentException
        {
            this.header = header;
            this.type = type;
            this.params = params;
        }

        /**
         * @return the authentication type (for example "Basic" or "Digest")
         */
        public String getType()
        {
            return type;
        }

        /**
         * @return the realm name or null if there is no realm parameter
         */
        public String getRealm()
        {
            return params.get("realm");
        }

        /**
         * @return the base64 content as a string if it exists otherwise null
         */
        public String getBase64()
        {
            return params.get("base64");
        }

        /**
         * @return additional authentication parameters
         */
        public Map<String, String> getParameters()
        {
            return params;
        }

        /**
         * @return specified authentication parameter or null if does not exist
         */
        public String getParameter(String paramName)
        {
            return params.get(StringUtil.asciiToLowerCase(paramName));
        }

        /**
         * @return the {@code Authorization} (or {@code Proxy-Authorization}) header
         */
        public HttpHeader getHeader()
        {
            return header;
        }
    }

    /**
     * {@link Result} holds the information needed to authenticate a {@link Request} via {@link org.eclipse.jetty.client.api.Authentication.Result#apply(org.eclipse.jetty.client.api.Request)}.
     */
    public static interface Result
    {
        /**
         * @return the URI of the request that has been used to generate this {@link Result}
         */
        URI getURI();

        /**
         * Applies the authentication result to the given request.
         * Typically, a {@code Authorization} header is added to the request, with the right information to
         * successfully authenticate at the server.
         *
         * @param request the request to authenticate
         */
        void apply(Request request);
    }
}
