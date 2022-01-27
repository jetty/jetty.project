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

import org.eclipse.jetty.http.HttpURI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class RewritePatternRuleTest extends AbstractRuleTestCase
{
    // TODO: Parameterize
    private final String[][] _tests =
        {
            {"/foo/bar", "/", "/replace"},
            {"/foo/bar", "/*", "/replace/foo/bar"},
            {"/foo/bar", "/foo/*", "/replace/bar"},
            {"/foo/bar", "/foo/bar", "/replace"},
            {"/foo/bar.txt", "*.txt", "/replace"},
            {"/foo/bar/%20x", "/foo/*", "/replace/bar/%20x"},
        };
    private RewritePatternRule _rule;

    @BeforeEach
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
        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/old/context", null, queryString).asImmutable());

        RewritePatternRule rewritePatternRule = new RewritePatternRule();
        rewritePatternRule.setPattern("/old/context");
        rewritePatternRule.setReplacement(replacement);

        String result = rewritePatternRule.matchAndApply("/old/context", _request, _response);
        assertThat("result matches expected", result, is(replacement));

        rewritePatternRule.applyURI(_request, null, result);
        assertThat("request URI matches expected", _request.getRequestURI(), is(replacement));
        assertThat("queryString matches expected", _request.getQueryString(), is(queryString));
    }

    @Test
    public void testRequestAndReplacementWithQueryString() throws IOException
    {
        String requestQueryString = "request=parameter";
        String replacement = "/replace?given=param";
        String[] split = replacement.split("\\?", 2);
        String path = split[0];
        String queryString = split[1];
        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/old/context", null, requestQueryString).asImmutable());

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
