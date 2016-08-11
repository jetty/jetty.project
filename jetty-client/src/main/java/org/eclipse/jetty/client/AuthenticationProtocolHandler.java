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

package org.eclipse.jetty.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public abstract class AuthenticationProtocolHandler implements ProtocolHandler
{
    public static final int DEFAULT_MAX_CONTENT_LENGTH = 16*1024;
    public static final Logger LOG = Log.getLogger(AuthenticationProtocolHandler.class);
    private static final Pattern AUTHENTICATE_PATTERN = Pattern.compile("([^\\s]+)\\s+realm=\"([^\"]*)\"(.*)", Pattern.CASE_INSENSITIVE);

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

    protected abstract String getAuthenticationAttribute();

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
                if (LOG.isDebugEnabled())
                    LOG.debug("Authentication challenge failed {}", result.getFailure());
                forwardFailureComplete(request, result.getRequestFailure(), response, result.getResponseFailure());
                return;
            }

            String authenticationAttribute = getAuthenticationAttribute();
            HttpConversation conversation = request.getConversation();
            if (conversation.getAttribute(authenticationAttribute) != null)
            {
                // We have already tried to authenticate, but we failed again
                if (LOG.isDebugEnabled())
                    LOG.debug("Bad credentials for {}", request);
                forwardSuccessComplete(request, response);
                return;
            }

            HttpHeader header = getAuthenticateHeader();
            List<Authentication.HeaderInfo> headerInfos = parseAuthenticateHeader(response, header);
            if (headerInfos.isEmpty())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Authentication challenge without {} header", header);
                forwardFailureComplete(request, null, response, new HttpResponseException("HTTP protocol violation: Authentication challenge without " + header + " header", response));
                return;
            }

            Authentication authentication = null;
            Authentication.HeaderInfo headerInfo = null;
            URI authURI = resolveURI(request, getAuthenticationURI(request));
            if (authURI != null)
            {
                for (Authentication.HeaderInfo element : headerInfos)
                {
                    authentication = client.getAuthenticationStore().findAuthentication(element.getType(), authURI, element.getRealm());
                    if (authentication != null)
                    {
                        headerInfo = element;
                        break;
                    }
                }
            }
            if (authentication == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("No authentication available for {}", request);
                forwardSuccessComplete(request, response);
                return;
            }

            try
            {
                final Authentication.Result authnResult = authentication.authenticate(request, response, headerInfo, conversation);
                if (LOG.isDebugEnabled())
                    LOG.debug("Authentication result {}", authnResult);
                if (authnResult == null)
                {
                    forwardSuccessComplete(request, response);
                    return;
                }

                conversation.setAttribute(authenticationAttribute, true);

                URI requestURI = request.getURI();
                String path = null;
                if (requestURI == null)
                {
                    requestURI = resolveURI(request, null);
                    path = request.getPath();
                }
                Request newRequest = client.copyRequest(request, requestURI);
                if (path != null)
                    newRequest.path(path);

                authnResult.apply(newRequest);
                // Copy existing, explicitly set, authorization headers.
                copyIfAbsent(request, newRequest, HttpHeader.AUTHORIZATION);
                copyIfAbsent(request, newRequest, HttpHeader.PROXY_AUTHORIZATION);

                newRequest.onResponseSuccess(r -> client.getAuthenticationStore().addAuthenticationResult(authnResult));

                Connection connection = (Connection)request.getAttributes().get(Connection.class.getName());
                if (connection != null)
                    connection.send(newRequest, null);
                else
                    newRequest.send(null);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Authentication failed", x);
                forwardFailureComplete(request, null, response, x);
            }
        }

        private URI resolveURI(HttpRequest request, URI uri)
        {
            if (uri != null)
                return uri;
            String target = request.getScheme() + "://" + request.getHost();
            int port = request.getPort();
            if (port > 0)
                target += ":" + port;
            return URI.create(target);
        }

        private void copyIfAbsent(HttpRequest oldRequest, Request newRequest, HttpHeader header)
        {
            HttpField field = oldRequest.getHeaders().getField(header);
            if (field != null && !newRequest.getHeaders().contains(header))
                newRequest.getHeaders().put(field);
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
            List<Response.ResponseListener> responseListeners = conversation.getResponseListeners();
            if (responseFailure == null)
                notifier.forwardSuccess(responseListeners, response);
            else
                notifier.forwardFailure(responseListeners, response, responseFailure);
            notifier.notifyComplete(responseListeners, new Result(request, requestFailure, response, responseFailure));
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
