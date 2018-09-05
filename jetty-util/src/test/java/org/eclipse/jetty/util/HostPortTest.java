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

package org.eclipse.jetty.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class HostPortTest
{
    private static Stream<Arguments> validAuthorityProvider()
    {
        return Stream.of(
                Arguments.of("", "", null),
                Arguments.of(":80", "", "80"),
                Arguments.of("host", "host", null),
                Arguments.of("host:80", "host", "80"),
                Arguments.of("10.10.10.1", "10.10.10.1", null),
                Arguments.of("10.10.10.1:80", "10.10.10.1", "80"),
                Arguments.of("[0::0::0::1]", "[0::0::0::1]", null),
                Arguments.of("[0::0::0::1]:80", "[0::0::0::1]", "80")
        );
    }

    private static Stream<Arguments> invalidAuthorityProvider()
    {
        return Stream.of(
                null,
                "host:",
                "127.0.0.1:",
                "[0::0::0::0::1]:",
                "host:xxx",
                "127.0.0.1:xxx",
                "[0::0::0::0::1]:xxx",
                "host:-80",
                "127.0.0.1:-80",
                "[0::0::0::0::1]:-80")
                .map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("validAuthorityProvider")
    public void testValidAuthority(String authority, String expectedHost, Integer expectedPort)
    {
        try
        {
            HostPort hostPort = new HostPort(authority);
            assertThat(authority,hostPort.getHost(),is(expectedHost));
            
            if (expectedPort==null)
                assertThat(authority,hostPort.getPort(),is(0));
            else
                assertThat(authority,hostPort.getPort(),is(expectedPort));
        }
        catch (Exception e)
        {
            if (expectedHost!=null)
                e.printStackTrace();
            assertNull(authority,expectedHost);
        }
    }

    @ParameterizedTest
    @MethodSource("invalidAuthorityProvider")
    public void testInvalidAuthority(String authority)
    {
        assertThrows(IllegalArgumentException.class, () -> {
            new HostPort(authority);
        });
    }

}
