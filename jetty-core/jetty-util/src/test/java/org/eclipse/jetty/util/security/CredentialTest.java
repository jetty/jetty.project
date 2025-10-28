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

package org.eclipse.jetty.util.security;

import org.eclipse.jetty.util.security.Credential.Crypt;
import org.eclipse.jetty.util.security.Credential.MD5;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CredentialTest
{
    @Test
    public void testCrypt()
    {
        Crypt c1 = (Crypt)Credential.getCredential(Crypt.crypt("fred", "abc123"));
        Crypt c2 = (Crypt)Credential.getCredential(Crypt.crypt("fred", "abc123"));
        Crypt c3 = (Crypt)Credential.getCredential(Crypt.crypt("fred", "xyz123"));
        Credential c4 = Credential.getCredential(Crypt.crypt("fred", "xyz123"));

        assertEquals(c1, c2);
        assertEquals(c2, c1);
        assertNotEquals(c1, c3);
        assertNotEquals(c3, c1);
        assertNotEquals(c3, c2);
        assertEquals(c4, c3);
        assertNotEquals(c4, c1);
    }

    @Test
    public void testMD5()
    {
        MD5 m1 = (MD5)Credential.getCredential(MD5.digest("123foo"));
        MD5 m2 = (MD5)Credential.getCredential(MD5.digest("123foo"));
        MD5 m3 = (MD5)Credential.getCredential(MD5.digest("123boo"));

        assertEquals(m1, m2);
        assertEquals(m2, m1);
        assertNotEquals(m3, m1);
    }

    @Test
    public void testPassword()
    {
        Password p1 = new Password(Password.obfuscate("abc123"));
        Credential p2 = Credential.getCredential(Password.obfuscate("abc123"));

        assertEquals(p1, p2);
    }

    @Test
    public void testStringEquals()
    {
        assertTrue(Credential.stringEquals("foo", "foo"));
        assertFalse(Credential.stringEquals("foo", "fooo"));
        assertFalse(Credential.stringEquals("foo", "fo"));
        assertFalse(Credential.stringEquals("foo", "bar"));
    }

    @Test
    public void testBytesEquals()
    {
        assertTrue(Credential.byteEquals("foo".getBytes(), "foo".getBytes()));
        assertFalse(Credential.byteEquals("foo".getBytes(), "fooo".getBytes()));
        assertFalse(Credential.byteEquals("foo".getBytes(), "fo".getBytes()));
        assertFalse(Credential.byteEquals("foo".getBytes(), "bar".getBytes()));
    }

    @Test
    public void testEmptyString()
    {
        assertFalse(Credential.stringEquals("fooo", ""));
        assertFalse(Credential.stringEquals("", "fooo"));
        assertTrue(Credential.stringEquals("", ""));
    }

    @Test
    public void testEmptyBytes()
    {
        assertFalse(Credential.byteEquals("fooo".getBytes(), "".getBytes()));
        assertFalse(Credential.byteEquals("".getBytes(), "fooo".getBytes()));
        assertTrue(Credential.byteEquals("".getBytes(), "".getBytes()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "CRYPT:usjRS48E8ZADM",
        "OBF:1v2j1uum1xtv1zej1zer1xtn1uvk1v1v",
        "MD5:5f4dcc3b5aa765d61d8327deb882cf99",
        "MD:SHA-1:5bAa61E4C9B93f3f0682250b6cF8331b7eE68fD8"
    })
    public void testGetCredential(String encoded)
    {
        Credential credential = Credential.getCredential(encoded);
        assertTrue(credential.check(Credential.getCredential("password")));
        assertTrue(credential.check("password"));
    }
}
