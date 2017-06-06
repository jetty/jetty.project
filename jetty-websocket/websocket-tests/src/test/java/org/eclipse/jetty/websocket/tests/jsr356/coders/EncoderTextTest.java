//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests.jsr356.coders;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertThat;

import org.junit.Test;

/**
 * Test various {@link javax.websocket.Encoder.Text} scenarios
 */
public class EncoderTextTest
{
    @Test
    public void testQuotesEncoder_Direct() throws Exception
    {
        QuotesEncoder encoder = new QuotesEncoder();
        Quotes quotes = QuotesUtil.loadQuote("quotes-ben.txt");
        String result = encoder.encode(quotes);
        assertThat("Result", result, containsString("Author: Benjamin Franklin\n"));
        assertThat("Result", result, containsString("Quote: We must, "));
    }
}
