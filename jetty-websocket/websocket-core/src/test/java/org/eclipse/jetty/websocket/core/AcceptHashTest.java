//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.TypeUtil;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class AcceptHashTest
{
    private static String hexAsKey(String hex)
    {
        byte key[] = TypeUtil.fromHexString(hex);
        assertThat("Key size of hex:[" + hex + "]", key.length, is(16));
        return String.valueOf(B64Code.encode(key));
    }

    @Parameterized.Parameters
    public static List<String[]> data()
    {
        List<String[]> cases = new ArrayList<>();

        cases.add(new String[]{hexAsKey("00112233445566778899AABBCCDDEEFF"), "mVL6JKtNRC4tluIaFAW2hhMffgE="});

        // Test values present in RFC6455
        // Note: client key bytes are "7468652073616d706c65206e6f6e6365"
        cases.add(new String[]{hexAsKey("7468652073616d706c65206e6f6e6365"), "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="});
        cases.add(new String[]{"dGhlIHNhbXBsZSBub25jZQ==", "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="});

        // Real World Captured values (from functional browser sessions)
        cases.add(new String[]{"xo++9aD2ivkaFaRNKcOrwQ==", "eYTC3DAl9qX36VRW2fZ/LPwTFZU="});

        return cases;
    }

    @Parameterized.Parameter(0)
    public String clientKey;

    @Parameterized.Parameter(1)
    public String expectedHash;

    @Test
    public void testHashKey()
    {
        String serverAccept = AcceptHash.hashKey(clientKey);
        assertThat("Hashed Key", serverAccept, is(expectedHash));
    }
}
