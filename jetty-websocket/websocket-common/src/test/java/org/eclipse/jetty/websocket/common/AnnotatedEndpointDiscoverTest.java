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

package org.eclipse.jetty.websocket.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.annotations.BadBinarySignatureSocket;
import org.eclipse.jetty.websocket.common.annotations.BadDuplicateBinarySocket;
import org.eclipse.jetty.websocket.common.annotations.BadDuplicateFrameSocket;
import org.eclipse.jetty.websocket.common.annotations.BadTextSignatureSocket;
import org.eclipse.jetty.websocket.common.annotations.FrameSocket;
import org.eclipse.jetty.websocket.common.annotations.MyEchoBinarySocket;
import org.eclipse.jetty.websocket.common.annotations.MyEchoSocket;
import org.eclipse.jetty.websocket.common.annotations.MyStatelessEchoSocket;
import org.eclipse.jetty.websocket.common.annotations.NoopSocket;
import org.eclipse.jetty.websocket.common.io.LocalWebSocketSession;
import org.eclipse.jetty.websocket.common.scopes.SimpleContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TestName;

import examples.AnnotatedBinaryArraySocket;
import examples.AnnotatedBinaryStreamSocket;
import examples.AnnotatedTextSocket;
import examples.AnnotatedTextStreamSocket;

public class AnnotatedEndpointDiscoverTest
{
    private WebSocketContainerScope containerScope = new SimpleContainerScope(WebSocketPolicy.newServerPolicy());

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Rule
    public TestName testname = new TestName();

    public LocalWebSocketSession createSession(Object endpoint)
    {
        return new LocalWebSocketSession(containerScope,testname,endpoint);
    }

    /**
     * Test Case for bad declaration (duplicate OnWebSocketBinary declarations)
     */
    @Test
    public void testAnnotatedBadDuplicateBinarySocket()
    {
        // Should toss exception
        thrown.expect(InvalidWebSocketException.class);
        thrown.expectMessage(containsString("Cannot replace previously assigned Binary Message Handler with "));
        createSession(new BadDuplicateBinarySocket());
    }

    /**
     * Test Case for bad declaration (duplicate frame type methods)
     */
    @Test
    public void testAnnotatedBadDuplicateFrameSocket()
    {
        // Should toss exception
        thrown.expect(InvalidWebSocketException.class);
        thrown.expectMessage(containsString("Duplicate @OnWebSocketFrame"));
        createSession(new BadDuplicateFrameSocket());
    }

    /**
     * Test Case for bad declaration a method with a non-void return type
     */
    @Test
    public void testAnnotatedBadSignature_NonVoidReturn()
    {
        // Should toss exception
        thrown.expect(InvalidWebSocketException.class);
        thrown.expectMessage(containsString("must be void"));
        createSession(new BadBinarySignatureSocket());
    }

    /**
     * Test Case for bad declaration a method with a public static method
     */
    @Test
    public void testAnnotatedBadSignature_Static()
    {
        // Should toss exception
        thrown.expect(InvalidWebSocketException.class);
        thrown.expectMessage(containsString("must not be static"));
        createSession(new BadTextSignatureSocket());
    }

    /**
     * Test Case for socket for binary array messages
     */
    @Test
    public void testAnnotatedBinaryArraySocket()
    {
        LocalWebSocketSession session = createSession(new AnnotatedBinaryArraySocket());

        String classId = AnnotatedBinaryArraySocket.class.getSimpleName();

        assertThat(classId + ".onBinary",session.getOnBinarySink(),notNullValue());
        assertThat(classId + ".onClose",session.getOnCloseFunction(),notNullValue());
        assertThat(classId + ".onConnect",session.getOnOpenFunction(),notNullValue());

        assertThat(classId + ".onException",session.getOnErrorFunction(),nullValue());
        assertThat(classId + ".onText",session.getOnTextSink(),nullValue());
        assertThat(classId + ".onFrame",session.getOnFrameFunction(),nullValue());
    }

    /**
     * Test Case for socket for binary stream messages
     */
    @Test
    public void testAnnotatedBinaryStreamSocket()
    {
        LocalWebSocketSession session = createSession(new AnnotatedBinaryStreamSocket());

        String classId = AnnotatedBinaryStreamSocket.class.getSimpleName();

        assertThat(classId + ".onBinary",session.getOnBinarySink(),notNullValue());
        assertThat(classId + ".onClose",session.getOnCloseFunction(),notNullValue());
        assertThat(classId + ".onConnect",session.getOnOpenFunction(),notNullValue());

        assertThat(classId + ".onException",session.getOnErrorFunction(),nullValue());
        assertThat(classId + ".onText",session.getOnTextSink(),nullValue());
        assertThat(classId + ".onFrame",session.getOnFrameFunction(),nullValue());
    }

    /**
     * Test Case for no exceptions and 4 methods (3 methods from parent)
     */
    @Test
    public void testAnnotatedMyEchoBinarySocket()
    {
        LocalWebSocketSession session = createSession(new MyEchoBinarySocket());

        String classId = MyEchoBinarySocket.class.getSimpleName();

        assertThat(classId + ".onBinary",session.getOnBinarySink(),notNullValue());
        assertThat(classId + ".onClose",session.getOnCloseFunction(),notNullValue());
        assertThat(classId + ".onConnect",session.getOnOpenFunction(),notNullValue());
        assertThat(classId + ".onException",session.getOnErrorFunction(),nullValue());
        assertThat(classId + ".onText",session.getOnTextSink(),notNullValue());
        assertThat(classId + ".onFrame",session.getOnFrameFunction(),nullValue());
    }

    /**
     * Test Case for no exceptions and 3 methods
     */
    @Test
    public void testAnnotatedMyEchoSocket()
    {
        LocalWebSocketSession session = createSession(new MyEchoSocket());

        String classId = MyEchoSocket.class.getSimpleName();

        assertThat(classId + ".onBinary",session.getOnBinarySink(),nullValue());
        assertThat(classId + ".onClose",session.getOnCloseFunction(),notNullValue());
        assertThat(classId + ".onConnect",session.getOnOpenFunction(),notNullValue());
        assertThat(classId + ".onException",session.getOnErrorFunction(),nullValue());
        assertThat(classId + ".onText",session.getOnTextSink(),notNullValue());
        assertThat(classId + ".onFrame",session.getOnFrameFunction(),nullValue());
    }

    /**
     * Test Case for annotated for text messages w/connection param
     */
    @Test
    public void testAnnotatedMyStatelessEchoSocket()
    {
        LocalWebSocketSession session = createSession(new MyStatelessEchoSocket());

        String classId = MyStatelessEchoSocket.class.getSimpleName();

        assertThat(classId + ".onBinary",session.getOnBinarySink(),nullValue());
        assertThat(classId + ".onClose",session.getOnCloseFunction(),nullValue());
        assertThat(classId + ".onConnect",session.getOnOpenFunction(),nullValue());
        assertThat(classId + ".onException",session.getOnErrorFunction(),nullValue());
        assertThat(classId + ".onText",session.getOnTextSink(),notNullValue());
        assertThat(classId + ".onFrame",session.getOnFrameFunction(),nullValue());
    }

    /**
     * Test Case for no exceptions and no methods
     */
    @Test
    public void testAnnotatedNoop()
    {
        LocalWebSocketSession session = createSession(new NoopSocket());

        String classId = NoopSocket.class.getSimpleName();

        assertThat(classId + ".onBinary",session.getOnBinarySink(),nullValue());
        assertThat(classId + ".onClose",session.getOnCloseFunction(),nullValue());
        assertThat(classId + ".onConnect",session.getOnOpenFunction(),nullValue());
        assertThat(classId + ".onException",session.getOnErrorFunction(),nullValue());
        assertThat(classId + ".onText",session.getOnTextSink(),nullValue());
        assertThat(classId + ".onFrame",session.getOnFrameFunction(),nullValue());
    }

    /**
     * Test Case for no exceptions and 1 methods
     */
    @Test
    public void testAnnotatedOnFrame()
    {
        LocalWebSocketSession session = createSession(new FrameSocket());

        String classId = FrameSocket.class.getSimpleName();

        assertThat(classId + ".onBinary",session.getOnBinarySink(),nullValue());
        assertThat(classId + ".onClose",session.getOnCloseFunction(),nullValue());
        assertThat(classId + ".onConnect",session.getOnOpenFunction(),nullValue());
        assertThat(classId + ".onException",session.getOnErrorFunction(),nullValue());
        assertThat(classId + ".onText",session.getOnTextSink(),nullValue());
        assertThat(classId + ".onFrame",session.getOnFrameFunction(),notNullValue());
    }

    /**
     * Test Case for socket for simple text messages
     */
    @Test
    public void testAnnotatedTextSocket()
    {
        LocalWebSocketSession session = createSession(new AnnotatedTextSocket());

        String classId = AnnotatedTextSocket.class.getSimpleName();

        assertThat(classId + ".onBinary",session.getOnBinarySink(),nullValue());
        assertThat(classId + ".onClose",session.getOnCloseFunction(),notNullValue());
        assertThat(classId + ".onConnect",session.getOnOpenFunction(),notNullValue());
        assertThat(classId + ".onException",session.getOnErrorFunction(),notNullValue());
        assertThat(classId + ".onText",session.getOnTextSink(),notNullValue());
        assertThat(classId + ".onFrame",session.getOnFrameFunction(),nullValue());
    }

    /**
     * Test Case for socket for text stream messages
     */
    @Test
    public void testAnnotatedTextStreamSocket()
    {
        LocalWebSocketSession session = createSession(new AnnotatedTextStreamSocket());

        String classId = AnnotatedTextStreamSocket.class.getSimpleName();

        assertThat(classId + ".onBinary",session.getOnBinarySink(),nullValue());
        assertThat(classId + ".onClose",session.getOnCloseFunction(),notNullValue());
        assertThat(classId + ".onConnect",session.getOnOpenFunction(),notNullValue());
        assertThat(classId + ".onException",session.getOnErrorFunction(),nullValue());
        assertThat(classId + ".onText",session.getOnTextSink(),notNullValue());
        assertThat(classId + ".onFrame",session.getOnFrameFunction(),nullValue());
    }
}
