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

package org.eclipse.jetty.websocket.tests.server.jsr356;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.eclipse.jetty.websocket.tests.LocalServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Test various {@link javax.websocket.Decoder.Text Decoder.Text} / {@link javax.websocket.Encoder.Text Encoder.Text} echo behavior of Java Primitives
 */
@RunWith(Parameterized.class)
public class PrimitivesTextEchoTest
{
    private static final Logger LOG = Log.getLogger(PrimitivesTextEchoTest.class);
    
    public static class BaseSocket
    {
        @OnError
        public void onError(Throwable cause) throws IOException
        {
            LOG.warn("Error", cause);
        }
    }
    
    @ServerEndpoint("/echo/boolean")
    public static class BooleanEchoSocket extends BaseSocket
    {
        @OnMessage
        public boolean onMessage(boolean b) throws IOException
        {
            return b;
        }
    }
    
    @ServerEndpoint("/echo/boolean-obj")
    public static class BooleanObjEchoSocket extends BaseSocket
    {
        @OnMessage
        public Boolean onMessage(Boolean b) throws IOException
        {
            return b;
        }
    }
    
    @ServerEndpoint("/echo/byte")
    public static class ByteEchoSocket extends BaseSocket
    {
        @OnMessage
        public byte onMessage(byte b) throws IOException
        {
            return b;
        }
    }
    
    @ServerEndpoint("/echo/byte-obj")
    public static class ByteObjEchoSocket extends BaseSocket
    {
        @OnMessage
        public byte onMessage(byte b) throws IOException
        {
            return b;
        }
    }
    
    @ServerEndpoint("/echo/char")
    public static class CharacterEchoSocket extends BaseSocket
    {
        @OnMessage
        public char onMessage(char c) throws IOException
        {
            return c;
        }
    }
    
    @ServerEndpoint("/echo/char-obj")
    public static class CharacterObjEchoSocket extends BaseSocket
    {
        @OnMessage
        public Character onMessage(Character c) throws IOException
        {
            return c;
        }
    }
    
    @ServerEndpoint("/echo/double")
    public static class DoubleEchoSocket extends BaseSocket
    {
        @OnMessage
        public double onMessage(double d) throws IOException
        {
            return d;
        }
    }
    
    @ServerEndpoint("/echo/double-obj")
    public static class DoubleObjEchoSocket extends BaseSocket
    {
        @OnMessage
        public Double onMessage(Double d) throws IOException
        {
            return d;
        }
    }
    
    @ServerEndpoint("/echo/float")
    public static class FloatEchoSocket extends BaseSocket
    {
        @OnMessage
        public float onMessage(float f) throws IOException
        {
            return f;
        }
    }
    
    @ServerEndpoint("/echo/float-obj")
    public static class FloatObjEchoSocket extends BaseSocket
    {
        @OnMessage
        public Float onMessage(Float f) throws IOException
        {
            return f;
        }
    }
    
    @ServerEndpoint("/echo/short")
    public static class ShortEchoSocket extends BaseSocket
    {
        @OnMessage
        public short onMessage(short s) throws IOException
        {
            return s;
        }
    }
    
    @ServerEndpoint("/echo/short-obj")
    public static class ShortObjEchoSocket extends BaseSocket
    {
        @OnMessage
        public Short onMessage(Short s) throws IOException
        {
            return s;
        }
    }
    
    @ServerEndpoint("/echo/integer")
    public static class IntegerEchoSocket extends BaseSocket
    {
        @OnMessage
        public int onMessage(int i) throws IOException
        {
            return i;
        }
    }
    
    @ServerEndpoint("/echo/integer-obj")
    public static class IntegerObjEchoSocket extends BaseSocket
    {
        @OnMessage
        public Integer onMessage(Integer i) throws IOException
        {
            return i;
        }
    }
    
    @ServerEndpoint("/echo/long")
    public static class LongEchoSocket extends BaseSocket
    {
        @OnMessage
        public long onMessage(long l) throws IOException
        {
            return l;
        }
    }
    
    @ServerEndpoint("/echo/long-obj")
    public static class LongObjEchoSocket extends BaseSocket
    {
        @OnMessage
        public Long onMessage(Long l) throws IOException
        {
            return l;
        }
    }
    
    @ServerEndpoint("/echo/string")
    public static class StringEchoSocket extends BaseSocket
    {
        @OnMessage
        public String onMessage(String s) throws IOException
        {
            return s;
        }
    }
    
    private static void addCase(List<Object[]> data, Class<?> endpointClass, String sendText, String expectText)
    {
        data.add(new Object[]{endpointClass.getSimpleName(), endpointClass, sendText, expectText});
    }
    
    @Parameterized.Parameters(name = "{0}: {2}")
    public static List<Object[]> data()
    {
        List<Object[]> data = new ArrayList<>();
        
        addCase(data, BooleanEchoSocket.class, "true", "true");
        addCase(data, BooleanEchoSocket.class, "TRUE", "true");
        addCase(data, BooleanEchoSocket.class, "false", "false");
        addCase(data, BooleanEchoSocket.class, "FALSE", "false");
        addCase(data, BooleanEchoSocket.class, "TRue", "true");
        addCase(data, BooleanEchoSocket.class, Boolean.toString(Boolean.TRUE), "true");
        addCase(data, BooleanEchoSocket.class, Boolean.toString(Boolean.FALSE), "false");
        addCase(data, BooleanEchoSocket.class, "Apple", "false");
        
        addCase(data, BooleanObjEchoSocket.class, "true", "true");
        addCase(data, BooleanObjEchoSocket.class, "TRUE", "true");
        addCase(data, BooleanObjEchoSocket.class, "false", "false");
        addCase(data, BooleanObjEchoSocket.class, "FALSE", "false");
        addCase(data, BooleanObjEchoSocket.class, "TRue", "true");
        addCase(data, BooleanObjEchoSocket.class, Boolean.toString(Boolean.TRUE), "true");
        addCase(data, BooleanObjEchoSocket.class, Boolean.toString(Boolean.FALSE), "false");
        addCase(data, BooleanObjEchoSocket.class, "Apple", "false");
        
        addCase(data, ByteEchoSocket.class, Byte.toString((byte)0x00), "0");
        addCase(data, ByteEchoSocket.class, Byte.toString((byte)0x58), "88");
        addCase(data, ByteEchoSocket.class, Byte.toString((byte)0x65), "101");
        addCase(data, ByteEchoSocket.class, Byte.toString(Byte.MAX_VALUE), "127");
        addCase(data, ByteEchoSocket.class, Byte.toString(Byte.MIN_VALUE), "-128");
        
        addCase(data, ByteObjEchoSocket.class, Byte.toString((byte)0x00), "0");
        addCase(data, ByteObjEchoSocket.class, Byte.toString((byte)0x58), "88");
        addCase(data, ByteObjEchoSocket.class, Byte.toString((byte)0x65), "101");
        addCase(data, ByteObjEchoSocket.class, Byte.toString(Byte.MAX_VALUE), "127");
        addCase(data, ByteObjEchoSocket.class, Byte.toString(Byte.MIN_VALUE), "-128");
        
        addCase(data, CharacterEchoSocket.class, Character.toString((char) 40), "(");
        addCase(data, CharacterEchoSocket.class, Character.toString((char) 106), "j");
        addCase(data, CharacterEchoSocket.class, Character.toString((char) 64), "@");
        addCase(data, CharacterEchoSocket.class, Character.toString((char) 0x262f), "\u262f");
    
        addCase(data, CharacterObjEchoSocket.class, Character.toString((char) 40), "(");
        addCase(data, CharacterObjEchoSocket.class, Character.toString((char) 106), "j");
        addCase(data, CharacterObjEchoSocket.class, Character.toString((char) 64), "@");
        addCase(data, CharacterObjEchoSocket.class, Character.toString((char) 0x262f), "\u262f");
    
        addCase(data, DoubleEchoSocket.class, Double.toString(3.1459), "3.1459");
        addCase(data, DoubleEchoSocket.class, Double.toString(123.456), "123.456");
        addCase(data, DoubleEchoSocket.class, Double.toString(55), "55.0");
        addCase(data, DoubleEchoSocket.class, Double.toString(.123), "0.123");
        addCase(data, DoubleEchoSocket.class, Double.toString(Double.MAX_VALUE), Double.toString(Double.MAX_VALUE));
        addCase(data, DoubleEchoSocket.class, Double.toString(Double.MIN_VALUE), Double.toString(Double.MIN_VALUE));
    
        addCase(data, DoubleObjEchoSocket.class, Double.toString(3.1459), "3.1459");
        addCase(data, DoubleObjEchoSocket.class, Double.toString(123.456), "123.456");
        addCase(data, DoubleObjEchoSocket.class, Double.toString(55), "55.0");
        addCase(data, DoubleObjEchoSocket.class, Double.toString(.123), "0.123");
        addCase(data, DoubleObjEchoSocket.class, Double.toString(Double.MAX_VALUE), Double.toString(Double.MAX_VALUE));
        addCase(data, DoubleObjEchoSocket.class, Double.toString(Double.MIN_VALUE), Double.toString(Double.MIN_VALUE));
    
        addCase(data, FloatEchoSocket.class, Float.toString(3.1459f), "3.1459");
        addCase(data, FloatEchoSocket.class, Float.toString(0.0f), "0.0");
        addCase(data, FloatEchoSocket.class, Float.toString(Float.MAX_VALUE), Float.toString(Float.MAX_VALUE));
        addCase(data, FloatEchoSocket.class, Float.toString(Float.MIN_VALUE), Float.toString(Float.MIN_VALUE));
    
        addCase(data, FloatObjEchoSocket.class, Float.toString(3.1459f), "3.1459");
        addCase(data, FloatObjEchoSocket.class, Float.toString(0.0f), "0.0");
        addCase(data, FloatObjEchoSocket.class, Float.toString(Float.MAX_VALUE), Float.toString(Float.MAX_VALUE));
        addCase(data, FloatObjEchoSocket.class, Float.toString(Float.MIN_VALUE), Float.toString(Float.MIN_VALUE));
        
        addCase(data, ShortEchoSocket.class, Short.toString((short) 0), "0");
        addCase(data, ShortEchoSocket.class, Short.toString((short) 30000), "30000");
        addCase(data, ShortEchoSocket.class, Short.toString(Short.MAX_VALUE), Short.toString(Short.MAX_VALUE));
        addCase(data, ShortEchoSocket.class, Short.toString(Short.MIN_VALUE), Short.toString(Short.MIN_VALUE));
        
        addCase(data, ShortObjEchoSocket.class, Short.toString((short) 0), "0");
        addCase(data, ShortObjEchoSocket.class, Short.toString((short) 30000), "30000");
        addCase(data, ShortObjEchoSocket.class, Short.toString(Short.MAX_VALUE), Short.toString(Short.MAX_VALUE));
        addCase(data, ShortObjEchoSocket.class, Short.toString(Short.MIN_VALUE), Short.toString(Short.MIN_VALUE));
    
        addCase(data, IntegerEchoSocket.class, Integer.toString(0), "0");
        addCase(data, IntegerEchoSocket.class, Integer.toString(100_000), "100000");
        addCase(data, IntegerEchoSocket.class, Integer.toString(-2_000_000), "-2000000");
        addCase(data, IntegerEchoSocket.class, Integer.toString(Integer.MAX_VALUE), Integer.toString(Integer.MAX_VALUE));
        addCase(data, IntegerEchoSocket.class, Integer.toString(Integer.MIN_VALUE), Integer.toString(Integer.MIN_VALUE));
    
        addCase(data, IntegerObjEchoSocket.class, Integer.toString(0), "0");
        addCase(data, IntegerObjEchoSocket.class, Integer.toString(100_000), "100000");
        addCase(data, IntegerObjEchoSocket.class, Integer.toString(-2_000_000), "-2000000");
        addCase(data, IntegerObjEchoSocket.class, Integer.toString(Integer.MAX_VALUE), Integer.toString(Integer.MAX_VALUE));
        addCase(data, IntegerObjEchoSocket.class, Integer.toString(Integer.MIN_VALUE), Integer.toString(Integer.MIN_VALUE));
    
        addCase(data, LongEchoSocket.class, Long.toString(0), "0");
        addCase(data, LongEchoSocket.class, Long.toString(100_000), "100000");
        addCase(data, LongEchoSocket.class, Long.toString(-2_000_000), "-2000000");
        addCase(data, LongEchoSocket.class, Long.toString(300_000_000_000l), "300000000000");
        addCase(data, LongEchoSocket.class, Long.toString(Long.MAX_VALUE), Long.toString(Long.MAX_VALUE));
        addCase(data, LongEchoSocket.class, Long.toString(Long.MIN_VALUE), Long.toString(Long.MIN_VALUE));
    
        addCase(data, LongObjEchoSocket.class, Long.toString(0), "0");
        addCase(data, LongObjEchoSocket.class, Long.toString(100_000), "100000");
        addCase(data, LongObjEchoSocket.class, Long.toString(-2_000_000), "-2000000");
        addCase(data, LongObjEchoSocket.class, Long.toString(300_000_000_000l), "300000000000");
        addCase(data, LongObjEchoSocket.class, Long.toString(Long.MAX_VALUE), Long.toString(Long.MAX_VALUE));
        addCase(data, LongObjEchoSocket.class, Long.toString(Long.MIN_VALUE), Long.toString(Long.MIN_VALUE));
    
        addCase(data, StringEchoSocket.class, "Hello World", "Hello World");
        return data;
    }
    
    private static LocalServer server;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new LocalServer()
        {
            @Override
            protected void configureServletContextHandler(ServletContextHandler context) throws Exception
            {
                ServerContainer container = WebSocketServerContainerInitializer.configureContext(context);
                
                container.addEndpoint(BooleanEchoSocket.class);
                container.addEndpoint(BooleanObjEchoSocket.class);
                container.addEndpoint(ByteEchoSocket.class);
                container.addEndpoint(ByteObjEchoSocket.class);
                container.addEndpoint(CharacterEchoSocket.class);
                container.addEndpoint(CharacterObjEchoSocket.class);
                container.addEndpoint(DoubleEchoSocket.class);
                container.addEndpoint(DoubleObjEchoSocket.class);
                container.addEndpoint(FloatEchoSocket.class);
                container.addEndpoint(FloatObjEchoSocket.class);
                container.addEndpoint(ShortEchoSocket.class);
                container.addEndpoint(ShortObjEchoSocket.class);
                container.addEndpoint(IntegerEchoSocket.class);
                container.addEndpoint(IntegerObjEchoSocket.class);
                container.addEndpoint(LongEchoSocket.class);
                container.addEndpoint(LongObjEchoSocket.class);
                container.addEndpoint(StringEchoSocket.class);
            }
        };
        server.start();
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Parameterized.Parameter
    public String endpointClassname;
    
    @Parameterized.Parameter(1)
    public Class<?> endpointClass;
    
    @Parameterized.Parameter(2)
    public String sendText;
    
    @Parameterized.Parameter(3)
    public String expectText;
    
    @Test
    public void testPrimitiveEcho() throws Exception
    {
        String requestPath = endpointClass.getAnnotation(ServerEndpoint.class).value();
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(sendText));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(expectText));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        try (LocalFuzzer session = server.newLocalFuzzer(requestPath))
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
}
