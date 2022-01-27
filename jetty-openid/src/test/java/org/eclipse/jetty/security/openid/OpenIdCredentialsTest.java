//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
