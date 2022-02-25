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

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

public class OpenIdCredentialsTest
{
    @Test
    public void testSingleAudienceValueInArray() throws Exception
    {
        String issuer = "myIssuer123";
        String clientId = "myClientId456";
        OpenIdConfiguration configuration = new OpenIdConfiguration(issuer, "", "", clientId, "", new HttpClient());

        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", issuer);
        claims.put("aud", new String[]{clientId});
        claims.put("exp", System.currentTimeMillis() + 5000);

        assertDoesNotThrow(() -> new OpenIdCredentials(claims).redeemAuthCode(configuration));
    }
}
