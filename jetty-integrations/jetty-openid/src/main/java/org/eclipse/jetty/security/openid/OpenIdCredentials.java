//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.io.Serial;
import java.io.Serializable;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.Authentication;
import org.eclipse.jetty.client.BasicAuthentication;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.FormRequestContent;
import org.eclipse.jetty.client.Request;
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
    @Serial
    private static final long serialVersionUID = 4766053233370044796L;

    private final String redirectUri;
    private String authCode;
    private Map<String, Object> response;
    private Map<String, Object> claims;
    private Fields errorFields;

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

    /**
     * <p>This returns a non-null value only when {@link #redeemAuthCode(OpenIdConfiguration)} has been called and an error occurred.</p>
     * <p>The returned {@link Fields} will contain an entry for {@link OpenIdAuthenticator#ERROR_PARAMETER}, and optional
     * fields from the response if present, including {@code error}, {@code error_description} and {@code error_uri}.</p>
     * @return the error fields or null if no error has occurred.
     */
    public Fields getErrorFields()
    {
        return errorFields;
    }

    /**
     * <p>Redeems the Authorization Code with the Token Endpoint to receive an ID Token.</p>
     * <p>{@link #getErrorFields()} should be called directly following this to check if an error occurred.</p>
     * @param configuration the openIdConfiguration to use.
     */
    public void redeemAuthCode(OpenIdConfiguration configuration)
    {
        if (authCode != null)
        {
            try
            {
                response = claimAuthCode(configuration);

                // Parse error response define by Section 5.2 of OAuth 2.0 [RFC6749].
                String errorCode = (String)response.get("error");
                if (errorCode != null)
                {
                    String errorDescription = (String)response.get("error_description");
                    String errorUri = (String)response.get("error_uri");
                    StringBuilder errorMessage = new StringBuilder();
                    errorMessage.append("auth failed: ").append(errorCode);
                    if (errorDescription != null)
                        errorMessage.append(" - ").append(errorDescription);

                    errorFields = new Fields();
                    errorFields.put(OpenIdAuthenticator.ERROR_PARAMETER, errorMessage.toString());
                    errorFields.put("error", errorCode);
                    if (errorDescription != null)
                        errorFields.put("error_description", errorDescription);
                    if (errorUri != null)
                        errorFields.put("error_uri", errorUri);
                    return;
                }

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
                validateClaims(configuration);
            }
            catch (Throwable t)
            {
                errorFields = new Fields();
                errorFields.put(OpenIdAuthenticator.ERROR_PARAMETER, t.getMessage());
            }
            finally
            {
                // reset authCode as it can only be used once
                authCode = null;
            }
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
        if (isExpired())
            throw new AuthenticationException("ID Token has expired");
    }

    public boolean isExpired()
    {
        return checkExpiry(claims);
    }

    public static boolean checkExpiry(Map<String, Object> claims)
    {
        if (claims == null)
            return true;

        // Check that the ID token has not expired by checking the exp claim.
        return Instant.ofEpochSecond((Long)claims.get("exp")).isBefore(Instant.now());
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
        switch (configuration.getAuthenticationMethod())
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
                throw new IllegalStateException(configuration.getAuthenticationMethod());
        }

        FormRequestContent formContent = new FormRequestContent(fields);
        request = request.body(formContent).timeout(10, TimeUnit.SECONDS);
        ContentResponse response = request.send();
        String responseBody = response.getContentAsString();
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
