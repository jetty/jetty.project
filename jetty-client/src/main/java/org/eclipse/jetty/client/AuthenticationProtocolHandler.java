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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpHeader;

public class AuthenticationProtocolHandler extends Response.Listener.Adapter implements ProtocolHandler
{
    private static final Pattern WWW_AUTHENTICATE_PATTERN = Pattern.compile("([^\\s]+)\\s+realm=\"([^\"]+)\"(\\s*,\\s*)?(.*)", Pattern.CASE_INSENSITIVE);

    private final ResponseNotifier notifier = new ResponseNotifier();
    private final HttpClient client;

    public AuthenticationProtocolHandler(HttpClient client)
    {
        this.client = client;
    }

    @Override
    public boolean accept(Request request, Response response)
    {
        return response.status() == 401;
    }

    @Override
    public Response.Listener getResponseListener()
    {
        return this;
    }

    @Override
    public void onComplete(Result result)
    {
        if (!result.isFailed())
        {
            List<WWWAuthenticate> wwwAuthenticates = parseWWWAuthenticate(result.getResponse());
            if (wwwAuthenticates.isEmpty())
            {
                // TODO
            }
            else
            {
                Request request = result.getRequest();
                final String uri = request.uri();
                Authentication authentication = null;
                for (WWWAuthenticate wwwAuthenticate : wwwAuthenticates)
                {
                    authentication = client.getAuthenticationStore().findAuthentication(wwwAuthenticate.type, uri, wwwAuthenticate.realm);
                    if (authentication != null)
                        break;
                }
                if (authentication != null)
                {
                    final Authentication authn = authentication;
                    authn.authenticate(request);
                    request.send(new Adapter()
                    {
                        @Override
                        public void onComplete(Result result)
                        {
                            if (!result.isFailed())
                            {
                                Authentication.Result authnResult = new Authentication.Result(uri, authn);
                                client.getAuthenticationStore().addAuthenticationResult(authnResult);
                            }
                        }
                    });
                }
                else
                {
                    noAuthentication(request, result.getResponse());
                }
            }
        }
    }

    private List<WWWAuthenticate> parseWWWAuthenticate(Response response)
    {
        List<WWWAuthenticate> result = new ArrayList<>();
        List<String> values = Collections.list(response.headers().getValues(HttpHeader.WWW_AUTHENTICATE.asString()));
        for (String value : values)
        {
            Matcher matcher = WWW_AUTHENTICATE_PATTERN.matcher(value);
            if (matcher.matches())
            {
                String type = matcher.group(1);
                String realm = matcher.group(2);
                String params = matcher.group(4);
                WWWAuthenticate wwwAuthenticate = new WWWAuthenticate(type, realm, params);
                result.add(wwwAuthenticate);
            }
        }
        return result;
    }

    private void noAuthentication(Request request, Response response)
    {
        HttpConversation conversation = client.getConversation(request);
        Response.Listener listener = conversation.exchanges().peekFirst().listener();
        notifier.notifyBegin(listener, response);
        notifier.notifyHeaders(listener, response);
        notifier.notifySuccess(listener, response);
        // TODO: this call here is horrid, but needed... but here it is too late for the exchange
        // TODO: to figure out that the conversation is finished, so we need to manually do it here, no matter what.
        // TODO: However, we also need to make sure that requests are not resent with the same ID
        // TODO: because here the connection has already been returned to the pool, so the "new" request may see
        // TODO: the same conversation but it's not really the case.
        // TODO: perhaps the factory for requests should be the conversation ?
        conversation.complete();
        notifier.notifyComplete(listener, new Result(request, response));
    }

    private class WWWAuthenticate
    {
        private final String type;
        private final String realm;
        private final String params;

        public WWWAuthenticate(String type, String realm, String params)
        {
            this.type = type;
            this.realm = realm;
            this.params = params;
        }
    }
}
