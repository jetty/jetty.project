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

package org.eclipse.jetty.http;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;

public class CookieParserTest
{
    @Test
    public void testNewCookieParser()
    {
        assertThat(CookieParser.newParser(null, CookieCompliance.RFC2965_LEGACY, null), instanceOf(CookieCutter.class));
        assertThat(CookieParser.newParser(null, CookieCompliance.RFC6265_LEGACY, null), instanceOf(CookieCutter.class));
        assertThat(CookieParser.newParser(null, new CookieCompliance("custom", EnumSet.of(CookieCompliance.Violation.COMMA_SEPARATOR, CookieCompliance.Violation.BAD_QUOTES)), null), instanceOf(CookieCutter.class));

        assertThat(CookieParser.newParser(null, CookieCompliance.RFC2965, null), instanceOf(RFC6265CookieParser.class));
        assertThat(CookieParser.newParser(null, CookieCompliance.RFC6265, null), instanceOf(RFC6265CookieParser.class));
        assertThat(CookieParser.newParser(null, CookieCompliance.RFC6265_STRICT, null), instanceOf(RFC6265CookieParser.class));
        assertThat(CookieParser.newParser(null, new CookieCompliance("custom", EnumSet.of(CookieCompliance.Violation.COMMA_SEPARATOR)), null), instanceOf(RFC6265CookieParser.class));
    }
}
