package org.eclipse.jetty.websocket.ab;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
{ TestABCase1_1.class, TestABCase1_2.class, TestABCase2.class, TestABCase3.class, TestABCase4.class, TestABCase7_3.class })
public class AllTests
{
    /* nothing to do here, its all done in the annotations */
}
