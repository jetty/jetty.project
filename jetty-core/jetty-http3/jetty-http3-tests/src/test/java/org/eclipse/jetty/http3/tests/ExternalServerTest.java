//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.tests;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.transport.HttpClientTransportOverHTTP3;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.util.HostPort;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("external")
public class ExternalServerTest
{
    private static final Logger LOG = LoggerFactory.getLogger(ExternalServerTest.class);

    @Test
    @Tag("external")
    public void testExternalServerWithHttpClient() throws Exception
    {
        HTTP3Client client = new HTTP3Client();
        HttpClientTransportOverHTTP3 transport = new HttpClientTransportOverHTTP3(client);
        HttpClient httpClient = new HttpClient(transport);
        httpClient.start();
        try
        {
            URI uri = URI.create("https://maven-central-eu.storage-download.googleapis.com/maven2/org/apache/maven/maven-parent/38/maven-parent-38.pom");
            ContentResponse response = httpClient.newRequest(uri).send();
            assertThat(response.getContentAsString(), containsString("<artifactId>maven-parent</artifactId>"));
        }
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    @Tag("external")
    public void testExternalServerWithHTTP3Client() throws Exception
    {
        HTTP3Client client = new HTTP3Client();
        client.start();
        try
        {
            HostPort hostPort = new HostPort("maven-central-eu.storage-download.googleapis.com:443");
            Session.Client session = client.connect(new InetSocketAddress(hostPort.getHost(), hostPort.getPort()), new Session.Client.Listener() {})
                .get(5, TimeUnit.SECONDS);

            CountDownLatch requestLatch = new CountDownLatch(1);
            HttpURI uri = HttpURI.from(String.format("https://%s/", hostPort));
            MetaData.Request request = new MetaData.Request(HttpMethod.GET.asString(), uri, HttpVersion.HTTP_3, HttpFields.EMPTY, 0);
            session.newRequest(new HeadersFrame(request, true), new Stream.Client.Listener()
            {
                @Override
                public void onResponse(Stream.Client stream, HeadersFrame frame)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("RESPONSE HEADER = {}", frame);
                    if (frame.isLast())
                    {
                        requestLatch.countDown();
                        return;
                    }
                    stream.demand();
                }

                @Override
                public void onDataAvailable(Stream.Client stream)
                {
                    Stream.Data data = stream.readData();
                    if (LOG.isDebugEnabled())
                        LOG.debug("RESPONSE DATA = {}", data);
                    if (data != null)
                    {
                        data.release();
                        if (data.isLast())
                        {
                            requestLatch.countDown();
                            return;
                        }
                    }
                    stream.demand();
                }

                @Override
                public void onTrailer(Stream.Client stream, HeadersFrame frame)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("RESPONSE TRAILER = {}", frame);
                    requestLatch.countDown();
                }
            });

            assertTrue(requestLatch.await(5, TimeUnit.SECONDS));
        }
        finally
        {
            client.stop();
        }
    }
}
