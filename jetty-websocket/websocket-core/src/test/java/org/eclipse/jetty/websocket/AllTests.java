package org.eclipse.jetty.websocket;

import org.eclipse.jetty.websocket.driver.EventMethodsCacheTest;
import org.eclipse.jetty.websocket.driver.WebSocketEventDriverTest;
import org.eclipse.jetty.websocket.protocol.AcceptHashTest;
import org.eclipse.jetty.websocket.protocol.ClosePayloadParserTest;
import org.eclipse.jetty.websocket.protocol.ParserTest;
import org.eclipse.jetty.websocket.protocol.PingPayloadParserTest;
import org.eclipse.jetty.websocket.protocol.RFC6455ExamplesGeneratorTest;
import org.eclipse.jetty.websocket.protocol.RFC6455ExamplesParserTest;
import org.eclipse.jetty.websocket.protocol.TextPayloadParserTest;
import org.eclipse.jetty.websocket.protocol.WebSocketFrameTest;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
        { org.eclipse.jetty.websocket.ab.AllTests.class, EventMethodsCacheTest.class, WebSocketEventDriverTest.class, AcceptHashTest.class,
            ClosePayloadParserTest.class, ParserTest.class, PingPayloadParserTest.class, RFC6455ExamplesGeneratorTest.class, RFC6455ExamplesParserTest.class,
            TextPayloadParserTest.class, WebSocketFrameTest.class, GeneratorParserRoundtripTest.class })
public class AllTests
{
    /* nothing to do here */
}
