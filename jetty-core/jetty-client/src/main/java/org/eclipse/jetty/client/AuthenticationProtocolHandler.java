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

package org.eclipse.jetty.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.Authentication.HeaderInfo;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.util.NanoTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AuthenticationProtocolHandler implements ProtocolHandler
{
    public static final int DEFAULT_MAX_CONTENT_LENGTH = 16 * 1024;
    public static final Logger LOG = LoggerFactory.getLogger(AuthenticationProtocolHandler.class);
    private final HttpClient client;
    private final int maxContentLength;
    private final ResponseNotifier notifier;

    private static final Pattern CHALLENGE_PATTERN = Pattern.compile("(?<schemeOnly>[!#$%&'*+\\-.^_`|~0-9A-Za-z]+)|(?:(?<scheme>[!#$%&'*+\\-.^_`|~0-9A-Za-z]+)\\s+)?(?:(?<token68>[a-zA-Z0-9\\-._~+/]+=*)|(?<paramName>[!#$%&'*+\\-.^_`|~0-9A-Za-z]+)\\s*=\\s*(?:(?<paramValue>.*)))");

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

    protected List<HeaderInfo> getHeaderInfo(String header) throws IllegalArgumentException
    {
        List<HeaderInfo> headerInfos = new ArrayList<>();
        Matcher m;

        for (String value : new QuotedCSV(true, header))
        {
            m = CHALLENGE_PATTERN.matcher(value);
            if (m.matches())
            {
                if (m.group("schemeOnly") != null)
                {
                    headerInfos.add(new HeaderInfo(getAuthorizationHeader(), m.group(1), new HashMap<>()));
                    continue;
                }

                if (m.group("scheme") != null)
                {
                    headerInfos.add(new HeaderInfo(getAuthorizationHeader(), m.group("scheme"), new HashMap<>()));
                }

                if (headerInfos.isEmpty())
                    throw new IllegalArgumentException("Parameters without auth-scheme");

                Map<String, String> authParams = headerInfos.get(headerInfos.size() - 1).getParameters();
                if (m.group("paramName") != null)
                {
                    String paramVal = QuotedCSV.unquote(m.group("paramValue"));
                    authParams.put(m.group("paramName"), paramVal);
                }
                else if (m.group("token68") != null)
                {
                    if (!authParams.isEmpty())
                        throw new IllegalArgumentException("token68 after auth-params");

                    authParams.put("base64", m.group("token68"));
                }
            }
        }

        return headerInfos;
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
            if (result.getResponseFailure() != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Authentication challenge failed", result.getFailure());
                forwardFailureComplete(request, result.getRequestFailure(), response, result.getResponseFailure());
                return;
            }

            String authenticationAttribute = getAuthenticationAttribute();
            HttpConversation conversation = request.getConversation();
            if (conversation.getAttribute(authenticationAttribute) != null)
            {
                // We have already tried to authenticate, but we failed again.
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
                forwardFailureComplete(request, result.getRequestFailure(), response, new HttpResponseException("HTTP protocol violation: Authentication challenge without " + header + " header", response));
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

            Request.Content content = request.getBody();
            if (content != null && !content.rewind())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Request content not reproducible for {}", request);
                forwardSuccessComplete(request, response);
                return;
            }

            try
            {
                Authentication.Result authnResult = authentication.authenticate(request, response, headerInfo, conversation);
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

                // Adjust the timeout of the new request, taking into account the
                // timeout of the previous request and the time already elapsed.
                long timeoutNanoTime = request.getTimeoutNanoTime();
                if (timeoutNanoTime < Long.MAX_VALUE)
                {
                    long newTimeout = NanoTime.until(timeoutNanoTime);
                    if (newTimeout > 0)
                    {
                        newRequest.timeout(newTimeout, TimeUnit.NANOSECONDS);
                    }
                    else
                    {
                        TimeoutException failure = new TimeoutException("Total timeout " + request.getConversation().getTimeout() + " ms elapsed");
                        forwardFailureComplete(request, failure, response, failure);
                        return;
                    }
                }

                if (path != null)
                    newRequest.path(path);

                authnResult.apply(newRequest);
                // Copy existing, explicitly set, authorization headers.
                copyIfAbsent(request, newRequest, HttpHeader.AUTHORIZATION);
                copyIfAbsent(request, newRequest, HttpHeader.PROXY_AUTHORIZATION);

                AfterAuthenticationListener listener = new AfterAuthenticationListener(authnResult);
                Connection connection = (Connection)request.getAttributes().get(Connection.class.getName());
                if (connection != null)
                    connection.send(newRequest, listener);
                else
                    newRequest.send(listener);
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
                newRequest.headers(headers -> headers.put(field));
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
            List<String> values = response.getHeaders().getValuesList(header);
            for (String value : values)
            {
                try
                {
                    result.addAll(getHeaderInfo(value));
                }
                catch (IllegalArgumentException e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Failed to parse authentication header", e);
                }
            }
            return result;
        }
    }

    private class AfterAuthenticationListener extends Response.Listener.Adapter
    {
        private final Authentication.Result authenticationResult;

        private AfterAuthenticationListener(Authentication.Result authenticationResult)
        {
            this.authenticationResult = authenticationResult;
        }

        @Override
        public void onSuccess(Response response)
        {
            int status = response.getStatus();
            if (HttpStatus.isSuccess(status) || HttpStatus.isRedirection(status))
                client.getAuthenticationStore().addAuthenticationResult(authenticationResult);
        }
    }
}
