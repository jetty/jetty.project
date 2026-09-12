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

package org.eclipse.jetty.util;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class InetAddressPatternTest
{
    @Test
    public void testNullPattern()
    {
        assertNull(InetAddressPattern.from(null));
    }

    @ParameterizedTest
    @CsvSource({
        "1.2.3.4, 1.2.3.4, true",
        "1.2.3.4, 1.2.3.5, false",
        "localhost, localhost, true",
        "'[::1]', '0:0:0:0:0:0:0:1', true",
        "'[::1]', '::2', false",
        "10.10.0.0/16, 10.10.0.0, true",
        "10.10.0.0/16, 10.10.255.255, true",
        "10.10.0.0/16, 10.11.0.0, false",
        "192.0.80.0/22, 192.0.83.255, true",
        "192.0.80.0/22, 192.0.84.0, false",
        "0.0.0.0/0, 203.0.113.17, true",
        "255.255.255.255/32, 255.255.255.255, true",
        "255.255.255.255/32, 255.255.255.254, false",
        "'[abcd:ef00::]/24', 'abcd:efff::ffff', true",
        "'[abcd:ef00::]/24', 'abcd:f000::', false",
        "'[::]/0', '2001:db8::1', true",
        "'[::1]/128', '::1', true",
        "'[::1]/128', '::2', false",
        "10.0.0.4-10.0.0.6, 10.0.0.3, false",
        "10.0.0.4-10.0.0.6, 10.0.0.4, true",
        "10.0.0.4-10.0.0.6, 10.0.0.6, true",
        "10.0.0.4-10.0.0.6, 10.0.0.7, false",
        "10.1.0.254-10.1.1.1, 10.1.1.0, true",
        "'[abcd:ef::fffe]-[abcd:ef::1:1]', 'abcd:ef::ffff', true",
        "'[abcd:ef::fffe]-[abcd:ef::1:1]', 'abcd:ef::1:2', false",
        "10.-.245-.-2, 10.0.245.0, true",
        "10.-.245-.-2, 10.255.245.2, true",
        "10.-.245-.-2, 10.255.245.3, false",
        "11.11.11.127-129, 11.11.11.126, false",
        "11.11.11.127-129, 11.11.11.128, true",
        "11.11.11.127-129, 11.11.11.130, false"
    })
    public void testMatches(String pattern, String address, boolean expected) throws Exception
    {
        InetAddressPattern inetAddressPattern = InetAddressPattern.from(pattern);
        assertEquals(expected, inetAddressPattern.test(InetAddress.getByName(address)));
    }

    @ParameterizedTest
    @CsvSource({
        "1.2.3.4, '::1'",
        "10.0.0.1-10.0.0.2, '::1'",
        "10.-.0.1, '::1'",
        "'[::1]', 127.0.0.1",
        "'[::]/0', 127.0.0.1",
        "'[::1]-[::2]', 127.0.0.1"
    })
    public void testDifferentAddressFamilyDoesNotMatch(String pattern, String address) throws Exception
    {
        assertFalse(InetAddressPattern.from(pattern).test(InetAddress.getByName(address)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "1.2.3.4",
        "10.0.0.0/8",
        "10.0.0.1-10.0.0.2",
        "10.-.0.1"
    })
    public void testNullAddressDoesNotMatch(String pattern)
    {
        assertFalse(InetAddressPattern.from(pattern).test(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "0.0.0.0/-1",
        "0.0.0.0/33",
        "10.0.1.0/16",
        "192.0.81.0/22",
        "[::]/129",
        "10.0.0.2-10.0.0.1",
        "10.0.0.1-[::1]",
        "10.0.0-256.1",
        "[:::1]"
    })
    public void testInvalidPattern(String pattern)
    {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> InetAddressPattern.from(pattern));
        assertThat(failure.getMessage(), containsString(pattern));
    }

    @Test
    public void testObjectContract()
    {
        InetAddressPattern pattern = InetAddressPattern.from("192.0.2.0/24");
        InetAddressPattern same = InetAddressPattern.from("192.0.2.0/24");
        InetAddressPattern different = InetAddressPattern.from("192.0.2.0/25");

        assertEquals(pattern, same);
        assertEquals(pattern.hashCode(), same.hashCode());
        assertFalse(pattern.equals(different));
        assertFalse(pattern.equals("192.0.2.0/24"));
        assertEquals("192.0.2.0/24", pattern.toString());
    }
}
