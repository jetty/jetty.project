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
                String jwt = getJWT();
                decodeJWT(jwt);

                if (LOG.isDebugEnabled())
                    LOG.debug("userInfo {}", claims);
            }
            finally
            {
                // reset authCode as it can only be used once
                authCode = null;
            }
        }
    }

    public boolean validate()
    {
        if (authCode != null)
            return false;

        // Check audience should be clientId
        String audience = (String)claims.get("aud");
        if (!configuration.getIdentityProvider().equals(audience))
        {
            LOG.warn("Audience claim MUST contain the value of the Issuer Identifier for the OP", this);
            //return false;
        }

        String issuer = (String)claims.get("iss");
        if (!configuration.getClientId().equals(issuer))
        {
            LOG.warn("Issuer claim MUST be the client_id of the OAuth Client {}", this);
            //return false;
        }

        // Check expiry
        long expiry = (Long)claims.get("exp");
        long currentTimeSeconds = (long)(System.currentTimeMillis() / 1000F);
        if (currentTimeSeconds > expiry)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("OpenId Credentials expired {}", this);
            return false;
        }

        return true;
    }

    private void decodeJWT(String jwt) throws IOException
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

        // validate signature
        LOG.warn("Signature NOT validated {}", jwtSignature);

        // response should be a set of name/value pairs
        claims = (Map)JSON.parse(jwtClaimString);
    }

    private String getJWT() throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("getJWT {}", authCode);

        // Use the auth code to get the id_token from the OpenID Provider
        String urlParameters = "code=" + authCode +
            "&client_id=" + configuration.getClientId() +
            "&client_secret=" + configuration.getClientSecret() +
            "&redirect_uri=" + redirectUri +
            "&grant_type=authorization_code";

        byte[] payload = urlParameters.getBytes(StandardCharsets.UTF_8);
        URL url = new URL(configuration.getTokenEndpoint());
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Host", configuration.getIdentityProvider());
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setRequestProperty("charset", "utf-8");

        try (DataOutputStream wr = new DataOutputStream(connection.getOutputStream()))
        {
            wr.write(payload);
        }

        // get response and extract id_token jwt
        InputStream content = (InputStream)connection.getContent();
        response = (Map)JSON.parse(IO.toString(content));

        if (LOG.isDebugEnabled())
            LOG.debug("responseMap: {}", response);

        return (String)response.get("id_token");
    }
}
