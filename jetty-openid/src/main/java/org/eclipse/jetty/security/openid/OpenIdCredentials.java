//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>The credentials of an user to be authenticated with OpenID Connect. This will contain
 * the OpenID ID Token and the OAuth 2.0 Access Token.</p>
 *
 * <p>
 * This is constructed with an authorization code from the authentication request. This authorization code
 * is then exchanged using {@link #redeemAuthCode()} for a response containing the ID Token and Access Token.
 * The response is then validated against the {@link OpenIdConfiguration}.
 * </p>
 */
public class OpenIdCredentials implements Serializable
{
    private static final Logger LOG = Log.getLogger(OpenIdCredentials.class);
    private static final long serialVersionUID = 4766053233370044796L;

    private final String redirectUri;
    private final OpenIdConfiguration configuration;
    private String authCode;
    private Map<String, Object> response;
    private Map<String, Object> claims;

    public OpenIdCredentials(String authCode, String redirectUri, OpenIdConfiguration configuration)
    {
        this.authCode = authCode;
        this.redirectUri = redirectUri;
        this.configuration = configuration;
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

    public void redeemAuthCode() throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("redeemAuthCode() {}", this);

        if (authCode != null)
        {
            try
            {
                response = claimAuthCode(authCode);
                if (LOG.isDebugEnabled())
                    LOG.debug("response: {}", response);

                String idToken = (String)response.get("id_token");
                if (idToken == null)
                    throw new IllegalArgumentException("no id_token");

                String accessToken = (String)response.get("access_token");
                if (accessToken == null)
                    throw new IllegalArgumentException("no access_token");

                String tokenType = (String)response.get("token_type");
                if (!"Bearer".equalsIgnoreCase(tokenType))
                    throw new IllegalArgumentException("invalid token_type");

                claims = JwtDecoder.decode(idToken);
                if (LOG.isDebugEnabled())
                    LOG.debug("claims {}", claims);
                validateClaims();
            }
            finally
            {
                // reset authCode as it can only be used once
                authCode = null;
            }
        }
    }

    private void validateClaims()
    {
        // Issuer Identifier for the OpenID Provider MUST exactly match the value of the iss (issuer) Claim.
        if (!configuration.getIssuer().equals(claims.get("iss")))
            throw new IllegalArgumentException("Issuer Identifier MUST exactly match the iss Claim");

        // The aud (audience) Claim MUST contain the client_id value.
        validateAudience();

        // If an azp (authorized party) Claim is present, verify that its client_id is the Claim Value.
        Object azp = claims.get("azp");
        if (azp != null && !configuration.getClientId().equals(azp))
            throw new IllegalArgumentException("Authorized party claim value should be the client_id");
    }

    private void validateAudience()
    {
        Object aud = claims.get("aud");
        String clientId = configuration.getClientId();
        boolean isString = aud instanceof String;
        boolean isList = aud instanceof Object[];
        boolean isValidType = isString || isList;

        if (isString && !clientId.equals(aud))
            throw new IllegalArgumentException("Audience Claim MUST contain the client_id value");
        else if (isList)
        {
            if (!Arrays.asList((Object[])aud).contains(clientId))
                throw new IllegalArgumentException("Audience Claim MUST contain the client_id value");

            if (claims.get("azp") == null)
                throw new IllegalArgumentException("A multi-audience ID token needs to contain an azp claim");
        }
        else if (!isValidType)
            throw new IllegalArgumentException("Audience claim was not valid");
    }

    public boolean isExpired()
    {
        if (authCode != null || claims == null)
            return true;

        // Check expiry
        long expiry = (Long)claims.get("exp");
        long currentTimeSeconds = (long)(System.currentTimeMillis() / 1000F);
        if (currentTimeSeconds > expiry)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("OpenId Credentials expired {}", this);
            return true;
        }

        return false;
    }

    private Map<String, Object> claimAuthCode(String authCode) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("claimAuthCode {}", authCode);

        // Use the authorization code to get the id_token from the OpenID Provider
        String urlParameters = "code=" + authCode +
            "&client_id=" + UrlEncoded.encodeString(configuration.getClientId(), StandardCharsets.UTF_8) +
            "&client_secret=" + UrlEncoded.encodeString(configuration.getClientSecret(), StandardCharsets.UTF_8) +
            "&redirect_uri=" + UrlEncoded.encodeString(redirectUri, StandardCharsets.UTF_8) +
            "&grant_type=authorization_code";

        URL url = new URL(configuration.getTokenEndpoint());
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        try
        {
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Host", configuration.getOpenIdProvider());
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            try (DataOutputStream wr = new DataOutputStream(connection.getOutputStream()))
            {
                wr.write(urlParameters.getBytes(StandardCharsets.UTF_8));
            }

            try (InputStream content = (InputStream)connection.getContent())
            {
                return (Map)JSON.parse(IO.toString(content));
            }
        }
        finally
        {
            connection.disconnect();
        }
    }
}
