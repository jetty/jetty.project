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

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class RedirectRegexRuleTest extends AbstractRuleTestCase
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
    public void testLocationWithReplacementGroupEmpty() throws IOException
    {
        RedirectRegexRule rule = new RedirectRegexRule("/my/dir/file/(.*)$", "http://www.mortbay.org/$1");

        // Resource is dir
        rule.matchAndApply("/my/dir/file/", _request, _response);
        assertRedirectResponse(HttpStatus.FOUND_302, "http://www.mortbay.org/");
    }

    @Test
    public void testLocationWithPathReplacement() throws IOException
    {
        RedirectRegexRule rule = new RedirectRegexRule("/documentation/(.*)$", "/docs/$1");

        // Resource is dir
        rule.matchAndApply("/documentation/top.html", _request, _response);
        assertRedirectResponse(HttpStatus.FOUND_302, "http://0.0.0.0/docs/top.html");
    }

    @Test
    public void testLocationWithReplacmentGroupSimple() throws IOException
    {
        RedirectRegexRule rule = new RedirectRegexRule("/my/dir/file/(.*)$", "http://www.mortbay.org/$1");

        // Resource is an image
        rule.matchAndApply("/my/dir/file/image.png", _request, _response);
        assertRedirectResponse(HttpStatus.FOUND_302, "http://www.mortbay.org/image.png");
    }

    @Test
    public void testLocationWithReplacementGroupDeepWithParams() throws IOException
    {
        RedirectRegexRule rule = new RedirectRegexRule("/my/dir/file/(.*)$", "http://www.mortbay.org/$1");

        // Resource is api with parameters
        rule.matchAndApply("/my/dir/file/api/rest/foo?id=100&sort=date", _request, _response);
        assertRedirectResponse(HttpStatus.FOUND_302, "http://www.mortbay.org/api/rest/foo?id=100&sort=date");
    }

    @Test
    public void testMovedPermanently() throws IOException
    {
        RedirectRegexRule rule = new RedirectRegexRule();
        rule.setRegex("/api/(.*)$");
        rule.setLocation("http://api.company.com/$1");
        rule.setStatusCode(HttpStatus.MOVED_PERMANENTLY_301);

        // Resource is api with parameters
        rule.matchAndApply("/api/rest/foo?id=100&sort=date", _request, _response);
        assertRedirectResponse(HttpStatus.MOVED_PERMANENTLY_301, "http://api.company.com/rest/foo?id=100&sort=date");
    }
}
