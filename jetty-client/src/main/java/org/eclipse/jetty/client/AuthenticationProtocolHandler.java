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

package org.eclipse.jetty.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class AuthenticationProtocolHandler implements ProtocolHandler
{
    public static final Logger LOG = Log.getLogger(AuthenticationProtocolHandler.class);
    private static final Pattern WWW_AUTHENTICATE_PATTERN = Pattern.compile("([^\\s]+)\\s+realm=\"([^\"]+)\".*", Pattern.CASE_INSENSITIVE);

    private final HttpClient client;
    private final int maxContentLength;
    private final ResponseNotifier notifier;

    public AuthenticationProtocolHandler(HttpClient client)
    {
        this(client, 4096);
    }

    public AuthenticationProtocolHandler(HttpClient client, int maxContentLength)
    {
        this.client = client;
        this.maxContentLength = maxContentLength;
        this.notifier = new ResponseNotifier(client);
    }

    @Override
    public boolean accept(Request request, Response response)
    {
        return response.getStatus() == 401;
    }

    @Override
    public Response.Listener getResponseListener()
    {
        // Return new instances every time to keep track of the response content
        return new AuthenticationListener();
    }

    private class AuthenticationListener extends BufferingResponseListener
    {
        private AuthenticationListener()
        {
            super(maxContentLength);
        }

        @Override
        public void onComplete(Result result)
        {
            Request request = result.getRequest();
            HttpConversation conversation = client.getConversation(request.getConversationID(), false);
            List<Response.ResponseListener> listeners = conversation.getExchanges().peekFirst().getResponseListeners();
            ContentResponse response = new HttpContentResponse(result.getResponse(), getContent(), getEncoding());
            if (result.isFailed())
            {
                Throwable failure = result.getFailure();
                LOG.debug("Authentication challenge failed {}", failure);
                notifier.forwardFailureComplete(listeners, request, result.getRequestFailure(), response, result.getResponseFailure());
                return;
            }

            List<WWWAuthenticate> wwwAuthenticates = parseWWWAuthenticate(response);
            if (wwwAuthenticates.isEmpty())
            {
                LOG.debug("Authentication challenge without WWW-Authenticate header");
                notifier.forwardFailureComplete(listeners, request, null, response, new HttpResponseException("HTTP protocol violation: 401 without WWW-Authenticate header", response));
                return;
            }

            final URI uri = request.getURI();
            Authentication authentication = null;
            WWWAuthenticate wwwAuthenticate = null;
            for (WWWAuthenticate wwwAuthn : wwwAuthenticates)
            {
                authentication = client.getAuthenticationStore().findAuthentication(wwwAuthn.type, uri, wwwAuthn.realm);
                if (authentication != null)
                {
                    wwwAuthenticate = wwwAuthn;
                    break;
                }
            }
            if (authentication == null)
            {
                LOG.debug("No authentication available for {}", request);
                notifier.forwardSuccessComplete(listeners, request, response);
                return;
            }

            final Authentication.Result authnResult = authentication.authenticate(request, response, wwwAuthenticate.value, conversation);
            LOG.debug("Authentication result {}", authnResult);
            if (authnResult == null)
            {
                notifier.forwardSuccessComplete(listeners, request, response);
                return;
            }

            Request newRequest = client.copyRequest(request, uri);
            authnResult.apply(newRequest);
            newRequest.onResponseSuccess(new Response.SuccessListener()
            {
                @Override
                public void onSuccess(Response response)
                {
                    client.getAuthenticationStore().addAuthenticationResult(authnResult);
                }
            }).send(null);
        }

        private List<WWWAuthenticate> parseWWWAuthenticate(Response response)
        {
            // TODO: these should be ordered by strength
            List<WWWAuthenticate> result = new ArrayList<>();
            List<String> values = Collections.list(response.getHeaders().getValues(HttpHeader.WWW_AUTHENTICATE.asString()));
            for (String value : values)
            {
                Matcher matcher = WWW_AUTHENTICATE_PATTERN.matcher(value);
                if (matcher.matches())
                {
                    String type = matcher.group(1);
                    String realm = matcher.group(2);
                    WWWAuthenticate wwwAuthenticate = new WWWAuthenticate(value, type, realm);
                    result.add(wwwAuthenticate);
                }
            }
            return result;
        }
    }

    private class WWWAuthenticate
    {
        private final String value;
        private final String type;
        private final String realm;

        public WWWAuthenticate(String value, String type, String realm)
        {
            this.value = value;
            this.type = type;
            this.realm = realm;
        }
    }
}
