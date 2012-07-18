package org.eclipse.jetty.websocket.protocol;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
{ AcceptHashTest.class, ClosePayloadParserTest.class, GeneratorTest.class, ParserTest.class, PingPayloadParserTest.class, RFC6455ExamplesGeneratorTest.class,
        RFC6455ExamplesParserTest.class, TextPayloadParserTest.class, WebSocketFrameTest.class })
public class AllTests
{
    /* allow junit annotations to do the heavy lifting */
}
