//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.gcloud.session;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * GCloudTestSuite
 *
 * Sets up the gcloud emulator once before running all tests.
 *
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
  ClientCrossContextSessionTest.class,
  ForwardedSessionTest.class,
  ImmediateSaveTest.class,
  ImmortalSessionTest.class,
  InvalidationSessionTest.class,
  LastAccessTimeTest.class,
  LocalSessionScavengingTest.class,
  NewSessionTest.class,
  OrphanedSessionTest.class,
  ReentrantRequestSessionTest.class,
  RemoveSessionTest.class,
  SameNodeLoadTest.class,
  ServerCrossContextSessionTest.class,
  SessionExpiryTest.class,
  SessionInvalidateAndCreateTest.class,
  SessionMigrationTest.class,
  SessionRenewTest.class,
  SessionValueSavingTest.class,
  StopSessionManagerPreserveSessionTest.class
})


public class GCloudTestSuite
{
    public static GCloudSessionTestSupport __testSupport;
    
    
    @BeforeClass
    public static void setUp () throws Exception
    {
        __testSupport = new GCloudSessionTestSupport();
        __testSupport.setUp();
    }
    
    @AfterClass
    public static void tearDown () throws Exception
    {
        __testSupport.tearDown();
    }
}
