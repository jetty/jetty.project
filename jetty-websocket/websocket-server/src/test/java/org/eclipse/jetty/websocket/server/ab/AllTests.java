package org.eclipse.jetty.websocket.server.ab;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
// @formatter:off
@Suite.SuiteClasses( { 
    TestABCase1.class, 
    TestABCase2.class, 
    TestABCase3.class, 
    TestABCase4.class, 
    TestABCase5.class, 
    TestABCase6.class, 
    TestABCase6_GoodUTF.class,
    TestABCase6_BadUTF.class,
    TestABCase7.class,
    TestABCase7_GoodStatusCodes.class,
    TestABCase7_BadStatusCodes.class,
    TestABCase9.class
})
// @formatter:on
public class AllTests
{
    /* let junit do the rest */
}
