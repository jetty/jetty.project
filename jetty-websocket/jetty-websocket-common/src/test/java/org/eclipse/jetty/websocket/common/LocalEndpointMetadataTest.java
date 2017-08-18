//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import org.eclipse.jetty.websocket.common.endpoints.annotated.AnnotatedBinaryArraySocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.AnnotatedBinaryStreamSocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.AnnotatedTextSocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.AnnotatedTextStreamSocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.BadBinarySignatureSocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.BadDuplicateBinarySocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.BadDuplicateFrameSocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.BadTextSignatureSocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.FrameSocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.MyEchoBinarySocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.MyEchoSocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.MyStatelessEchoSocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.NoopSocket;
import org.eclipse.jetty.websocket.common.endpoints.listeners.ListenerBasicSocket;
import org.eclipse.jetty.websocket.common.message.ByteArrayMessageSink;
import org.eclipse.jetty.websocket.common.message.InputStreamMessageSink;
import org.eclipse.jetty.websocket.common.message.ReaderMessageSink;
import org.eclipse.jetty.websocket.common.message.StringMessageSink;
import org.eclipse.jetty.websocket.core.InvalidWebSocketException;
import org.hamcrest.Matcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TestName;

public class LocalEndpointMetadataTest
{
    public static final Matcher<Object> EXISTS = notNullValue();
    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Rule
    public TestName testname = new TestName();

    private LocalEndpointFactory endpointFactory = new LocalEndpointFactory();

    private LocalEndpointMetadata createMetadata(Class<?> endpointClass)
    {
        return endpointFactory.createMetadata(endpointClass);
    }

    @Test
    public void testGood_ListenerBasicSocket()
    {
        LocalEndpointFactory endpointFactory = new LocalEndpointFactory();
        LocalEndpointMetadata metadata = endpointFactory.getMetadata(ListenerBasicSocket.class);
        assertThat("Metadata", metadata, notNullValue());
    }

    /**
     * Test Case for bad declaration (duplicate OnWebSocketBinary declarations)
     */
    @Test
    public void testAnnotatedBadDuplicateBinarySocket() throws Exception
    {
        // Should toss exception
        thrown.expect(InvalidWebSocketException.class);
        thrown.expectMessage(allOf(containsString("Cannot replace previously assigned"), containsString("BINARY Handler")));
        createMetadata(BadDuplicateBinarySocket.class);
    }

    /**
     * Test Case for bad declaration (duplicate frame type methods)
     */
    @Test
    public void testAnnotatedBadDuplicateFrameSocket() throws Exception
    {
        // Should toss exception
        thrown.expect(InvalidWebSocketException.class);
        thrown.expectMessage(containsString("Duplicate @OnWebSocketFrame"));
        createMetadata(BadDuplicateFrameSocket.class);
    }

    /**
     * Test Case for bad declaration a method with a non-void return type
     */
    @Test
    public void testAnnotatedBadSignature_NonVoidReturn() throws Exception
    {
        // Should toss exception
        thrown.expect(InvalidWebSocketException.class);
        thrown.expectMessage(containsString("must be void"));
        createMetadata(BadBinarySignatureSocket.class);
    }

    /**
     * Test Case for bad declaration a method with a public static method
     */
    @Test
    public void testAnnotatedBadSignature_Static() throws Exception
    {
        // Should toss exception
        thrown.expect(InvalidWebSocketException.class);
        thrown.expectMessage(containsString("must not be static"));
        createMetadata(BadTextSignatureSocket.class);
    }

    /**
     * Test Case for socket for binary array messages
     */
    @Test
    public void testAnnotatedBinaryArraySocket() throws Exception
    {
        LocalEndpointMetadata metadata = createMetadata(AnnotatedBinaryArraySocket.class);

        String classId = AnnotatedBinaryArraySocket.class.getSimpleName();

        assertThat(classId + ".binaryHandle", metadata.getBinaryHandle(), EXISTS);
        assertThat(classId + ".binarySink", metadata.getBinarySink(), equalTo(ByteArrayMessageSink.class));

        assertThat(classId + ".textHandle", metadata.getBinaryHandle(), nullValue());
        assertThat(classId + ".textSink", metadata.getBinarySink(), nullValue());

        assertThat(classId + ".openHandle", metadata.getOpenHandle(), EXISTS);
        assertThat(classId + ".closeHandle", metadata.getCloseHandle(), EXISTS);
        assertThat(classId + ".errorHandle", metadata.getErrorHandle(), nullValue());

        assertThat(classId + ".frameHandle", metadata.getFrameHandle(), nullValue());
        assertThat(classId + ".pingHandle", metadata.getPingHandle(), nullValue());
        assertThat(classId + ".pongHandle", metadata.getPongHandle(), nullValue());
    }

    /**
     * Test Case for socket for binary stream messages
     */
    @Test
    public void testAnnotatedBinaryStreamSocket() throws Exception
    {
        LocalEndpointMetadata metadata = createMetadata(AnnotatedBinaryStreamSocket.class);

        String classId = AnnotatedBinaryStreamSocket.class.getSimpleName();

        assertThat(classId + ".binaryHandle", metadata.getBinaryHandle(), EXISTS);
        assertThat(classId + ".binarySink", metadata.getBinarySink(), equalTo(InputStreamMessageSink.class));

        assertThat(classId + ".textHandle", metadata.getBinaryHandle(), nullValue());
        assertThat(classId + ".textSink", metadata.getBinarySink(), nullValue());

        assertThat(classId + ".openHandle", metadata.getOpenHandle(), EXISTS);
        assertThat(classId + ".closeHandle", metadata.getCloseHandle(), EXISTS);
        assertThat(classId + ".errorHandle", metadata.getErrorHandle(), nullValue());

        assertThat(classId + ".frameHandle", metadata.getFrameHandle(), nullValue());
        assertThat(classId + ".pingHandle", metadata.getPingHandle(), nullValue());
        assertThat(classId + ".pongHandle", metadata.getPongHandle(), nullValue());
    }

    /**
     * Test Case for no exceptions and 4 methods (3 methods from parent)
     */
    @Test
    public void testAnnotatedMyEchoBinarySocket() throws Exception
    {
        LocalEndpointMetadata metadata = createMetadata(MyEchoBinarySocket.class);

        String classId = MyEchoBinarySocket.class.getSimpleName();

        assertThat(classId + ".binaryHandle", metadata.getBinaryHandle(), EXISTS);
        assertThat(classId + ".binarySink", metadata.getBinarySink(), equalTo(ByteArrayMessageSink.class));

        assertThat(classId + ".textHandle", metadata.getBinaryHandle(), nullValue());
        assertThat(classId + ".textSink", metadata.getBinarySink(), nullValue());

        assertThat(classId + ".openHandle", metadata.getOpenHandle(), EXISTS);
        assertThat(classId + ".closeHandle", metadata.getCloseHandle(), EXISTS);
        assertThat(classId + ".errorHandle", metadata.getErrorHandle(), nullValue());

        assertThat(classId + ".frameHandle", metadata.getFrameHandle(), nullValue());
        assertThat(classId + ".pingHandle", metadata.getPingHandle(), nullValue());
        assertThat(classId + ".pongHandle", metadata.getPongHandle(), nullValue());
    }

    /**
     * Test Case for no exceptions and 3 methods
     */
    @Test
    public void testAnnotatedMyEchoSocket() throws Exception
    {
        LocalEndpointMetadata metadata = createMetadata(MyEchoSocket.class);

        String classId = MyEchoSocket.class.getSimpleName();

        assertThat(classId + ".binaryHandle", metadata.getBinaryHandle(), nullValue());
        assertThat(classId + ".binarySink", metadata.getBinarySink(), nullValue());

        assertThat(classId + ".textHandle", metadata.getBinaryHandle(), EXISTS);
        assertThat(classId + ".textSink", metadata.getBinarySink(), equalTo(StringMessageSink.class));

        assertThat(classId + ".openHandle", metadata.getOpenHandle(), EXISTS);
        assertThat(classId + ".closeHandle", metadata.getCloseHandle(), EXISTS);
        assertThat(classId + ".errorHandle", metadata.getErrorHandle(), nullValue());

        assertThat(classId + ".frameHandle", metadata.getFrameHandle(), nullValue());
        assertThat(classId + ".pingHandle", metadata.getPingHandle(), nullValue());
        assertThat(classId + ".pongHandle", metadata.getPongHandle(), nullValue());
    }

    /**
     * Test Case for annotated for text messages w/connection param
     */
    @Test
    public void testAnnotatedMyStatelessEchoSocket() throws Exception
    {
        LocalEndpointMetadata metadata = createMetadata(MyStatelessEchoSocket.class);

        String classId = MyStatelessEchoSocket.class.getSimpleName();

        assertThat(classId + ".binaryHandle", metadata.getBinaryHandle(), nullValue());
        assertThat(classId + ".binarySink", metadata.getBinarySink(), nullValue());

        assertThat(classId + ".textHandle", metadata.getBinaryHandle(), EXISTS);
        assertThat(classId + ".textSink", metadata.getBinarySink(), equalTo(StringMessageSink.class));

        assertThat(classId + ".openHandle", metadata.getOpenHandle(), nullValue());
        assertThat(classId + ".closeHandle", metadata.getCloseHandle(), nullValue());
        assertThat(classId + ".errorHandle", metadata.getErrorHandle(), nullValue());

        assertThat(classId + ".frameHandle", metadata.getFrameHandle(), nullValue());
        assertThat(classId + ".pingHandle", metadata.getPingHandle(), nullValue());
        assertThat(classId + ".pongHandle", metadata.getPongHandle(), nullValue());
    }

    /**
     * Test Case for no exceptions and no methods
     */
    @Test
    public void testAnnotatedNoop() throws Exception
    {
        LocalEndpointMetadata metadata = createMetadata(NoopSocket.class);

        String classId = NoopSocket.class.getSimpleName();

        assertThat(classId + ".binaryHandle", metadata.getBinaryHandle(), nullValue());
        assertThat(classId + ".binarySink", metadata.getBinarySink(), nullValue());

        assertThat(classId + ".textHandle", metadata.getBinaryHandle(), nullValue());
        assertThat(classId + ".textSink", metadata.getBinarySink(), nullValue());

        assertThat(classId + ".openHandle", metadata.getOpenHandle(), nullValue());
        assertThat(classId + ".closeHandle", metadata.getCloseHandle(), nullValue());
        assertThat(classId + ".errorHandle", metadata.getErrorHandle(), nullValue());

        assertThat(classId + ".frameHandle", metadata.getFrameHandle(), nullValue());
        assertThat(classId + ".pingHandle", metadata.getPingHandle(), nullValue());
        assertThat(classId + ".pongHandle", metadata.getPongHandle(), nullValue());
    }

    /**
     * Test Case for no exceptions and 1 methods
     */
    @Test
    public void testAnnotatedOnFrame() throws Exception
    {
        LocalEndpointMetadata metadata = createMetadata(FrameSocket.class);

        String classId = FrameSocket.class.getSimpleName();

        assertThat(classId + ".binaryHandle", metadata.getBinaryHandle(), nullValue());
        assertThat(classId + ".binarySink", metadata.getBinarySink(), nullValue());

        assertThat(classId + ".textHandle", metadata.getBinaryHandle(), nullValue());
        assertThat(classId + ".textSink", metadata.getBinarySink(), nullValue());

        assertThat(classId + ".openHandle", metadata.getOpenHandle(), nullValue());
        assertThat(classId + ".closeHandle", metadata.getCloseHandle(), nullValue());
        assertThat(classId + ".errorHandle", metadata.getErrorHandle(), nullValue());

        assertThat(classId + ".frameHandle", metadata.getFrameHandle(), EXISTS);
        assertThat(classId + ".pingHandle", metadata.getPingHandle(), nullValue());
        assertThat(classId + ".pongHandle", metadata.getPongHandle(), nullValue());
    }

    /**
     * Test Case for socket for simple text messages
     */
    @Test
    public void testAnnotatedTextSocket() throws Exception
    {
        LocalEndpointMetadata metadata = createMetadata(AnnotatedTextSocket.class);

        String classId = AnnotatedTextSocket.class.getSimpleName();

        assertThat(classId + ".binaryHandle", metadata.getBinaryHandle(), nullValue());
        assertThat(classId + ".binarySink", metadata.getBinarySink(), nullValue());

        assertThat(classId + ".textHandle", metadata.getBinaryHandle(), EXISTS);
        assertThat(classId + ".textSink", metadata.getBinarySink(), equalTo(StringMessageSink.class));

        assertThat(classId + ".openHandle", metadata.getOpenHandle(), EXISTS);
        assertThat(classId + ".closeHandle", metadata.getCloseHandle(), EXISTS);
        assertThat(classId + ".errorHandle", metadata.getErrorHandle(), EXISTS);

        assertThat(classId + ".frameHandle", metadata.getFrameHandle(), nullValue());
        assertThat(classId + ".pingHandle", metadata.getPingHandle(), nullValue());
        assertThat(classId + ".pongHandle", metadata.getPongHandle(), nullValue());
    }

    /**
     * Test Case for socket for text stream messages
     */
    @Test
    public void testAnnotatedTextStreamSocket() throws Exception
    {
        LocalEndpointMetadata metadata = createMetadata(AnnotatedTextStreamSocket.class);

        String classId = AnnotatedTextStreamSocket.class.getSimpleName();

        assertThat(classId + ".binaryHandle", metadata.getBinaryHandle(), nullValue());
        assertThat(classId + ".binarySink", metadata.getBinarySink(), nullValue());

        assertThat(classId + ".textHandle", metadata.getBinaryHandle(), EXISTS);
        assertThat(classId + ".textSink", metadata.getBinarySink(), equalTo(ReaderMessageSink.class));

        assertThat(classId + ".openHandle", metadata.getOpenHandle(), EXISTS);
        assertThat(classId + ".closeHandle", metadata.getCloseHandle(), EXISTS);
        assertThat(classId + ".errorHandle", metadata.getErrorHandle(), nullValue());

        assertThat(classId + ".frameHandle", metadata.getFrameHandle(), nullValue());
        assertThat(classId + ".pingHandle", metadata.getPingHandle(), nullValue());
        assertThat(classId + ".pongHandle", metadata.getPongHandle(), nullValue());
    }
}
