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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Used to decode the ID Token from the base64 encrypted JSON Web Token (JWT).
 */
public class CredentialsDecoder
{
    private static final Logger LOG = Log.getLogger(CredentialsDecoder.class);
    private static final Base64.Decoder decoder = Base64.getUrlDecoder();

    /**
     * Decodes a JSON Web Token (JWT) into a Map of claims.
     * @param jwt the JWT to decode.
     * @return the map of claims encoded in the JWT.
     */
    public static Map<String, Object> decode(String jwt)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("decode {}", jwt);

        String[] sections = jwt.split("\\.");
        if (sections.length != 3)
            throw new IllegalArgumentException("JWT does not contain 3 sections");

        String jwtHeaderString = new String(decoder.decode(padJWTSection(sections[0])), StandardCharsets.UTF_8);
        String jwtClaimString = new String(decoder.decode(padJWTSection(sections[1])), StandardCharsets.UTF_8);
        String jwtSignature = sections[2];

        Map<String, Object> jwtHeader = (Map)JSON.parse(jwtHeaderString);
        if (LOG.isDebugEnabled())
            LOG.debug("JWT Header: {}", jwtHeader);

        /* If the ID Token is received via direct communication between the Client
         and the Token Endpoint (which it is in this flow), the TLS server validation
          MAY be used to validate the issuer in place of checking the token signature. */
        if (LOG.isDebugEnabled())
            LOG.debug("JWT signature not validated {}", jwtSignature);

        return (Map)JSON.parse(jwtClaimString);
    }

    static byte[] padJWTSection(String unpaddedEncodedJwtSection)
    {
        int length = unpaddedEncodedJwtSection.length();
        int remainder = length % 4;

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
