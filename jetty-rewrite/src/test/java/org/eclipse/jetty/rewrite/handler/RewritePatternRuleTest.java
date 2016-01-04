//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.io.IOException;

import org.junit.Before;
import org.junit.Test;

public class RewritePatternRuleTest extends AbstractRuleTestCase
{
    private String[][] _tests =
            {
                    {"/foo/bar", "/", "/replace"},
                    {"/foo/bar", "/*", "/replace/foo/bar"},
                    {"/foo/bar", "/foo/*", "/replace/bar"},
                    {"/foo/bar", "/foo/bar", "/replace"},
                    {"/foo/bar.txt", "*.txt", "/replace"},
                    {"/foo/bar/%20x", "/foo/*", "/replace/bar/%20x"},
            };
    private RewritePatternRule _rule;

    @Before
    public void init() throws Exception
    {
        start(false);
        _rule = new RewritePatternRule();
        _rule.setReplacement("/replace");
    }

    @Test
    public void testMatchAndApplyAndApplyURI() throws IOException
    {
        for (String[] test : _tests)
        {
            _rule.setPattern(test[1]);
            String result = _rule.matchAndApply(test[0], _request, _response);
            assertThat(test[1], test[2], is(result));

            _rule.applyURI(_request, null, result);
            assertThat(_request.getRequestURI(), is(test[2]));
        }
    }

    @Test
    public void testReplacementWithQueryString() throws IOException
    {
        String replacement = "/replace?given=param";
        String[] split = replacement.split("\\?", 2);
        String path = split[0];
        String queryString = split[1];

        RewritePatternRule rewritePatternRule = new RewritePatternRule();
        rewritePatternRule.setPattern("/old/context");
        rewritePatternRule.setReplacement(replacement);

        String result = rewritePatternRule.matchAndApply("/old/context", _request, _response);
        assertThat(result, is(path));

        rewritePatternRule.applyURI(_request, null, result);
        assertThat("queryString matches expected", _request.getQueryString(), is(queryString));
        assertThat("request URI matches expected", _request.getRequestURI(), is(path));

    }

    @Test
    public void testRequestWithQueryString() throws IOException
    {
        String replacement = "/replace";
        String queryString = "request=parameter";
        _request.setURIPathQuery("/old/context");
        _request.setQueryString(queryString);

        RewritePatternRule rewritePatternRule = new RewritePatternRule();
        rewritePatternRule.setPattern("/old/context");
        rewritePatternRule.setReplacement(replacement);

        String result = rewritePatternRule.matchAndApply("/old/context", _request, _response);
        assertThat("result matches expected", result, is(replacement));

        rewritePatternRule.applyURI(_request, null, result);
        assertThat("queryString matches expected", _request.getQueryString(), is(queryString));
        assertThat("request URI matches expected", _request.getRequestURI(), is(replacement));
    }

    @Test
    public void testRequestAndReplacementWithQueryString() throws IOException
    {
        String requestQueryString = "request=parameter";
        String replacement = "/replace?given=param";
        String[] split = replacement.split("\\?", 2);
        String path = split[0];
        String queryString = split[1];
        _request.setURIPathQuery("/old/context");
        _request.setQueryString(requestQueryString);

        RewritePatternRule rewritePatternRule = new RewritePatternRule();
        rewritePatternRule.setPattern("/old/context");
        rewritePatternRule.setReplacement(replacement);

        String result = rewritePatternRule.matchAndApply("/old/context", _request, _response);
        assertThat(result, is(path));

        rewritePatternRule.applyURI(_request, null, result);
        assertThat("queryString matches expected", _request.getQueryString(),
                is(requestQueryString + "&" + queryString));
        assertThat("request URI matches expected", _request.getRequestURI(), is(path));
    }

}
