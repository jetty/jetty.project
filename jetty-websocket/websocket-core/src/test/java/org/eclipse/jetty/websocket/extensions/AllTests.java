package org.eclipse.jetty.websocket.extensions;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
        { DeflateFrameExtensionTest.class, FragmentExtensionTest.class, IdentityExtensionTest.class })
public class AllTests
{
    /* nothing to do here, its all done in the annotations */
}
