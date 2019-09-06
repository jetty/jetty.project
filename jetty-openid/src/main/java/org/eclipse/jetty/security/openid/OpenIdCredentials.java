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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class OpenIdCredentials
{
    private static final Logger LOG = Log.getLogger(OpenIdCredentials.class);

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

                claims = decodeJWT(idToken);
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
        {
            LOG.warn("Invalid \"iss\" Claim: was {}, expected {}", claims.get("iss"), configuration.getIssuer());
            throw new IllegalArgumentException("invalid iss claim");
        }

        // The aud (audience) Claim MUST contain the client_id value.
        if (!configuration.getClientId().equals(claims.get("aud")))
        {
            LOG.warn("Audience Claim MUST contain the client_id value");
            throw new IllegalArgumentException("invalid aud claim");
        }

        // If an azp (authorized party) Claim is present, verify that its client_id is the Claim Value.
        Object azp = claims.get("azp");
        if (azp != null && !configuration.getClientId().equals(azp))
        {
            LOG.warn("Authorized party claim value should be the client_id");
            throw new IllegalArgumentException("invalid azp claim");
        }
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

    protected Map<String, Object> decodeJWT(String jwt) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("decodeJWT {}", jwt);

        String[] sections = jwt.split("\\.");
        if (sections.length != 3)
            throw new IllegalArgumentException("JWT does not contain 3 sections");

        String jwtHeaderString = new String(Base64.getDecoder().decode(sections[0]), StandardCharsets.UTF_8);
        String jwtClaimString = new String(Base64.getDecoder().decode(sections[1]), StandardCharsets.UTF_8);
        String jwtSignature = sections[2];

        Map<String, Object> jwtHeader = (Map)JSON.parse(jwtHeaderString);
        LOG.debug("JWT Header: {}", jwtHeader);

        /* If the ID Token is received via direct communication between the Client
         and the Token Endpoint (which it is in this flow), the TLS server validation
          MAY be used to validate the issuer in place of checking the token signature. */
        if (LOG.isDebugEnabled())
            LOG.debug("JWT signature not validated {}", jwtSignature);

        return (Map)JSON.parse(jwtClaimString);
    }

    private Map<String, Object> claimAuthCode(String authCode) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("claimAuthCode {}", authCode);

        // Use the authorization code to get the id_token from the OpenID Provider
        String urlParameters = "code=" + authCode +
            "&client_id=" + configuration.getClientId() +
            "&client_secret=" + configuration.getClientSecret() +
            "&redirect_uri=" + redirectUri +
            "&grant_type=authorization_code";

        URL url = new URL(configuration.getTokenEndpoint());
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Host", configuration.getOpenIdProvider());
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setRequestProperty("charset", "utf-8");

        try (DataOutputStream wr = new DataOutputStream(connection.getOutputStream()))
        {
            wr.write(urlParameters.getBytes(StandardCharsets.UTF_8));
        }

        InputStream content = (InputStream)connection.getContent();
        return (Map)JSON.parse(IO.toString(content));
    }
}
