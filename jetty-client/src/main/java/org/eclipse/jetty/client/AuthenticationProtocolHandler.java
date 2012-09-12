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

import java.nio.ByteBuffer;
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
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class AuthenticationProtocolHandler implements ProtocolHandler
{
    public static final Logger LOG = Log.getLogger(AuthenticationProtocolHandler.class);
    private static final Pattern WWW_AUTHENTICATE_PATTERN = Pattern.compile("([^\\s]+)\\s+realm=\"([^\"]+)\".*", Pattern.CASE_INSENSITIVE);

    private final ResponseNotifier notifier = new ResponseNotifier();
    private final HttpClient client;
    private final int maxContentLength;

    public AuthenticationProtocolHandler(HttpClient client)
    {
        this(client, 4096);
    }

    public AuthenticationProtocolHandler(HttpClient client, int maxContentLength)
    {
        this.client = client;
        this.maxContentLength = maxContentLength;
    }

    @Override
    public boolean accept(Request request, Response response)
    {
        return response.status() == 401;
    }

    @Override
    public Response.Listener getResponseListener()
    {
        return new AuthenticationListener();
    }

    private class AuthenticationListener extends Response.Listener.Adapter
    {
        private byte[] buffer = new byte[0];

        @Override
        public void onContent(Response response, ByteBuffer content)
        {
            if (buffer.length == maxContentLength)
                return;

            long newLength = buffer.length + content.remaining();
            if (newLength > maxContentLength)
                newLength = maxContentLength;

            byte[] newBuffer = new byte[(int)newLength];
            System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
            content.get(newBuffer, buffer.length, content.remaining());
            buffer = newBuffer;
        }

        @Override
        public void onComplete(Result result)
        {
            Request request = result.getRequest();
            ContentResponse response = new HttpContentResponse(result.getResponse(), buffer);
            if (result.isFailed())
            {
                Throwable failure = result.getFailure();
                LOG.debug("Authentication challenge failed {}", failure);
                forwardFailure(request, response, failure);
                return;
            }

            List<WWWAuthenticate> wwwAuthenticates = parseWWWAuthenticate(response);
            if (wwwAuthenticates.isEmpty())
            {
                LOG.debug("Authentication challenge without WWW-Authenticate header");
                forwardFailure(request, response, new HttpResponseException("HTTP protocol violation: 401 without WWW-Authenticate header", response));
                return;
            }

            final String uri = request.uri();
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
                forwardSuccess(request, response);
                return;
            }

            HttpConversation conversation = client.getConversation(request);
            final Authentication.Result authnResult = authentication.authenticate(request, response, wwwAuthenticate.value, conversation);
            LOG.debug("Authentication result {}", authnResult);
            if (authnResult == null)
            {
                forwardSuccess(request, response);
                return;
            }

            authnResult.apply(request);
            request.send(new Adapter()
            {
                @Override
                public void onSuccess(Response response)
                {
                    client.getAuthenticationStore().addAuthenticationResult(authnResult);
                }
            });
        }

        private void forwardFailure(Request request, Response response, Throwable failure)
        {
            HttpConversation conversation = client.getConversation(request);
            Response.Listener listener = conversation.exchanges().peekFirst().listener();
            notifier.notifyBegin(listener, response);
            notifier.notifyHeaders(listener, response);
            if (response instanceof ContentResponse)
                notifier.notifyContent(listener, response, ByteBuffer.wrap(((ContentResponse)response).content()));
            notifier.notifyFailure(listener, response, failure);
            conversation.complete();
            notifier.notifyComplete(listener, new Result(request, response, failure));
        }

        private void forwardSuccess(Request request, Response response)
        {
            HttpConversation conversation = client.getConversation(request);
            Response.Listener listener = conversation.exchanges().peekFirst().listener();
            notifier.notifyBegin(listener, response);
            notifier.notifyHeaders(listener, response);
            if (response instanceof ContentResponse)
                notifier.notifyContent(listener, response, ByteBuffer.wrap(((ContentResponse)response).content()));
            notifier.notifySuccess(listener, response);
            conversation.complete();
            notifier.notifyComplete(listener, new Result(request, response));
        }

        private List<WWWAuthenticate> parseWWWAuthenticate(Response response)
        {
            // TODO: these should be ordered by strength
            List<WWWAuthenticate> result = new ArrayList<>();
            List<String> values = Collections.list(response.headers().getValues(HttpHeader.WWW_AUTHENTICATE.asString()));
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
