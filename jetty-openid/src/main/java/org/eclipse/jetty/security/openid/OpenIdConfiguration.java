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

package org.eclipse.jetty.security.openid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * Holds the configuration for an OpenID Connect service.
 *
 * This uses the OpenID Provider URL with the path {@link #CONFIG_PATH} to discover
 * the required information about the OIDC service.
 */
public class OpenIdConfiguration extends ContainerLifeCycle
{
    private static final Logger LOG = Log.getLogger(OpenIdConfiguration.class);
    private static final String CONFIG_PATH = "/.well-known/openid-configuration";

    private final HttpClient httpClient;
    private final String issuer;
    private final String clientId;
    private final String clientSecret;
    private final List<String> scopes = new ArrayList<>();
    private String authEndpoint;
    private String tokenEndpoint;

    /**
     * Create an OpenID configuration for a specific OIDC provider.
     * @param provider The URL of the OpenID provider.
     * @param clientId OAuth 2.0 Client Identifier valid at the Authorization Server.
     * @param clientSecret The client secret known only by the Client and the Authorization Server.
     */
    public OpenIdConfiguration(String provider, String clientId, String clientSecret)
    {
        this(provider, null, null, clientId, clientSecret, null);
    }

    /**
     * Create an OpenID configuration for a specific OIDC provider.
     * @param issuer The URL of the OpenID provider.
     * @param authorizationEndpoint the URL of the OpenID provider's authorization endpoint if configured.
     * @param tokenEndpoint the URL of the OpenID provider's token endpoint if configured.
     * @param clientId OAuth 2.0 Client Identifier valid at the Authorization Server.
     * @param clientSecret The client secret known only by the Client and the Authorization Server.
     * @param httpClient The {@link HttpClient} instance to use.
     */
    public OpenIdConfiguration(String issuer, String authorizationEndpoint, String tokenEndpoint,
                               String clientId, String clientSecret, HttpClient httpClient)
    {
        this.issuer = issuer;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.authEndpoint = authorizationEndpoint;
        this.tokenEndpoint = tokenEndpoint;
        this.httpClient = httpClient != null ? httpClient : newHttpClient();

        if (this.issuer == null)
            throw new IllegalArgumentException("Issuer was not configured");

        addBean(this.httpClient);
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();

        if (authEndpoint == null || tokenEndpoint == null)
        {
            Map<String, Object> discoveryDocument = fetchOpenIdConnectMetadata(issuer, httpClient);

            authEndpoint = (String)discoveryDocument.get("authorization_endpoint");
            if (authEndpoint == null)
                throw new IllegalArgumentException("authorization_endpoint");

            tokenEndpoint = (String)discoveryDocument.get("token_endpoint");
            if (tokenEndpoint == null)
                throw new IllegalArgumentException("token_endpoint");

            if (!Objects.equals(discoveryDocument.get("issuer"), issuer))
                LOG.warn("The issuer in the metadata is not correct.");
        }
    }

    private static HttpClient newHttpClient()
    {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(false);
        return new HttpClient(sslContextFactory);
    }

    private static Map<String, Object> fetchOpenIdConnectMetadata(String provider, HttpClient httpClient)
    {
        try
        {
            if (provider.endsWith("/"))
                provider = provider.substring(0, provider.length() - 1);

            Map<String, Object> result;
            String responseBody = httpClient.GET(provider + CONFIG_PATH)
                    .getContentAsString();
            Object parsedResult = JSON.parse(responseBody);

            if (parsedResult instanceof Map)
            {
                Map<?, ?> rawResult = (Map)parsedResult;
                result = rawResult.entrySet().stream()
                        .collect(Collectors.toMap(it -> it.getKey().toString(), Map.Entry::getValue));
            }
            else
            {
                LOG.warn("OpenID provider did not return a proper JSON object response. Result was '{}'", responseBody);
                throw new IllegalStateException("Could not parse OpenID provider's malformed response");
            }

            LOG.debug("discovery document {}", result);

            return result;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("invalid identity provider", e);
        }
    }

    public HttpClient getHttpClient()
    {
        return httpClient;
    }

    public String getAuthEndpoint()
    {
        return authEndpoint;
    }

    public String getClientId()
    {
        return clientId;
    }

    public String getClientSecret()
    {
        return clientSecret;
    }

    public String getIssuer()
    {
        return issuer;
    }

    public String getTokenEndpoint()
    {
        return tokenEndpoint;
    }

    public void addScopes(String... scopes)
    {
        if (scopes != null)
            Collections.addAll(this.scopes, scopes);
    }

    public List<String> getScopes()
    {
        return scopes;
    }
}
