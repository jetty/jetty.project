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

package org.eclipse.jetty.ee9.security.openid;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import org.eclipse.jetty.util.ajax.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Used to decode the ID Token from the base64 encrypted JSON Web Token (JWT).
 */
public class JwtDecoder
{
    private static final Logger LOG = LoggerFactory.getLogger(JwtDecoder.class);

    /**
     * Decodes a JSON Web Token (JWT) into a Map of claims.
     *
     * @param jwt the JWT to decode.
     * @return the map of claims encoded in the JWT.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> decode(String jwt)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("decode {}", jwt);

        String[] sections = jwt.split("\\.");
        if (sections.length != 3)
            throw new IllegalArgumentException("JWT does not contain 3 sections");

        Base64.Decoder decoder = Base64.getUrlDecoder();
        String jwtHeaderString = new String(decoder.decode(padJWTSection(sections[0])), StandardCharsets.UTF_8);
        String jwtClaimString = new String(decoder.decode(padJWTSection(sections[1])), StandardCharsets.UTF_8);
        String jwtSignature = sections[2];

        JSON json = new JSON();

        Object parsedJwtHeader = json.fromJSON(jwtHeaderString);
        if (!(parsedJwtHeader instanceof Map))
            throw new IllegalStateException("Invalid JWT header");
        Map<String, Object> jwtHeader = (Map<String, Object>)parsedJwtHeader;
        if (LOG.isDebugEnabled())
            LOG.debug("JWT Header: {}", jwtHeader);

        /* If the ID Token is received via direct communication between the Client
         and the Token Endpoint (which it is in this flow), the TLS server validation
          MAY be used to validate the issuer in place of checking the token signature. */
        if (LOG.isDebugEnabled())
            LOG.debug("JWT signature not validated {}", jwtSignature);

        Object parsedClaims = json.fromJSON(jwtClaimString);
        if (!(parsedClaims instanceof Map))
            throw new IllegalStateException("Could not decode JSON for JWT claims.");
        return (Map<String, Object>)parsedClaims;
    }

    static byte[] padJWTSection(String unpaddedEncodedJwtSection)
    {
        // If already padded just use what we are given.
        if (unpaddedEncodedJwtSection.endsWith("="))
            return unpaddedEncodedJwtSection.getBytes();

        int length = unpaddedEncodedJwtSection.length();
        int remainder = length % 4;

        // A valid base-64-encoded string will have a remainder of 0, 2 or 3. Never 1!
        if (remainder == 1)
            throw new IllegalArgumentException("Not a valid Base64-encoded string");

        byte[] paddedEncodedJwtSection;
        if (remainder > 0)
        {
            int paddingNeeded = (4 - remainder) % 4;
            paddedEncodedJwtSection = Arrays.copyOf(unpaddedEncodedJwtSection.getBytes(), length + paddingNeeded);
            Arrays.fill(paddedEncodedJwtSection, length, paddedEncodedJwtSection.length, (byte)'=');
        }
        else
        {
            paddedEncodedJwtSection = unpaddedEncodedJwtSection.getBytes();
        }

        return paddedEncodedJwtSection;
    }
}
