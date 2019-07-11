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

package org.eclipse.jetty.websocket.core;

import java.util.Base64;
import java.util.stream.Stream;

import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.websocket.core.internal.WebSocketCore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class AcceptHashTest
{
    private static String hexAsKey(String hex)
    {
        byte[] key = TypeUtil.fromHexString(hex);
        assertThat("Key size of hex:[" + hex + "]", key.length, is(16));
        return Base64.getEncoder().encodeToString(key);
    }

    public static Stream<Arguments> data()
    {
        return Stream.of(
            Arguments.of(hexAsKey("00112233445566778899AABBCCDDEEFF"), "mVL6JKtNRC4tluIaFAW2hhMffgE="),

            // Test values present in RFC6455
            // Note: client key bytes are "7468652073616d706c65206e6f6e6365"
            Arguments.of(hexAsKey("7468652073616d706c65206e6f6e6365"), "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="),
            Arguments.of("dGhlIHNhbXBsZSBub25jZQ==", "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="),

            // Real World Captured values (from functional browser sessions)
            Arguments.of("xo++9aD2ivkaFaRNKcOrwQ==", "eYTC3DAl9qX36VRW2fZ/LPwTFZU=")
        );
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testHashKey(String clientKey, String expectedHash)
    {
        String serverAccept = WebSocketCore.hashKey(clientKey);
        assertThat("Hashed Key", serverAccept, is(expectedHash));
    }
}
