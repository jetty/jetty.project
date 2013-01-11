//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
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

    private Random random = new Random();

    HttpTransportOverSPDY httpTransportOverSPDY;

    @Before
    public void setUp() throws Exception
    {
        httpTransportOverSPDY = new HttpTransportOverSPDY(connector, httpConfiguration, endPoint, pushStrategy,
                stream, new Fields());
        when(responseInfo.getStatus()).thenReturn(HttpStatus.OK_200);
        when(stream.getSession()).thenReturn(session);
        when(session.getVersion()).thenReturn(SPDY.V3);
        when(stream.isClosed()).thenReturn(false);
    }

    @Test
    public void testSendWithResponseInfoNullAndContentNullAndLastContentTrue() throws Exception
    {
        ByteBuffer content = null;
        boolean lastContent = true;

        httpTransportOverSPDY.send(null, content, lastContent, callback);
        ArgumentCaptor<ByteBufferDataInfo> dataInfoCaptor = ArgumentCaptor.forClass(ByteBufferDataInfo.class);
        verify(stream, times(1)).data(dataInfoCaptor.capture(), any(Callback.class));
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
        verify(stream, times(1)).data(dataInfoCaptor.capture(), any(Callback.class));
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
        verify(stream, times(1)).data(dataInfoCaptor.capture(), any(Callback.class));
        assertThat("lastContent is true", dataInfoCaptor.getValue().isClose(), is(true));
        assertThat("ByteBuffer is empty", dataInfoCaptor.getValue().length(), is(0));
    }

    @Test
    public void testSendWithResponseInfoNullAndContentNullAndLastContentFalse() throws Exception
    {
        ByteBuffer content = null;
        boolean lastContent = false;
        

        httpTransportOverSPDY.send(null, content, lastContent, callback);
        verify(stream, times(0)).data(any(ByteBufferDataInfo.class), any(Callback.class));
    }

    @Test
    public void testSendWithResponseInfoNullAndContentAndLastContentFalse() throws Exception
    {
        ByteBuffer content = createRandomByteBuffer();
        boolean lastContent = false;
        

        httpTransportOverSPDY.send(null, content, lastContent, callback);
        ArgumentCaptor<ByteBufferDataInfo> dataInfoCaptor = ArgumentCaptor.forClass(ByteBufferDataInfo.class);
        verify(stream, times(1)).data(dataInfoCaptor.capture(), any(Callback.class));
        assertThat("lastContent is false", dataInfoCaptor.getValue().isClose(), is(false));
        assertThat("ByteBuffer is empty", dataInfoCaptor.getValue().length(), is(4096));
    }

    @Test
    public void testSendWithResponseInfoNullAndEmptyContentAndLastContentFalse() throws Exception
    {
        ByteBuffer content = BufferUtil.EMPTY_BUFFER;
        boolean lastContent = false;
        

        httpTransportOverSPDY.send(null, content, lastContent, callback);
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
        verify(stream, times(1)).reply(replyInfoCaptor.capture(), any(Callback.class));
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
        verify(stream, times(1)).reply(replyInfoCaptor.capture(), any(Callback.class));
        assertThat("ReplyInfo close is false", replyInfoCaptor.getValue().isClose(), is(false));

        ArgumentCaptor<ByteBufferDataInfo> dataInfoCaptor = ArgumentCaptor.forClass(ByteBufferDataInfo.class);
        verify(stream, times(1)).data(dataInfoCaptor.capture(), any(Callback.class));
        assertThat("lastContent is true", dataInfoCaptor.getValue().isClose(), is(true));
        assertThat("ByteBuffer length is 4096", dataInfoCaptor.getValue().length(), is(4096));
    }

    @Test
    public void testSendWithResponseInfoAndContentNullAndLastContentFalse() throws Exception
    {
        ByteBuffer content = null;
        boolean lastContent = false;
        

        httpTransportOverSPDY.send(responseInfo, content, lastContent, callback);
        ArgumentCaptor<ReplyInfo> replyInfoCaptor = ArgumentCaptor.forClass(ReplyInfo.class);
        verify(stream, times(1)).reply(replyInfoCaptor.capture(), any(Callback.class));
        assertThat("ReplyInfo close is true", replyInfoCaptor.getValue().isClose(), is(false));

        verify(stream, times(0)).data(any(ByteBufferDataInfo.class), any(Callback.class));
    }

    @Test
    public void testSendWithResponseInfoAndContentAndLastContentFalse() throws Exception
    {
        ByteBuffer content = createRandomByteBuffer();

        boolean lastContent = false;
        

        httpTransportOverSPDY.send(responseInfo, content, lastContent, callback);
        ArgumentCaptor<ReplyInfo> replyInfoCaptor = ArgumentCaptor.forClass(ReplyInfo.class);
        verify(stream, times(1)).reply(replyInfoCaptor.capture(), any(Callback.class));
        assertThat("ReplyInfo close is false", replyInfoCaptor.getValue().isClose(), is(false));

        ArgumentCaptor<ByteBufferDataInfo> dataInfoCaptor = ArgumentCaptor.forClass(ByteBufferDataInfo.class);
        verify(stream, times(1)).data(dataInfoCaptor.capture(), any(Callback.class));
        assertThat("lastContent is false", dataInfoCaptor.getValue().isClose(), is(false));
        assertThat("ByteBuffer length is 4096", dataInfoCaptor.getValue().length(), is(4096));
    }

    @Test
    public void testVerifyThatAStreamIsNotCommittedTwice() throws IOException
    {
        ((StdErrLog)Log.getLogger(HttpTransportOverSPDY.class)).setHideStacks(true);
        ByteBuffer content = createRandomByteBuffer();
        boolean lastContent = false;

        httpTransportOverSPDY.send(responseInfo,content,lastContent, callback);
        ArgumentCaptor<ReplyInfo> replyInfoCaptor = ArgumentCaptor.forClass(ReplyInfo.class);
        verify(stream, times(1)).reply(replyInfoCaptor.capture(), any(Callback.class));
        assertThat("ReplyInfo close is false", replyInfoCaptor.getValue().isClose(), is(false));

        httpTransportOverSPDY.send(HttpGenerator.RESPONSE_500_INFO, null,true);

        verify(stream, times(1)).data(any(DataInfo.class), any(Callback.class));

        ((StdErrLog)Log.getLogger(HttpTransportOverSPDY.class)).setHideStacks(false);
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
