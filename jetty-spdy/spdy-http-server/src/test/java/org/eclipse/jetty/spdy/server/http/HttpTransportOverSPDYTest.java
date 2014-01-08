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
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.http.HTTPSPDYHeader;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class HttpTransportOverSPDYTest
{
    @Mock
    Connector connector;
    @Mock
    HttpConfiguration httpConfiguration;
    @Mock
    EndPoint endPoint;
    @Mock
    PushStrategy pushStrategy;
    @Mock
    Stream stream;
    @Mock
    Callback callback;
    @Mock
    Session session;
    @Mock
    HttpGenerator.ResponseInfo responseInfo;

    Random random = new Random();
    short version = SPDY.V3;

    HttpTransportOverSPDY httpTransportOverSPDY;

    @Before
    public void setUp() throws Exception
    {
        Fields requestHeaders = new Fields();
        requestHeaders.add(HTTPSPDYHeader.METHOD.name(version), "GET");
        when(responseInfo.getStatus()).thenReturn(HttpStatus.OK_200);
        when(stream.getSession()).thenReturn(session);
        when(session.getVersion()).thenReturn(SPDY.V3);
        when(stream.isClosed()).thenReturn(false);
        httpTransportOverSPDY = new HttpTransportOverSPDY(connector, httpConfiguration, endPoint, pushStrategy,
                stream, requestHeaders);
    }

    @Test
    public void testSendWithResponseInfoNullAndContentNullAndLastContentTrue() throws Exception
    {
        ByteBuffer content = null;
        boolean lastContent = true;

        httpTransportOverSPDY.send(null, content, lastContent, callback);
        ArgumentCaptor<ByteBufferDataInfo> dataInfoCaptor = ArgumentCaptor.forClass(ByteBufferDataInfo.class);
        ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
        verify(stream, times(1)).data(dataInfoCaptor.capture(), callbackCaptor.capture());
        callbackCaptor.getValue().succeeded();
        verify(callback, times(1)).succeeded();
        assertThat("lastContent is true", dataInfoCaptor.getValue().isClose(), is(true));
        assertThat("ByteBuffer is empty", dataInfoCaptor.getValue().length(), is(0));
    }

    @Test
    public void testSendWithResponseInfoNullAndContentAndLastContentTrue() throws Exception
    {
        ByteBuffer content = createRandomByteBuffer();

        boolean lastContent = true;

        httpTransportOverSPDY.send(null, content, lastContent, callback);
        ArgumentCaptor<ByteBufferDataInfo> dataInfoCaptor = ArgumentCaptor.forClass(ByteBufferDataInfo.class);
        ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
        verify(stream, times(1)).data(dataInfoCaptor.capture(), callbackCaptor.capture());
        callbackCaptor.getValue().succeeded();
        verify(callback, times(1)).succeeded();
        assertThat("lastContent is true", dataInfoCaptor.getValue().isClose(), is(true));
        assertThat("ByteBuffer length is 4096", dataInfoCaptor.getValue().length(), is(4096));
    }

    @Test
    public void testSendWithResponseInfoNullAndEmptyContentAndLastContentTrue() throws Exception
    {
        ByteBuffer content = BufferUtil.EMPTY_BUFFER;
        boolean lastContent = true;

        httpTransportOverSPDY.send(null, content, lastContent, callback);
        ArgumentCaptor<ByteBufferDataInfo> dataInfoCaptor = ArgumentCaptor.forClass(ByteBufferDataInfo.class);
        ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
        verify(stream, times(1)).data(dataInfoCaptor.capture(), callbackCaptor.capture());
        callbackCaptor.getValue().succeeded();
        verify(callback, times(1)).succeeded();
        assertThat("lastContent is true", dataInfoCaptor.getValue().isClose(), is(true));
        assertThat("ByteBuffer is empty", dataInfoCaptor.getValue().length(), is(0));
    }

    @Test
    public void testSendWithResponseInfoNullAndContentAndLastContentFalse() throws Exception
    {
        ByteBuffer content = createRandomByteBuffer();
        boolean lastContent = false;

        httpTransportOverSPDY.send(null, content, lastContent, callback);
        ArgumentCaptor<ByteBufferDataInfo> dataInfoCaptor = ArgumentCaptor.forClass(ByteBufferDataInfo.class);
        ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
        verify(stream, times(1)).data(dataInfoCaptor.capture(), callbackCaptor.capture());
        callbackCaptor.getValue().succeeded();
        verify(callback, times(1)).succeeded();
        assertThat("lastContent is false", dataInfoCaptor.getValue().isClose(), is(false));
        assertThat("ByteBuffer is empty", dataInfoCaptor.getValue().length(), is(4096));
    }

    @Test(expected = IllegalStateException.class)
    public void testSendWithResponseInfoNullAndContentNullAndLastContentFalse() throws Exception
    {
        ByteBuffer content = null;
        boolean lastContent = false;
        httpTransportOverSPDY.send(null, content, lastContent, callback);
    }

    @Test(expected = IllegalStateException.class)
    public void testSendWithResponseInfoNullAndEmptyContentAndLastContentFalse() throws Exception
    {
        ByteBuffer content = BufferUtil.EMPTY_BUFFER;
        boolean lastContent = false;
        httpTransportOverSPDY.send(null, content, lastContent, callback);
    }

    @Test
    public void testSendWithResponseInfoAndContentNullAndLastContentFalse() throws Exception
    {
        ByteBuffer content = null;
        boolean lastContent = false;

        httpTransportOverSPDY.send(responseInfo, content, lastContent, callback);
        ArgumentCaptor<ReplyInfo> replyInfoCaptor = ArgumentCaptor.forClass(ReplyInfo.class);
        ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
        verify(stream, times(1)).reply(replyInfoCaptor.capture(), callbackCaptor.capture());
        callbackCaptor.getValue().succeeded();
        assertThat("ReplyInfo close is true", replyInfoCaptor.getValue().isClose(), is(false));

        verify(stream, times(0)).data(any(ByteBufferDataInfo.class), any(Callback.class));
        verify(callback, times(1)).succeeded();
    }

    @Test
    public void testSendWithResponseInfoAndContentNullAndLastContentTrue() throws Exception
    {
        ByteBuffer content = null;
        boolean lastContent = true;

        // when stream.isClosed() is called a 2nd time, the reply has closed the stream already
        when(stream.isClosed()).thenReturn(false).thenReturn(true);

        httpTransportOverSPDY.send(responseInfo, content, lastContent, callback);
        ArgumentCaptor<ReplyInfo> replyInfoCaptor = ArgumentCaptor.forClass(ReplyInfo.class);
        ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
        verify(stream, times(1)).reply(replyInfoCaptor.capture(), callbackCaptor.capture());
        callbackCaptor.getValue().succeeded();
        assertThat("ReplyInfo close is true", replyInfoCaptor.getValue().isClose(), is(true));

        verify(callback, times(1)).succeeded();
    }

    @Test
    public void testSendWithResponseInfoAndContentAndLastContentTrue() throws Exception
    {
        ByteBuffer content = createRandomByteBuffer();
        boolean lastContent = true;

        httpTransportOverSPDY.send(responseInfo, content, lastContent, callback);
        ArgumentCaptor<ReplyInfo> replyInfoCaptor = ArgumentCaptor.forClass(ReplyInfo.class);
        ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
        verify(stream, times(1)).reply(replyInfoCaptor.capture(), callbackCaptor.capture());
        callbackCaptor.getValue().succeeded();
        assertThat("ReplyInfo close is false", replyInfoCaptor.getValue().isClose(), is(false));
        ArgumentCaptor<ByteBufferDataInfo> dataInfoCaptor = ArgumentCaptor.forClass(ByteBufferDataInfo.class);
        verify(stream, times(1)).data(dataInfoCaptor.capture(), callbackCaptor.capture());
        callbackCaptor.getValue().succeeded();
        assertThat("lastContent is true", dataInfoCaptor.getValue().isClose(), is(true));
        assertThat("ByteBuffer length is 4096", dataInfoCaptor.getValue().length(), is(4096));
        verify(callback, times(1)).succeeded();
    }

    @Test
    public void testSendWithResponseInfoAndContentAndLastContentFalse() throws Exception
    {
        ByteBuffer content = createRandomByteBuffer();
        boolean lastContent = false;

        httpTransportOverSPDY.send(responseInfo, content, lastContent, callback);
        ArgumentCaptor<ReplyInfo> replyInfoCaptor = ArgumentCaptor.forClass(ReplyInfo.class);
        ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
        verify(stream, times(1)).reply(replyInfoCaptor.capture(), callbackCaptor.capture());
        callbackCaptor.getValue().succeeded();
        assertThat("ReplyInfo close is false", replyInfoCaptor.getValue().isClose(), is(false));

        ArgumentCaptor<ByteBufferDataInfo> dataInfoCaptor = ArgumentCaptor.forClass(ByteBufferDataInfo.class);
        verify(stream, times(1)).data(dataInfoCaptor.capture(), callbackCaptor.capture());
        callbackCaptor.getValue().succeeded();
        assertThat("lastContent is false", dataInfoCaptor.getValue().isClose(), is(false));
        assertThat("ByteBuffer length is 4096", dataInfoCaptor.getValue().length(), is(4096));

        verify(callback, times(1)).succeeded();
    }

    @Test
    public void testVerifyThatAStreamIsNotCommittedTwice() throws IOException, InterruptedException
    {
        final CountDownLatch failedCalledLatch = new CountDownLatch(1);
        ByteBuffer content = createRandomByteBuffer();
        boolean lastContent = false;

        httpTransportOverSPDY.send(responseInfo, content, lastContent, callback);
        ArgumentCaptor<ReplyInfo> replyInfoCaptor = ArgumentCaptor.forClass(ReplyInfo.class);
        verify(stream, times(1)).reply(replyInfoCaptor.capture(), any(Callback.class));
        assertThat("ReplyInfo close is false", replyInfoCaptor.getValue().isClose(), is(false));

        httpTransportOverSPDY.send(HttpGenerator.RESPONSE_500_INFO, null, true, new Callback.Adapter()
        {
            @Override
            public void failed(Throwable x)
            {
                failedCalledLatch.countDown();
            }
        });

        verify(stream, times(1)).data(any(DataInfo.class), any(Callback.class));

        assertThat("callback.failed has been called", failedCalledLatch.await(5, TimeUnit.SECONDS), is(true));
    }

    private ByteBuffer createRandomByteBuffer()
    {
        ByteBuffer content = BufferUtil.allocate(8192);
        BufferUtil.flipToFill(content);
        byte[] randomBytes = new byte[4096];
        random.nextBytes(randomBytes);
        content.put(randomBytes);
        return content;
    }
}
