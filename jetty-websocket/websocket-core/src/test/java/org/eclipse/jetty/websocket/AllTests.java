package org.eclipse.jetty.websocket;

import org.eclipse.jetty.websocket.driver.EventMethodsCacheTest;
import org.eclipse.jetty.websocket.driver.WebSocketEventDriverTest;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
        { org.eclipse.jetty.websocket.ab.AllTests.class, EventMethodsCacheTest.class, WebSocketEventDriverTest.class,
            org.eclipse.jetty.websocket.extensions.AllTests.class, org.eclipse.jetty.websocket.protocol.AllTests.class, GeneratorParserRoundtripTest.class })
public class AllTests
{
    /* nothing to do here */
}
