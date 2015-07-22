//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.IOException;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.Before;
import org.junit.Test;

public class MovedPermanentlyPatternRuleTest extends AbstractRuleTestCase
{
    @Before
    public void init() throws Exception
    {
        start(false);
    }

    @Test
    public void testGlobPattern() throws IOException
    {
        MovedPermanentlyPatternRule rule = new MovedPermanentlyPatternRule();
        rule.setPattern("*");

        String location = "http://new.company.com";
        rule.setLocation(location);
        rule.apply("/", _request, _response);
        
        assertThat("Response Status", _response.getStatus(), is(HttpStatus.MOVED_PERMANENTLY_301));
        assertThat("Response Location Header", _response.getHeader(HttpHeader.LOCATION.asString()), is(location));
    }
    
    @Test
    public void testPrefixPattern() throws IOException
    {
        MovedPermanentlyPatternRule rule = new MovedPermanentlyPatternRule();
        rule.setPattern("/api/*");

        String location = "http://api.company.com/";
        rule.setLocation(location);
        rule.apply("/api/docs", _request, _response);
        
        assertThat("Response Status", _response.getStatus(), is(HttpStatus.MOVED_PERMANENTLY_301));
        assertThat("Response Location Header", _response.getHeader(HttpHeader.LOCATION.asString()), is(location));
    }
}
