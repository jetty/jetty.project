//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.server.http;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Set;

import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.http.HTTPSPDYHeader;
import org.eclipse.jetty.util.Fields;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ReferrerPushStrategyUnitTest
{
    public static final short VERSION = SPDY.V3;
    public static final String SCHEME = "http";
    public static final String HOST = "localhost";
    public static final String MAIN_URI = "/index.html";
    public static final String METHOD = "GET";

    // class under test
    private ReferrerPushStrategy referrerPushStrategy = new ReferrerPushStrategy();

    @Mock
    Stream stream;
    @Mock
    Session session;


    @Before
    public void setup()
    {
        referrerPushStrategy.setUserAgentBlacklist(Arrays.asList(".*(?i)firefox/16.*"));
    }

    @Test
    public void testReferrerCallsAfterTimeoutAreNotAddedAsPushResources() throws InterruptedException
    {
        Fields requestHeaders = getBaseHeaders(VERSION);
        int referrerCallTimeout = 1000;
        referrerPushStrategy.setReferrerPushPeriod(referrerCallTimeout);
        setMockExpectations();

        String referrerUrl = fillPushStrategyCache(requestHeaders);

        // sleep to pretend that the user manually clicked on a linked resource instead the browser requesting sub
        // resources immediately
        Thread.sleep(referrerCallTimeout + 1);

        requestHeaders.put(HTTPSPDYHeader.URI.name(VERSION), "image2.jpg");
        requestHeaders.put("referer", referrerUrl);
        Set<String> pushResources = referrerPushStrategy.apply(stream, requestHeaders, new Fields());
        assertThat("pushResources is empty", pushResources.size(), is(0));

        requestHeaders.put(HTTPSPDYHeader.URI.name(VERSION), MAIN_URI);
        pushResources = referrerPushStrategy.apply(stream, requestHeaders, new Fields());
        // as the image2.jpg request has been a link and not a sub resource, we expect that pushResources.size() is
        // still 2
        assertThat("pushResources contains two elements image.jpg and style.css", pushResources.size(), is(2));
    }

    @Test
    public void testUserAgentFilter() throws InterruptedException
    {
        Fields requestHeaders = getBaseHeaders(VERSION);
        setMockExpectations();

        fillPushStrategyCache(requestHeaders);

        Set<String> pushResources = referrerPushStrategy.apply(stream, requestHeaders, new Fields());
        assertThat("pushResources contains two elements image.jpg and style.css as no user-agent header is present",
                pushResources.size(), is(2));

        requestHeaders.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_7_5) AppleWebKit/537.4 (KHTML, like Gecko) Chrome/22.0.1229.94 Safari/537.4");
        pushResources = referrerPushStrategy.apply(stream, requestHeaders, new Fields());
        assertThat("pushResources contains two elements image.jpg and style.css as chrome is not blacklisted",
                pushResources.size(), is(2));

        requestHeaders.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.7; rv:16.0) Gecko/20100101 Firefox/16.0");
        pushResources = referrerPushStrategy.apply(stream, requestHeaders, new Fields());
        assertThat("no resources are returned as we want to filter firefox", pushResources.size(), is(0));
    }

    private Fields getBaseHeaders(short version)
    {
        Fields requestHeaders = new Fields();
        requestHeaders.put(HTTPSPDYHeader.SCHEME.name(version), SCHEME);
        requestHeaders.put(HTTPSPDYHeader.HOST.name(version), HOST);
        requestHeaders.put(HTTPSPDYHeader.URI.name(version), MAIN_URI);
        requestHeaders.put(HTTPSPDYHeader.METHOD.name(version), METHOD);
        return requestHeaders;
    }

    private void setMockExpectations()
    {
        when(stream.getSession()).thenReturn(session);
        when(session.getVersion()).thenReturn(VERSION);
    }

    private String fillPushStrategyCache(Fields requestHeaders)
    {
        Set<String> pushResources = referrerPushStrategy.apply(stream, requestHeaders, new Fields());
        assertThat("pushResources is empty", pushResources.size(), is(0));

        String origin = SCHEME + "://" + HOST;
        String referrerUrl = origin + MAIN_URI;

        requestHeaders.put(HTTPSPDYHeader.URI.name(VERSION), "image.jpg");
        requestHeaders.put("referer", referrerUrl);
        pushResources = referrerPushStrategy.apply(stream, requestHeaders, new Fields());
        assertThat("pushResources is empty", pushResources.size(), is(0));

        requestHeaders.put(HTTPSPDYHeader.URI.name(VERSION), "style.css");
        pushResources = referrerPushStrategy.apply(stream, requestHeaders, new Fields());
        assertThat("pushResources is empty", pushResources.size(), is(0));

        requestHeaders.put(HTTPSPDYHeader.URI.name(VERSION), MAIN_URI);
        pushResources = referrerPushStrategy.apply(stream, requestHeaders, new Fields());
        assertThat("pushResources contains two elements image.jpg and style.css", pushResources.size(), is(2));
        return referrerUrl;
    }
}
