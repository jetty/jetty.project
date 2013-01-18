//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.events;

import static org.hamcrest.Matchers.*;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.common.annotations.BadBinarySignatureSocket;
import org.eclipse.jetty.websocket.common.annotations.BadDuplicateBinarySocket;
import org.eclipse.jetty.websocket.common.annotations.BadDuplicateFrameSocket;
import org.eclipse.jetty.websocket.common.annotations.BadTextSignatureSocket;
import org.eclipse.jetty.websocket.common.annotations.FrameSocket;
import org.eclipse.jetty.websocket.common.annotations.MyEchoBinarySocket;
import org.eclipse.jetty.websocket.common.annotations.MyEchoSocket;
import org.eclipse.jetty.websocket.common.annotations.MyStatelessEchoSocket;
import org.eclipse.jetty.websocket.common.annotations.NoopSocket;
import org.eclipse.jetty.websocket.common.annotations.NotASocket;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.events.EventDriverFactory;
import org.eclipse.jetty.websocket.common.events.EventMethod;
import org.eclipse.jetty.websocket.common.events.EventMethods;
import org.eclipse.jetty.websocket.common.events.ListenerEventDriver;
import org.junit.Assert;
import org.junit.Test;

import examples.AdapterConnectCloseSocket;
import examples.AnnotatedBinaryArraySocket;
import examples.AnnotatedBinaryStreamSocket;
import examples.AnnotatedTextSocket;
import examples.AnnotatedTextStreamSocket;
import examples.ListenerBasicSocket;

public class EventDriverFactoryTest
{
    private void assertHasEventMethod(String message, EventMethod actual)
    {
        Assert.assertThat(message + " EventMethod",actual,notNullValue());

        Assert.assertThat(message + " EventMethod.pojo",actual.pojo,notNullValue());
        Assert.assertThat(message + " EventMethod.method",actual.method,notNullValue());
    }

    private void assertNoEventMethod(String message, EventMethod actual)
    {
        Assert.assertThat(message + "Event method",actual,nullValue());
    }

    /**
     * Test Case for no exceptions and 5 methods (extends WebSocketAdapter)
     */
    @Test
    public void testAdapterConnectCloseSocket()
    {
        EventDriverFactory factory = new EventDriverFactory(WebSocketPolicy.newClientPolicy());
        AdapterConnectCloseSocket socket = new AdapterConnectCloseSocket();
        EventDriver driver = factory.wrap(socket);

        String classId = AdapterConnectCloseSocket.class.getSimpleName();
        Assert.assertThat("EventDriver for " + classId,driver,instanceOf(ListenerEventDriver.class));
    }

    /**
     * Test Case for bad declaration (duplicate OnWebSocketBinary declarations)
     */
    @Test
    public void testAnnotatedBadDuplicateBinarySocket()
    {
        EventDriverFactory factory = new EventDriverFactory(WebSocketPolicy.newClientPolicy());
        try
        {
            // Should toss exception
            factory.getMethods(BadDuplicateBinarySocket.class);
            Assert.fail("Should have thrown " + InvalidWebSocketException.class);
        }
        catch (InvalidWebSocketException e)
        {
            // Validate that we have clear error message to the developer
            Assert.assertThat(e.getMessage(),containsString("Duplicate @OnWebSocketMessage declaration"));
        }
    }

    /**
     * Test Case for bad declaration (duplicate frame type methods)
     */
    @Test
    public void testAnnotatedBadDuplicateFrameSocket()
    {
        EventDriverFactory factory = new EventDriverFactory(WebSocketPolicy.newClientPolicy());
        try
        {
            // Should toss exception
            factory.getMethods(BadDuplicateFrameSocket.class);
            Assert.fail("Should have thrown " + InvalidWebSocketException.class);
        }
        catch (InvalidWebSocketException e)
        {
            // Validate that we have clear error message to the developer
            Assert.assertThat(e.getMessage(),containsString("Duplicate @OnWebSocketFrame"));
        }
    }

    /**
     * Test Case for bad declaration a method with a non-void return type
     */
    @Test
    public void testAnnotatedBadSignature_NonVoidReturn()
    {
        EventDriverFactory factory = new EventDriverFactory(WebSocketPolicy.newClientPolicy());
        try
        {
            // Should toss exception
            factory.getMethods(BadBinarySignatureSocket.class);
            Assert.fail("Should have thrown " + InvalidWebSocketException.class);
        }
        catch (InvalidWebSocketException e)
        {
            // Validate that we have clear error message to the developer
            Assert.assertThat(e.getMessage(),containsString("must be void"));
        }
    }

    /**
     * Test Case for bad declaration a method with a public static method
     */
    @Test
    public void testAnnotatedBadSignature_Static()
    {
        EventDriverFactory factory = new EventDriverFactory(WebSocketPolicy.newClientPolicy());
        try
        {
            // Should toss exception
            factory.getMethods(BadTextSignatureSocket.class);
            Assert.fail("Should have thrown " + InvalidWebSocketException.class);
        }
        catch (InvalidWebSocketException e)
        {
            // Validate that we have clear error message to the developer
            Assert.assertThat(e.getMessage(),containsString("may not be static"));
        }
    }

    /**
     * Test Case for socket for binary array messages
     */
    @Test
    public void testAnnotatedBinaryArraySocket()
    {
        EventDriverFactory factory = new EventDriverFactory(WebSocketPolicy.newClientPolicy());
        EventMethods methods = factory.getMethods(AnnotatedBinaryArraySocket.class);

        String classId = AnnotatedBinaryArraySocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,methods,notNullValue());

        assertHasEventMethod(classId + ".onBinary",methods.onBinary);
        assertHasEventMethod(classId + ".onClose",methods.onClose);
        assertHasEventMethod(classId + ".onConnect",methods.onConnect);
        assertNoEventMethod(classId + ".onException",methods.onException);
        assertNoEventMethod(classId + ".onText",methods.onText);
        assertNoEventMethod(classId + ".onFrame",methods.onFrame);

        Assert.assertFalse(classId + ".onBinary.hasConnection",methods.onBinary.isHasConnection());
        Assert.assertFalse(classId + ".onBinary.isStreaming",methods.onBinary.isStreaming());
    }

    /**
     * Test Case for socket for binary stream messages
     */
    @Test
    public void testAnnotatedBinaryStreamSocket()
    {
        EventDriverFactory factory = new EventDriverFactory(WebSocketPolicy.newClientPolicy());
        EventMethods methods = factory.getMethods(AnnotatedBinaryStreamSocket.class);

        String classId = AnnotatedBinaryStreamSocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,methods,notNullValue());

        assertHasEventMethod(classId + ".onBinary",methods.onBinary);
        assertHasEventMethod(classId + ".onClose",methods.onClose);
        assertHasEventMethod(classId + ".onConnect",methods.onConnect);
        assertNoEventMethod(classId + ".onException",methods.onException);
        assertNoEventMethod(classId + ".onText",methods.onText);
        assertNoEventMethod(classId + ".onFrame",methods.onFrame);

        Assert.assertFalse(classId + ".onBinary.hasConnection",methods.onBinary.isHasConnection());
        Assert.assertTrue(classId + ".onBinary.isStreaming",methods.onBinary.isStreaming());
    }

    /**
     * Test Case for no exceptions and 4 methods (3 methods from parent)
     */
    @Test
    public void testAnnotatedMyEchoBinarySocket()
    {
        EventDriverFactory factory = new EventDriverFactory(WebSocketPolicy.newClientPolicy());
        EventMethods methods = factory.getMethods(MyEchoBinarySocket.class);

        String classId = MyEchoBinarySocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,methods,notNullValue());

        assertHasEventMethod(classId + ".onBinary",methods.onBinary);
        assertHasEventMethod(classId + ".onClose",methods.onClose);
        assertHasEventMethod(classId + ".onConnect",methods.onConnect);
        assertNoEventMethod(classId + ".onException",methods.onException);
        assertHasEventMethod(classId + ".onText",methods.onText);
        assertNoEventMethod(classId + ".onFrame",methods.onFrame);
    }

    /**
     * Test Case for no exceptions and 3 methods
     */
    @Test
    public void testAnnotatedMyEchoSocket()
    {
        EventDriverFactory factory = new EventDriverFactory(WebSocketPolicy.newClientPolicy());
        EventMethods methods = factory.getMethods(MyEchoSocket.class);

        String classId = MyEchoSocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,methods,notNullValue());

        assertNoEventMethod(classId + ".onBinary",methods.onBinary);
        assertHasEventMethod(classId + ".onClose",methods.onClose);
        assertHasEventMethod(classId + ".onConnect",methods.onConnect);
        assertNoEventMethod(classId + ".onException",methods.onException);
        assertHasEventMethod(classId + ".onText",methods.onText);
        assertNoEventMethod(classId + ".onFrame",methods.onFrame);
    }

    /**
     * Test Case for annotated for text messages w/connection param
     */
    @Test
    public void testAnnotatedMyStatelessEchoSocket()
    {
        EventDriverFactory factory = new EventDriverFactory(WebSocketPolicy.newClientPolicy());
        EventMethods methods = factory.getMethods(MyStatelessEchoSocket.class);

        String classId = MyStatelessEchoSocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,methods,notNullValue());

        assertNoEventMethod(classId + ".onBinary",methods.onBinary);
        assertNoEventMethod(classId + ".onClose",methods.onClose);
        assertNoEventMethod(classId + ".onConnect",methods.onConnect);
        assertNoEventMethod(classId + ".onException",methods.onException);
        assertHasEventMethod(classId + ".onText",methods.onText);
        assertNoEventMethod(classId + ".onFrame",methods.onFrame);

        Assert.assertTrue(classId + ".onText.hasConnection",methods.onText.isHasConnection());
        Assert.assertFalse(classId + ".onText.isStreaming",methods.onText.isStreaming());
    }

    /**
     * Test Case for no exceptions and no methods
     */
    @Test
    public void testAnnotatedNoop()
    {
        EventDriverFactory factory = new EventDriverFactory(WebSocketPolicy.newClientPolicy());
        EventMethods methods = factory.getMethods(NoopSocket.class);

        String classId = NoopSocket.class.getSimpleName();

        Assert.assertThat("Methods for " + classId,methods,notNullValue());

        assertNoEventMethod(classId + ".onBinary",methods.onBinary);
        assertNoEventMethod(classId + ".onClose",methods.onClose);
        assertNoEventMethod(classId + ".onConnect",methods.onConnect);
        assertNoEventMethod(classId + ".onException",methods.onException);
        assertNoEventMethod(classId + ".onText",methods.onText);
        assertNoEventMethod(classId + ".onFrame",methods.onFrame);
    }

    /**
     * Test Case for no exceptions and 1 methods
     */
    @Test
    public void testAnnotatedOnFrame()
    {
        EventDriverFactory factory = new EventDriverFactory(WebSocketPolicy.newClientPolicy());
        EventMethods methods = factory.getMethods(FrameSocket.class);

        String classId = FrameSocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,methods,notNullValue());

        assertNoEventMethod(classId + ".onBinary",methods.onBinary);
        assertNoEventMethod(classId + ".onClose",methods.onClose);
        assertNoEventMethod(classId + ".onConnect",methods.onConnect);
        assertNoEventMethod(classId + ".onException",methods.onException);
        assertNoEventMethod(classId + ".onText",methods.onText);
        assertHasEventMethod(classId + ".onFrame",methods.onFrame);
    }

    /**
     * Test Case for socket for simple text messages
     */
    @Test
    public void testAnnotatedTextSocket()
    {
        EventDriverFactory factory = new EventDriverFactory(WebSocketPolicy.newClientPolicy());
        EventMethods methods = factory.getMethods(AnnotatedTextSocket.class);

        String classId = AnnotatedTextSocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,methods,notNullValue());

        assertNoEventMethod(classId + ".onBinary",methods.onBinary);
        assertHasEventMethod(classId + ".onClose",methods.onClose);
        assertHasEventMethod(classId + ".onConnect",methods.onConnect);
        assertNoEventMethod(classId + ".onException",methods.onException);
        assertHasEventMethod(classId + ".onText",methods.onText);
        assertNoEventMethod(classId + ".onFrame",methods.onFrame);

        Assert.assertFalse(classId + ".onText.hasConnection",methods.onText.isHasConnection());
        Assert.assertFalse(classId + ".onText.isStreaming",methods.onText.isStreaming());
    }

    /**
     * Test Case for socket for text stream messages
     */
    @Test
    public void testAnnotatedTextStreamSocket()
    {
        EventDriverFactory factory = new EventDriverFactory(WebSocketPolicy.newClientPolicy());
        EventMethods methods = factory.getMethods(AnnotatedTextStreamSocket.class);

        String classId = AnnotatedTextStreamSocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,methods,notNullValue());

        assertNoEventMethod(classId + ".onBinary",methods.onBinary);
        assertHasEventMethod(classId + ".onClose",methods.onClose);
        assertHasEventMethod(classId + ".onConnect",methods.onConnect);
        assertNoEventMethod(classId + ".onException",methods.onException);
        assertHasEventMethod(classId + ".onText",methods.onText);
        assertNoEventMethod(classId + ".onFrame",methods.onFrame);

        Assert.assertFalse(classId + ".onText.hasConnection",methods.onText.isHasConnection());
        Assert.assertTrue(classId + ".onText.isStreaming",methods.onText.isStreaming());
    }

    /**
     * Test Case for bad declaration (duplicate OnWebSocketBinary declarations)
     */
    @Test
    public void testBadNotASocket()
    {
        EventDriverFactory factory = new EventDriverFactory(WebSocketPolicy.newClientPolicy());
        try
        {
            NotASocket bad = new NotASocket();
            // Should toss exception
            factory.wrap(bad);
        }
        catch (InvalidWebSocketException e)
        {
            // Validate that we have clear error message to the developer
            Assert.assertThat(e.getMessage(),allOf(containsString(WebSocketListener.class.getSimpleName()),containsString(WebSocket.class.getSimpleName())));
        }
    }

    /**
     * Test Case for no exceptions and 5 methods (implement WebSocketListener)
     */
    @Test
    public void testListenerBasicSocket()
    {
        EventDriverFactory factory = new EventDriverFactory(WebSocketPolicy.newClientPolicy());
        ListenerBasicSocket socket = new ListenerBasicSocket();
        EventDriver driver = factory.wrap(socket);

        String classId = ListenerBasicSocket.class.getSimpleName();
        Assert.assertThat("EventDriver for " + classId,driver,instanceOf(ListenerEventDriver.class));
    }
}
