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
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.containsString;
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
import org.eclipse.jetty.websocket.common.function.CommonEndpointFunctions;
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
    
    public LocalWebSocketSession createSession(Object endpoint) throws Exception
    {
        LocalWebSocketSession session = new LocalWebSocketSession(containerScope, testname, endpoint);
        session.start();
        return session;
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
        createSession(new BadDuplicateBinarySocket());
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
        createSession(new BadDuplicateFrameSocket());
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
        createSession(new BadBinarySignatureSocket());
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
        createSession(new BadTextSignatureSocket());
    }
    
    /**
     * Test Case for socket for binary array messages
     */
    @Test
    public void testAnnotatedBinaryArraySocket() throws Exception
    {
        LocalWebSocketSession session = createSession(new AnnotatedBinaryArraySocket());
        CommonEndpointFunctions functions = session.getEndpointFunctions();
        
        String classId = AnnotatedBinaryArraySocket.class.getSimpleName();
        
        assertThat(classId + ".onBinary", functions.hasBinarySink(), is(true));
        assertThat(classId + ".onClose", functions.hasOnClose(), is(true));
        assertThat(classId + ".onConnect", functions.hasOnOpen(), is(true));
        
        assertThat(classId + ".onException", functions.hasOnError(), is(false));
        assertThat(classId + ".onText", functions.hasTextSink(), is(false));
        assertThat(classId + ".onFrame", functions.hasOnFrame(), is(false));
    }
    
    /**
     * Test Case for socket for binary stream messages
     */
    @Test
    public void testAnnotatedBinaryStreamSocket() throws Exception
    {
        LocalWebSocketSession session = createSession(new AnnotatedBinaryStreamSocket());
        CommonEndpointFunctions functions = session.getEndpointFunctions();
        
        String classId = AnnotatedBinaryStreamSocket.class.getSimpleName();
        
        assertThat(classId + ".onBinary", functions.hasBinarySink(), is(true));
        assertThat(classId + ".onClose", functions.hasOnClose(), is(true));
        assertThat(classId + ".onConnect", functions.hasOnOpen(), is(true));
        
        assertThat(classId + ".onException", functions.hasOnError(), is(false));
        assertThat(classId + ".onText", functions.hasTextSink(), is(false));
        assertThat(classId + ".onFrame", functions.hasOnFrame(), is(false));
    }
    
    /**
     * Test Case for no exceptions and 4 methods (3 methods from parent)
     */
    @Test
    public void testAnnotatedMyEchoBinarySocket() throws Exception
    {
        LocalWebSocketSession session = createSession(new MyEchoBinarySocket());
        CommonEndpointFunctions functions = session.getEndpointFunctions();
        
        String classId = MyEchoBinarySocket.class.getSimpleName();
        
        assertThat(classId + ".onBinary", functions.hasBinarySink(), is(true));
        assertThat(classId + ".onClose", functions.hasOnClose(), is(true));
        assertThat(classId + ".onConnect", functions.hasOnOpen(), is(true));
        
        assertThat(classId + ".onException", functions.hasOnError(), is(false));
        assertThat(classId + ".onText", functions.hasTextSink(), is(true));
        assertThat(classId + ".onFrame", functions.hasOnFrame(), is(false));
    }
    
    /**
     * Test Case for no exceptions and 3 methods
     */
    @Test
    public void testAnnotatedMyEchoSocket() throws Exception
    {
        LocalWebSocketSession session = createSession(new MyEchoSocket());
        CommonEndpointFunctions functions = session.getEndpointFunctions();
        
        String classId = MyEchoSocket.class.getSimpleName();
        
        assertThat(classId + ".onBinary", functions.hasBinarySink(), is(false));
        assertThat(classId + ".onClose", functions.hasOnClose(), is(true));
        assertThat(classId + ".onConnect", functions.hasOnOpen(), is(true));
        assertThat(classId + ".onException", functions.hasOnError(), is(false));
        assertThat(classId + ".onText", functions.hasTextSink(), is(true));
        assertThat(classId + ".onFrame", functions.hasOnFrame(), is(false));
    }
    
    /**
     * Test Case for annotated for text messages w/connection param
     */
    @Test
    public void testAnnotatedMyStatelessEchoSocket() throws Exception
    {
        LocalWebSocketSession session = createSession(new MyStatelessEchoSocket());
        CommonEndpointFunctions functions = session.getEndpointFunctions();
        
        String classId = MyStatelessEchoSocket.class.getSimpleName();
        
        assertThat(classId + ".onBinary", functions.hasBinarySink(), is(false));
        assertThat(classId + ".onClose", functions.hasOnClose(), is(false));
        assertThat(classId + ".onConnect", functions.hasOnOpen(), is(false));
        assertThat(classId + ".onException", functions.hasOnError(), is(false));
        assertThat(classId + ".onText", functions.hasTextSink(), is(true));
        assertThat(classId + ".onFrame", functions.hasOnFrame(), is(false));
    }
    
    /**
     * Test Case for no exceptions and no methods
     */
    @Test
    public void testAnnotatedNoop() throws Exception
    {
        LocalWebSocketSession session = createSession(new NoopSocket());
        CommonEndpointFunctions functions = session.getEndpointFunctions();
        
        String classId = NoopSocket.class.getSimpleName();
        
        assertThat(classId + ".onBinary", functions.hasBinarySink(), is(false));
        assertThat(classId + ".onClose", functions.hasOnClose(), is(false));
        assertThat(classId + ".onConnect", functions.hasOnOpen(), is(false));
        assertThat(classId + ".onException", functions.hasOnError(), is(false));
        assertThat(classId + ".onText", functions.hasTextSink(), is(false));
        assertThat(classId + ".onFrame", functions.hasOnFrame(), is(false));
    }
    
    /**
     * Test Case for no exceptions and 1 methods
     */
    @Test
    public void testAnnotatedOnFrame() throws Exception
    {
        LocalWebSocketSession session = createSession(new FrameSocket());
        CommonEndpointFunctions functions = session.getEndpointFunctions();
        
        String classId = FrameSocket.class.getSimpleName();
        
        assertThat(classId + ".onBinary", functions.hasBinarySink(), is(false));
        assertThat(classId + ".onClose", functions.hasOnClose(), is(false));
        assertThat(classId + ".onConnect", functions.hasOnOpen(), is(false));
        assertThat(classId + ".onException", functions.hasOnError(), is(false));
        assertThat(classId + ".onText", functions.hasTextSink(), is(false));
        assertThat(classId + ".onFrame", functions.hasOnFrame(), is(true));
    }
    
    /**
     * Test Case for socket for simple text messages
     */
    @Test
    public void testAnnotatedTextSocket() throws Exception
    {
        LocalWebSocketSession session = createSession(new AnnotatedTextSocket());
        CommonEndpointFunctions functions = session.getEndpointFunctions();
        
        String classId = AnnotatedTextSocket.class.getSimpleName();
        
        assertThat(classId + ".onBinary", functions.hasBinarySink(), is(false));
        assertThat(classId + ".onClose", functions.hasOnClose(), is(true));
        assertThat(classId + ".onConnect", functions.hasOnOpen(), is(true));
        assertThat(classId + ".onException", functions.hasOnError(), is(true));
        assertThat(classId + ".onText", functions.hasTextSink(), is(true));
        assertThat(classId + ".onFrame", functions.hasOnFrame(), is(false));
    }
    
    /**
     * Test Case for socket for text stream messages
     */
    @Test
    public void testAnnotatedTextStreamSocket() throws Exception
    {
        LocalWebSocketSession session = createSession(new AnnotatedTextStreamSocket());
        CommonEndpointFunctions functions = session.getEndpointFunctions();
        
        String classId = AnnotatedTextStreamSocket.class.getSimpleName();
        
        assertThat(classId + ".onBinary", functions.hasBinarySink(), is(false));
        assertThat(classId + ".onClose", functions.hasOnClose(), is(true));
        assertThat(classId + ".onConnect", functions.hasOnOpen(), is(true));
        assertThat(classId + ".onException", functions.hasOnError(), is(false));
        assertThat(classId + ".onText", functions.hasTextSink(), is(true));
        assertThat(classId + ".onFrame", functions.hasOnFrame(), is(false));
    }
}
