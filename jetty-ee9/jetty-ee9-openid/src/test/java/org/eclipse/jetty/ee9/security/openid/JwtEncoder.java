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

import java.util.Base64;

/**
 * A basic JWT encoder for testing purposes.
 */
public class JwtEncoder
{
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder();
    private static final String DEFAULT_HEADER = "{\"INFO\": \"this is not used or checked in our implementation\"}";
    private static final String DEFAULT_SIGNATURE = "we do not validate signature as we use the authorization code flow";

    public static String encode(String idToken)
    {
        return stripPadding(ENCODER.encodeToString(DEFAULT_HEADER.getBytes())) + "." +
            stripPadding(ENCODER.encodeToString(idToken.getBytes())) + "." +
            stripPadding(ENCODER.encodeToString(DEFAULT_SIGNATURE.getBytes()));
    }

    private static String stripPadding(String paddedBase64)
    {
        return paddedBase64.split("=")[0];
    }

    /**
     * Create a basic JWT for testing using argument supplied attributes.
     */
    public static String createIdToken(String provider, String clientId, String subject, String name, long expiry)
    {
        return "{" +
            "\"iss\": \"" + provider + "\"," +
            "\"sub\": \"" + subject + "\"," +
            "\"aud\": \"" + clientId + "\"," +
            "\"exp\": " + expiry + "," +
            "\"name\": \"" + name + "\"," +
            "\"email\": \"" + name + "@example.com" + "\"" +
            "}";
    }
}
