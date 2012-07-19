package org.eclipse.jetty.websocket.server;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
{ org.eclipse.jetty.websocket.server.ab.AllTests.class, DeflateExtensionTest.class, FragmentExtensionTest.class, IdentityExtensionTest.class, LoadTest.class,
        WebSocketInvalidVersionTest.class, WebSocketLoadRFC6455Test.class, WebSocketOverSSLTest.class, WebSocketServletRFCTest.class })
public class AllTests
{
    /* let junit do the rest */
}
