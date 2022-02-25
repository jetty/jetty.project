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

package org.eclipse.jetty.logging;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

public class CapturedStream extends PrintStream
{
    private final ByteArrayOutputStream test;

    public CapturedStream()
    {
        super(new ByteArrayOutputStream(), true, UTF_8);
        test = (ByteArrayOutputStream)super.out;
    }

    public void assertContains(String expectedString)
    {
        String output = new String(test.toByteArray());
        assertThat(output, containsString(expectedString));
    }

    public void assertNotContains(String unexpectedString)
    {
        String output = new String(test.toByteArray());
        assertThat(output, not(containsString(unexpectedString)));
    }

    @Override
    public String toString()
    {
        return new String(test.toByteArray());
    }
}
