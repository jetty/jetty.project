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

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class JwtDecoderTest
{
    public static Stream<Arguments> paddingExamples()
    {
        return Stream.of(
            Arguments.of("XXXX", "XXXX"),
            Arguments.of("XXX", "XXX="),
            Arguments.of("XX", "XX=="),
            Arguments.of("XXX=", "XXX="),
            Arguments.of("X-X", "X-X="),
            Arguments.of("@#", "@#==")
            // Arguments.of("X=", "?") // TODO: what to expect in this case
            );
    }

    public static Stream<Arguments> badPaddingExamples()
    {
        return Stream.of(
            Arguments.of("X"),
            Arguments.of("XXXXX")
        );
    }

    @ParameterizedTest
    @MethodSource("paddingExamples")
    public void testPaddingBase64(String input, String expected)
    {
        byte[] actual = JwtDecoder.padJWTSection(input);
        assertThat(actual, is(expected.getBytes()));
    }

    @ParameterizedTest
    @MethodSource("badPaddingExamples")
    public void testPaddingInvalidBase64(String input)
    {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> JwtDecoder.padJWTSection(input));

        assertThat(error.getMessage(), is("Not a valid Base64-encoded string"));
    }

    @Test
    public void testEncodeDecode()
    {
        String issuer = "example.com";
        String subject = "1234";
        String clientId = "1234.client.id";
        String name = "Bob";
        long expiry = 123;

        // Create a fake ID Token.
        String claims = JwtEncoder.createIdToken(issuer, clientId, subject, name, expiry);
        String idToken = JwtEncoder.encode(claims);

        // Decode the ID Token and verify the claims are the same.
        Map<String, Object> decodedClaims = JwtDecoder.decode(idToken);
        assertThat(decodedClaims.get("iss"), is(issuer));
        assertThat(decodedClaims.get("sub"), is(subject));
        assertThat(decodedClaims.get("aud"), is(clientId));
        assertThat(decodedClaims.get("name"), is(name));
        assertThat(decodedClaims.get("exp"), is(expiry));
    }
}