//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Dispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    public void testStatusCodeNoReason() throws IOException
    {
        for (int i = 1; i < 400; i++)
        {
            _rule.setCode("" + i);
            _rule.apply(null, _request, _response);

            assertEquals(i, _response.getStatus());
        }
    }

    @Test
    public void testStatusCodeWithReason() throws IOException
    {
        for (int i = 1; i < 400; i++)
        {
            _rule.setCode("" + i);
            _rule.setReason("reason" + i);
            _rule.apply(null, _request, _response);

            assertEquals(i, _response.getStatus());
            assertEquals("reason" + i, _response.getReason());
        }
    }

    @Test
    public void testErrorStatusNoReason() throws IOException
    {
        for (int i = 400; i < 600; i++)
        {
            _rule.setCode("" + i);
            _rule.apply(null, _request, _response);

            assertEquals(i, _response.getStatus());
            assertEquals(HttpStatus.getMessage(i), _request.getAttribute(Dispatcher.ERROR_MESSAGE));
            super.reset();
        }
    }

    @Test
    public void testErrorStatusWithReason() throws IOException
    {
        for (int i = 400; i < 600; i++)
        {
            _rule.setCode("" + i);
            _rule.setReason("reason-" + i);
            _rule.apply(null, _request, _response);

            assertEquals(i, _response.getStatus());
            assertEquals("reason-" + i,  _request.getAttribute(Dispatcher.ERROR_MESSAGE));
            super.reset();
        }
    }
}
