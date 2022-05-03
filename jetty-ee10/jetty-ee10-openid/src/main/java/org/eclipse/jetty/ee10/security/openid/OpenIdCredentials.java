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

package org.eclipse.jetty.ee10.security.openid;

import java.io.Serializable;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.FormRequestContent;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.ajax.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The credentials of an user to be authenticated with OpenID Connect. This will contain
 * the OpenID ID Token and the OAuth 2.0 Access Token.</p>
 *
 * <p>
 * This is constructed with an authorization code from the authentication request. This authorization code
 * is then exchanged using {@link #redeemAuthCode(OpenIdConfiguration)} for a response containing the ID Token and Access Token.
 * The response is then validated against the {@link OpenIdConfiguration}.
 * </p>
 */
public class OpenIdCredentials implements Serializable
{
    private static final Logger LOG = LoggerFactory.getLogger(OpenIdCredentials.class);
    private static final long serialVersionUID = 4766053233370044796L;

    private final String redirectUri;
    private String authCode;
    private Map<String, Object> response;
    private Map<String, Object> claims;
    private boolean verified = false;

    public OpenIdCredentials(Map<String, Object> claims)
    {
        this.redirectUri = null;
        this.authCode = null;
        this.claims = claims;
    }

    public OpenIdCredentials(String authCode, String redirectUri)
    {
        this.authCode = authCode;
        this.redirectUri = redirectUri;
    }

    public String getUserId()
    {
        return (String)claims.get("sub");
    }

    public Map<String, Object> getClaims()
    {
        return claims;
    }

    public Map<String, Object> getResponse()
    {
        return response;
    }

    public void redeemAuthCode(OpenIdConfiguration configuration) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("redeemAuthCode() {}", this);

        if (authCode != null)
        {
            try
            {
                response = claimAuthCode(configuration);
                if (LOG.isDebugEnabled())
                    LOG.debug("response: {}", response);

                String idToken = (String)response.get("id_token");
                if (idToken == null)
                    throw new AuthenticationException("no id_token");

                String accessToken = (String)response.get("access_token");
                if (accessToken == null)
                    throw new AuthenticationException("no access_token");

                String tokenType = (String)response.get("token_type");
                if (!"Bearer".equalsIgnoreCase(tokenType))
                    throw new AuthenticationException("invalid token_type");

                claims = JwtDecoder.decode(idToken);
                if (LOG.isDebugEnabled())
                    LOG.debug("claims {}", claims);
            }
            finally
            {
                // reset authCode as it can only be used once
                authCode = null;
            }
        }

        if (!verified)
        {
            validateClaims(configuration);
            verified = true;
        }
    }

    private void validateClaims(OpenIdConfiguration configuration) throws Exception
    {
        // Issuer Identifier for the OpenID Provider MUST exactly match the value of the iss (issuer) Claim.
        if (!configuration.getIssuer().equals(claims.get("iss")))
            throw new AuthenticationException("Issuer Identifier MUST exactly match the iss Claim");

        // The aud (audience) Claim MUST contain the client_id value.
        validateAudience(configuration);

        // If an azp (authorized party) Claim is present, verify that its client_id is the Claim Value.
        Object azp = claims.get("azp");
        if (azp != null && !configuration.getClientId().equals(azp))
            throw new AuthenticationException("Authorized party claim value should be the client_id");

        // Check that the ID token has not expired by checking the exp claim.
        long expiry = (Long)claims.get("exp");
        long currentTimeSeconds = (long)(System.currentTimeMillis() / 1000F);
        if (currentTimeSeconds > expiry)
            throw new AuthenticationException("ID Token has expired");
    }

    private void validateAudience(OpenIdConfiguration configuration) throws AuthenticationException
    {
        Object aud = claims.get("aud");
        String clientId = configuration.getClientId();
        boolean isString = aud instanceof String;
        boolean isList = aud instanceof Object[];
        boolean isValidType = isString || isList;

        if (isString && !clientId.equals(aud))
            throw new AuthenticationException("Audience Claim MUST contain the client_id value");
        else if (isList)
        {
            List<Object> list = Arrays.asList((Object[])aud);
            if (!list.contains(clientId))
                throw new AuthenticationException("Audience Claim MUST contain the client_id value");

            if (list.size() > 1 && claims.get("azp") == null)
                throw new AuthenticationException("A multi-audience ID token needs to contain an azp claim");
        }
        else if (!isValidType)
            throw new AuthenticationException("Audience claim was not valid");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> claimAuthCode(OpenIdConfiguration configuration) throws Exception
    {
        Fields fields = new Fields();
        fields.add("code", authCode);
        fields.add("redirect_uri", redirectUri);
        fields.add("grant_type", "authorization_code");

        Request request = configuration.getHttpClient().POST(configuration.getTokenEndpoint());
        switch (configuration.getAuthMethod())
        {
            case "client_secret_basic":
                URI uri = URI.create(configuration.getTokenEndpoint());
                Authentication.Result authentication = new BasicAuthentication.BasicResult(uri, configuration.getClientId(), configuration.getClientSecret());
                authentication.apply(request);
                break;
            case "client_secret_post":
                fields.add("client_id", configuration.getClientId());
                fields.add("client_secret", configuration.getClientSecret());
                break;
            default:
                throw new IllegalStateException(configuration.getAuthMethod());
        }

        FormRequestContent formContent = new FormRequestContent(fields);
        request = request.body(formContent).timeout(10, TimeUnit.SECONDS);
        ContentResponse response = request.send();
        String responseBody = response.getContentAsString();
        if (LOG.isDebugEnabled())
            LOG.debug("Authentication response: {}", responseBody);

        Object parsedResponse = new JSON().fromJSON(responseBody);
        if (!(parsedResponse instanceof Map))
            throw new AuthenticationException("Malformed response from OpenID Provider");
        return (Map<String, Object>)parsedResponse;
    }

    public static class AuthenticationException extends Exception
    {
        public AuthenticationException(String message)
        {
            super(message);
        }
    }
}
