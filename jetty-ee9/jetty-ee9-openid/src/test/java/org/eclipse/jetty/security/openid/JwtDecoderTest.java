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
            Arguments.of("@#", "@#=="),
            Arguments.of("X=", "X="),
            Arguments.of("XX=", "XX="),
            Arguments.of("XX==", "XX=="),
            Arguments.of("XXX=", "XXX="),
            Arguments.of("", "")
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

    @Test
    public void testDecodeMissingPadding()
    {
        // Example given in Issue #4128 which requires the re-adding the B64 padding to decode.
        String jwt = "eyJraWQiOiIxNTU1OTM0ODQ3IiwieDV0IjoiOWdCOW9zRldSRHRSMkhtNGNmVnJnWTBGcmZRIiwiYWxnIjoiUlMyNTYifQ" +
            ".eyJhdF9oYXNoIjoiQTA0NUoxcE5YRk1nYzlXN2wxSk1fUSIsImRlbGVnYXRpb25faWQiOiJjZTBhNjRlNS0xYWY3LTQ2MzEtOGUz" +
            "NC1mNDE5N2JkYzVjZTAiLCJhY3IiOiJ1cm46c2U6Y3VyaXR5OmF1dGhlbnRpY2F0aW9uOmh0bWwtZm9ybTpodG1sLXByaW1hcnkiL" +
            "CJzX2hhc2giOiIwc1FtRG9YY3FwcnM4NWUzdy0wbHdBIiwiYXpwIjoiNzZiZTc5Y2ItM2E1Ni00ZTE3LTg3NzYtNDI1Nzc5MjRjYz" +
            "c2IiwiYXV0aF90aW1lIjoxNTY5NjU4MDk1LCJleHAiOjE1Njk2NjE5OTUsIm5iZiI6MTU2OTY1ODM5NSwianRpIjoiZjJkNWI2YzE" +
            "tNTIxYi00Y2Y5LThlNWEtOTg5NGJhNmE0MzkyIiwiaXNzIjoiaHR0cHM6Ly9ub3JkaWNhcGlzLmN1cml0eS5pby9-IiwiYXVkIjoi" +
            "NzZiZTc5Y2ItM2E1Ni00ZTE3LTg3NzYtNDI1Nzc5MjRjYzc2Iiwic3ViIjoibmlrb3MiLCJpYXQiOjE1Njk2NTgzOTUsInB1cnBvc" +
            "2UiOiJpZCJ9.Wd458zNmXggpkDN6vbS3-aiajh4-VbkmcStLYUqahYJUp9p-AUI_RZttWvwh3UDMG9rWww_ya8KFK_SkPfKooEaSN" +
            "OjOhw0ox4d-9lgti3J49eRyO20RViXvRHyLVtcjv5IaqvMXgwW60Thubv19OION7DstyArffcxNNSpiqDq6wjd0T2DJ3gSXXlJHLT" +
            "Wrry3svqu1j_GCbHc04XYGicxsusKgc3n22dh4I6p4trdo0Gu5Un0bZ8Yov7IzWItqTgm9X5r9gZlAOLcAuK1WTwkzAwZJ24HgvxK" +
            "muYfV_4ZCg_VPN2Op8YPuRAQOgUERpeTv1RDFTOG9GKZIMBVR0A";

        // Decode the ID Token and verify the claims are the correct.
        Map<String, Object> decodedClaims = JwtDecoder.decode(jwt);
        assertThat(decodedClaims.get("sub"), is("nikos"));
        assertThat(decodedClaims.get("aud"), is("76be79cb-3a56-4e17-8776-42577924cc76"));
        assertThat(decodedClaims.get("exp"), is(1569661995L));
    }
}
