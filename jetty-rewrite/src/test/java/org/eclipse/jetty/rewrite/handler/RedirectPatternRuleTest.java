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

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class RedirectPatternRuleTest extends AbstractRuleTestCase
{
    @BeforeEach
    public void init() throws Exception
    {
        start(false);
    }

    private void assertRedirectResponse(int expectedStatusCode, String expectedLocation) throws IOException
    {
        assertThat("Response status code", _response.getStatus(), is(expectedStatusCode));
        assertThat("Response location", _response.getHeader(HttpHeader.LOCATION.asString()), is(expectedLocation));
    }

    @Test
    public void testGlobPattern() throws IOException
    {
        String location = "http://eclipse.com";

        RedirectPatternRule rule = new RedirectPatternRule();
        rule.setPattern("*");
        rule.setLocation(location);

        rule.apply("/", _request, _response);
        assertRedirectResponse(HttpStatus.FOUND_302, location);
    }

    @Test
    public void testPrefixPattern() throws IOException
    {
        String location = "http://api.company.com/";

        RedirectPatternRule rule = new RedirectPatternRule();
        rule.setPattern("/api/*");
        rule.setLocation(location);
        rule.setStatusCode(HttpStatus.MOVED_PERMANENTLY_301);

        rule.apply("/api/rest?foo=1", _request, _response);
        assertRedirectResponse(HttpStatus.MOVED_PERMANENTLY_301, location);
    }
}
