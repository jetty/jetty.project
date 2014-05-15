//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

public abstract class AuthenticationProtocolHandler implements ProtocolHandler
{
    public static final int DEFAULT_MAX_CONTENT_LENGTH = 4096;
    public static final Logger LOG = Log.getLogger(AuthenticationProtocolHandler.class);
    private static final Pattern AUTHENTICATE_PATTERN = Pattern.compile("([^\\s]+)\\s+realm=\"([^\"]+)\"(.*)", Pattern.CASE_INSENSITIVE);
    private static final String AUTHENTICATION_ATTRIBUTE = AuthenticationProtocolHandler.class.getName() + ".authentication";

    private final HttpClient client;
    private final int maxContentLength;
    private final ResponseNotifier notifier;

    protected AuthenticationProtocolHandler(HttpClient client, int maxContentLength)
    {
        this.client = client;
        this.maxContentLength = maxContentLength;
        this.notifier = new ResponseNotifier();
    }

    protected HttpClient getHttpClient()
    {
        return client;
    }

    protected abstract HttpHeader getAuthenticateHeader();

    protected abstract HttpHeader getAuthorizationHeader();

    protected abstract URI getAuthenticationURI(Request request);

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
            HttpRequest request = (HttpRequest)result.getRequest();
            ContentResponse response = new HttpContentResponse(result.getResponse(), getContent(), getMediaType(), getEncoding());
            if (result.isFailed())
            {
                Throwable failure = result.getFailure();
                LOG.debug("Authentication challenge failed {}", failure);
                forwardFailureComplete(request, result.getRequestFailure(), response, result.getResponseFailure());
                return;
            }

            HttpConversation conversation = request.getConversation();
            if (conversation.getAttribute(AUTHENTICATION_ATTRIBUTE) != null)
            {
                // We have already tried to authenticate, but we failed again
                LOG.debug("Bad credentials for {}", request);
                forwardSuccessComplete(request, response);
                return;
            }

            HttpHeader header = getAuthenticateHeader();
            List<Authentication.HeaderInfo> headerInfos = parseAuthenticateHeader(response, header);
            if (headerInfos.isEmpty())
            {
                LOG.debug("Authentication challenge without {} header", header);
                forwardFailureComplete(request, null, response, new HttpResponseException("HTTP protocol violation: Authentication challenge without " + header + " header", response));
                return;
            }

            Authentication authentication = null;
            Authentication.HeaderInfo headerInfo = null;
            URI uri = getAuthenticationURI(request);
            if (uri != null)
            {
                for (Authentication.HeaderInfo element : headerInfos)
                {
                    authentication = client.getAuthenticationStore().findAuthentication(element.getType(), uri, element.getRealm());
                    if (authentication != null)
                    {
                        headerInfo = element;
                        break;
                    }
                }
            }
            if (authentication == null)
            {
                LOG.debug("No authentication available for {}", request);
                forwardSuccessComplete(request, response);
                return;
            }

            final Authentication.Result authnResult = authentication.authenticate(request, response, headerInfo, conversation);
            LOG.debug("Authentication result {}", authnResult);
            if (authnResult == null)
            {
                forwardSuccessComplete(request, response);
                return;
            }

            conversation.setAttribute(AUTHENTICATION_ATTRIBUTE, true);

            Request newRequest = client.copyRequest(request, request.getURI());
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

        private void forwardSuccessComplete(HttpRequest request, Response response)
        {
            HttpConversation conversation = request.getConversation();
            conversation.updateResponseListeners(null);
            notifier.forwardSuccessComplete(conversation.getResponseListeners(), request, response);
        }

        private void forwardFailureComplete(HttpRequest request, Throwable requestFailure, Response response, Throwable responseFailure)
        {
            HttpConversation conversation = request.getConversation();
            conversation.updateResponseListeners(null);
            notifier.forwardFailureComplete(conversation.getResponseListeners(), request, requestFailure, response, responseFailure);
        }

        private List<Authentication.HeaderInfo> parseAuthenticateHeader(Response response, HttpHeader header)
        {
            // TODO: these should be ordered by strength
            List<Authentication.HeaderInfo> result = new ArrayList<>();
            List<String> values = Collections.list(response.getHeaders().getValues(header.asString()));
            for (String value : values)
            {
                Matcher matcher = AUTHENTICATE_PATTERN.matcher(value);
                if (matcher.matches())
                {
                    String type = matcher.group(1);
                    String realm = matcher.group(2);
                    String params = matcher.group(3);
                    Authentication.HeaderInfo headerInfo = new Authentication.HeaderInfo(type, realm, params, getAuthorizationHeader());
                    result.add(headerInfo);
                }
            }
            return result;
        }
    }
}
