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

package org.eclipse.jetty.security.openid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the configuration for an OpenID Connect service.
 *
 * This uses the OpenID Provider URL with the path {@link #CONFIG_PATH} to discover
 * the required information about the OIDC service.
 */
public class OpenIdConfiguration extends ContainerLifeCycle
{
    private static final Logger LOG = LoggerFactory.getLogger(OpenIdConfiguration.class);
    private static final String CONFIG_PATH = "/.well-known/openid-configuration";
    private static final String AUTHORIZATION_ENDPOINT = "authorization_endpoint";
    private static final String TOKEN_ENDPOINT = "token_endpoint";
    private static final String ISSUER = "issuer";

    private final HttpClient httpClient;
    private final String issuer;
    private final String clientId;
    private final String clientSecret;
    private final List<String> scopes = new ArrayList<>();
    private final String authMethod;
    private String authEndpoint;
    private String tokenEndpoint;
    private boolean authenticateNewUsers = false;

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
        this(issuer, authorizationEndpoint, tokenEndpoint, clientId, clientSecret, "client_secret_post", httpClient);
    }

    /**
     * Create an OpenID configuration for a specific OIDC provider.
     * @param issuer The URL of the OpenID provider.
     * @param authorizationEndpoint the URL of the OpenID provider's authorization endpoint if configured.
     * @param tokenEndpoint the URL of the OpenID provider's token endpoint if configured.
     * @param clientId OAuth 2.0 Client Identifier valid at the Authorization Server.
     * @param clientSecret The client secret known only by the Client and the Authorization Server.
     * @param authMethod Authentication method to use with the Token Endpoint.
     * @param httpClient The {@link HttpClient} instance to use.
     */
    public OpenIdConfiguration(@Name("issuer") String issuer,
                               @Name("authorizationEndpoint") String authorizationEndpoint,
                               @Name("tokenEndpoint") String tokenEndpoint,
                               @Name("clientId") String clientId,
                               @Name("clientSecret") String clientSecret,
                               @Name("authMethod") String authMethod,
                               @Name("httpClient") HttpClient httpClient)
    {
        this.issuer = issuer;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.authEndpoint = authorizationEndpoint;
        this.tokenEndpoint = tokenEndpoint;
        this.httpClient = httpClient != null ? httpClient : newHttpClient();
        this.authMethod = authMethod;

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
            Map<String, Object> discoveryDocument = fetchOpenIdConnectMetadata();
            processMetadata(discoveryDocument);
        }
    }

    /**
     * Process the OpenID Connect metadata discovered by {@link #fetchOpenIdConnectMetadata()}.
     * By default, only the {@link #AUTHORIZATION_ENDPOINT} and {@link #TOKEN_ENDPOINT} claims are extracted.
     * @see <a href="https://openid.net/specs/openid-connect-discovery-1_0.html">OpenID Connect Discovery 1.0</a>
     */
    protected void processMetadata(Map<String, Object> discoveryDocument)
    {
        authEndpoint = (String)discoveryDocument.get(AUTHORIZATION_ENDPOINT);
        if (authEndpoint == null)
            throw new IllegalArgumentException(AUTHORIZATION_ENDPOINT);

        tokenEndpoint = (String)discoveryDocument.get(TOKEN_ENDPOINT);
        if (tokenEndpoint == null)
            throw new IllegalArgumentException(TOKEN_ENDPOINT);

        if (!Objects.equals(discoveryDocument.get(ISSUER), issuer))
            LOG.warn("The issuer in the metadata is not correct.");
    }

    /**
     * Obtain the JSON metadata from OpenID Connect Discovery Configuration Endpoint.
     * @return a set of Claims about the OpenID Provider's configuration in JSON format.
     */
    protected Map<String, Object> fetchOpenIdConnectMetadata()
    {
        String provider = issuer;
        if (provider.endsWith("/"))
            provider = provider.substring(0, provider.length() - 1);

        try
        {
            Map<String, Object> result;
            String responseBody = httpClient.GET(provider + CONFIG_PATH).getContentAsString();
            Object parsedResult = new JSON().fromJSON(responseBody);

            if (parsedResult instanceof Map)
            {
                Map<?, ?> rawResult = (Map<?, ?>)parsedResult;
                result = rawResult.entrySet().stream()
                        .filter(entry -> entry.getValue() != null)
                        .collect(Collectors.toMap(it -> it.getKey().toString(), Map.Entry::getValue));
                if (LOG.isDebugEnabled())
                    LOG.debug("discovery document {}", result);
                return result;
            }
            else
            {
                LOG.warn("OpenID provider did not return a proper JSON object response. Result was '{}'", responseBody);
                throw new IllegalStateException("Could not parse OpenID provider's malformed response");
            }
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("invalid identity provider " + provider, e);
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

    public String getAuthMethod()
    {
        return authMethod;
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

    public boolean isAuthenticateNewUsers()
    {
        return authenticateNewUsers;
    }

    public void setAuthenticateNewUsers(boolean authenticateNewUsers)
    {
        this.authenticateNewUsers = authenticateNewUsers;
    }

    private static HttpClient newHttpClient()
    {
        ClientConnector connector = new ClientConnector();
        connector.setSslContextFactory(new SslContextFactory.Client(false));
        return new HttpClient(new HttpClientTransportOverHTTP(connector));
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{iss=%s, clientId=%s, authEndpoint=%s, authMethod=%s, tokenEndpoint=%s, scopes=%s, authNewUsers=%s}",
            getClass().getSimpleName(), hashCode(), issuer, clientId, authEndpoint, authMethod, tokenEndpoint, scopes, authenticateNewUsers);
    }
}
