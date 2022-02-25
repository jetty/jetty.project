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

package org.eclipse.jetty.websocket.jakarta.tests.coders;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * Test various {@link jakarta.websocket.Encoder.Text} scenarios
 */
public class EncoderTextTest
{
    @Test
    public void testQuotesEncoderDirect() throws Exception
    {
        QuotesEncoder encoder = new QuotesEncoder();
        Quotes quotes = QuotesUtil.loadQuote("quotes-ben.txt");
        String result = encoder.encode(quotes);
        assertThat("Result", result, containsString("Author: Benjamin Franklin\n"));
        assertThat("Result", result, containsString("Quote: We must, "));
    }
}
