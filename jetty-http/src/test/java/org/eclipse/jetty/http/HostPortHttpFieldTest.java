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

package org.eclipse.jetty.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class HostPortHttpFieldTest
{
    @Test
    public void testSimpleAuthority()
    {
        HostPortHttpField hostPortHttpField = new HostPortHttpField("example.com");
        assertThat(hostPortHttpField.getHost(), is("example.com"));
    }

    @Test
    public void testMultiValueAuthorityField()
    {
        assertThrows(BadMessageException.class, () -> new HostPortHttpField("example.com, a.com, b.com, c.com"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "क्\u200Dष", // Devanagari - [ka क] [virāma ्] [ZWJ] [ṣa ष]
        "ರ\u200D್ಕ", // Kannada - [ra ರ‍] [ZWJ] [virāma ್] [ka ಕ]
        "ශ්\u200Dර", // Sinhala - [śa ශ] [virāma ්] [ZWJ] [ra ර]
        "ണ്\u200D" // Malayam - [Na ണ] [virāma ്] [ZWJ]
    })
    public void testIDNWithZeroWidthJoiner(String idn)
    {
        HostPortHttpField hostPortHttpField = new HostPortHttpField(idn);
        assertThat(hostPortHttpField.getHost(), is(idn));
    }
}
