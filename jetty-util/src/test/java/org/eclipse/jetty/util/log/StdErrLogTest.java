// ========================================================================
// Copyright (c) 2010-2010 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.util.log;

import junit.framework.TestCase;

/**
 * @author mgorovoy
 * */
public class StdErrLogTest extends TestCase
{
    public void testNullValues()
    {
        StdErrLog log = new StdErrLog();
        log.setDebugEnabled(true);
        log.setHideStacks(true);

        try {
            log.info("Testing info(msg,null,null) - {} {}",null,null);
            log.info("Testing info(msg,null,null) - {}",null,null);
            log.info("Testing info(msg,null,null)",null,null);
            log.info(null,"- Testing","info(null,arg0,arg1)");
            log.info(null,null,null);

            log.debug("Testing debug(msg,null,null) - {} {}",null,null);
            log.debug("Testing debug(msg,null,null) - {}",null,null);
            log.debug("Testing debug(msg,null,null)",null,null);
            log.debug(null,"- Testing","debug(null,arg0,arg1)");
            log.debug(null,null,null);

            log.debug("Testing debug(msg,null)");
            log.debug(null,new Throwable("IGNORE::Testing debug(null,thrw)").fillInStackTrace());

            log.warn("Testing warn(msg,null,null) - {} {}",null,null);
            log.warn("Testing warn(msg,null,null) - {}",null,null);
            log.warn("Testing warn(msg,null,null)",null,null);
            log.warn(null,"- Testing","warn(msg,arg0,arg1)");
            log.warn(null,null,null);

            log.warn("Testing warn(msg,null)");
            log.warn(null,new Throwable("IGNORE::Testing warn(msg,thrw)").fillInStackTrace());
        }
        catch (NullPointerException npe)
        {
            System.err.println(npe);
            npe.printStackTrace();
            assertTrue("NullPointerException in StdErrLog.", false);
        }
    }
}
