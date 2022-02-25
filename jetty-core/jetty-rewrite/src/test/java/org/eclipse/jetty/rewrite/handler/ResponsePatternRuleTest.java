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

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;

import org.eclipse.jetty.server.Dispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ResponsePatternRuleTest extends AbstractRuleTestCase
{
    private ResponsePatternRule _rule;

    @BeforeEach
    public void init() throws Exception
    {
        start(false);
        _rule = new ResponsePatternRule();
        _rule.setPattern("/test");
    }

    @Test
    public void testStatusCodeNoMessage() throws IOException
    {
        for (int i = 1; i < 600; i++)
        {
            _rule.setCode("" + i);
            _rule.setMessage(null);
            _rule.apply(null, _request, _response);

            assertEquals(i, _response.getStatus());
            assertNull(_request.getAttribute(Dispatcher.ERROR_MESSAGE));
        }
    }

    @Test
    public void testStatusCodeMessage() throws IOException
    {
        _rule.setCode("499");
        _rule.setMessage("Message 499");
        _rule.apply(null, _request, _response);

        assertEquals(499, _response.getStatus());
        assertEquals("Message 499", _request.getAttribute(Dispatcher.ERROR_MESSAGE));
    }
}
