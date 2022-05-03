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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

public class SyntaxTest
{
    @Test
    public void testRequireValidRFC2616TokenGood()
    {
        String[] tokens = {
            "name",
            "",
            null,
            "n.a.m.e",
            "na-me",
            "+name",
            "na*me",
            "na$me",
            "#name"
        };

        for (String token : tokens)
        {
            Syntax.requireValidRFC2616Token(token, "Test Based");
            // No exception should occur here
        }
    }

    @Test
    public void testRequireValidRFC2616TokenBad()
    {
        String[] tokens = {
            "\"name\"",
            "name\t",
            "na me",
            "name\u0082",
            "na\tme",
            "na;me",
            "{name}",
            "[name]",
            "\""
        };

        for (String token : tokens)
        {
            Throwable e = assertThrows(IllegalArgumentException.class,
                    () -> Syntax.requireValidRFC2616Token(token, "Test Based"));
            assertThat("Testing Bad RFC2616 Token [" + token + "]", e.getMessage(),
                    allOf(containsString("Test Based"),
                            containsString("RFC2616")));
        }
    }

    @Test
    public void testRequireValidRFC6265CookieValueGood()
    {
        String[] values = {
            "value",
            "",
            null,
            "val=ue",
            "val-ue",
            "\"value\"",
            "val/ue",
            "v.a.l.u.e"
        };

        for (String value : values)
        {
            Syntax.requireValidRFC6265CookieValue(value);
            // No exception should occur here
        }
    }

    @Test
    public void testRequireValidRFC6265CookieValueBad()
    {
        String[] values = {
            "va\tlue",
            "\t",
            "value\u0000",
            "val\u0082ue",
            "va lue",
            "va;lue",
            "\"value",
            "value\"",
            "val\\ue",
            "val\"ue",
            "\""
        };

        for (String value : values)
        {
            try
            {
                Syntax.requireValidRFC6265CookieValue(value);
                fail("RFC6265 Cookie Value [" + value + "] Should have thrown " + IllegalArgumentException.class.getName());
            }
            catch (IllegalArgumentException e)
            {
                assertThat("Testing Bad RFC6265 Cookie Value [" + value + "]", e.getMessage(), containsString("RFC6265"));
            }
        }
    }
}
