//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.security;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.security.Credential;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class PropertyUserStoreTest
{
    String testFileDir = "target" + File.separator + "property-user-store-test";
    String testFile = testFileDir + File.separator + "users.txt";

    @Before
    public void before() throws Exception
    {
        File file = new File(testFileDir);
        file.mkdirs();

        writeInitialUsers(testFile);
    }

    @After
    public void after() throws Exception
    {
        File file = new File(testFile);

        file.delete();
    }

    private void writeInitialUsers(String testFile) throws Exception
    {
        try (Writer writer = new BufferedWriter(new FileWriter(testFile)))
        {
            writer.append("tom: tom, roleA\n");
            writer.append("dick: dick, roleB\n");
            writer.append("harry: harry, roleA, roleB\n");
        }
    }

    private void writeAdditionalUser(String testFile) throws Exception
    {
        Thread.sleep(1001);
        try (Writer writer = new BufferedWriter(new FileWriter(testFile,true)))
        {
            writer.append("skip: skip, roleA\n");
        }
    }

    @Test
    public void testPropertyUserStoreLoad() throws Exception
    {
        final AtomicInteger userCount = new AtomicInteger();

        PropertyUserStore store = new PropertyUserStore();

        store.setConfig(testFile);

        store.registerUserListener(new PropertyUserStore.UserListener()
        {

            public void update(String username, Credential credential, String[] roleArray)
            {
                userCount.getAndIncrement();
            }

            public void remove(String username)
            {

            }
        });

        store.start();

        Assert.assertNotNull("Failed to retrieve UserIdentity directly from PropertyUserStore", store.getUserIdentity("tom"));
        Assert.assertNotNull("Failed to retrieve UserIdentity directly from PropertyUserStore", store.getUserIdentity("dick"));
        Assert.assertNotNull("Failed to retrieve UserIdentity directly from PropertyUserStore", store.getUserIdentity("harry"));
        Assert.assertEquals(3,userCount.get());
    }

    @Test
    public void testPropertyUserStoreLoadUpdateUser() throws Exception
    {

        final AtomicInteger userCount = new AtomicInteger();

        final List<String> users = new ArrayList<String>();

        PropertyUserStore store = new PropertyUserStore();
        store.setRefreshInterval(1);
        store.setConfig(testFile);

        store.registerUserListener(new PropertyUserStore.UserListener()
        {
            public void update(String username, Credential credential, String[] roleArray)
            {
                if (!users.contains(username))
                {
                    users.add(username);
                    userCount.getAndIncrement();
                }
            }

            public void remove(String username)
            {

            }
        });

        store.start();
        Assert.assertEquals(3,userCount.get());

        writeAdditionalUser(testFile);

        long start = System.currentTimeMillis();
        while (userCount.get() < 4 && (System.currentTimeMillis() - start) < 10000)
        {
            Thread.sleep(10);
        }

        Assert.assertNotNull("Failed to retrieve UserIdentity from PropertyUserStore directly", store.getUserIdentity("skip"));
        Assert.assertEquals(4,userCount.get());

        Assert.assertTrue(users.contains("skip"));
    }

    @Test
    public void testPropertyUserStoreLoadRemoveUser() throws Exception
    {
        writeAdditionalUser(testFile);

        final AtomicInteger userCount = new AtomicInteger();

        final List<String> users = new ArrayList<String>();

        PropertyUserStore store = new PropertyUserStore();
        store.setRefreshInterval(2);
        store.setConfig(testFile);

        store.registerUserListener(new PropertyUserStore.UserListener()
        {

            public void update(String username, Credential credential, String[] roleArray)
            {
                if (!users.contains(username))
                {
                    users.add(username);
                    userCount.getAndIncrement();
                }
            }

            public void remove(String username)
            {
                users.remove(username);
                userCount.getAndDecrement();
            }
        });

        store.start();

        Assert.assertEquals(4,userCount.get());

        Thread.sleep(2000);
        writeInitialUsers(testFile);
        Thread.sleep(3000);
        Assert.assertEquals(3,userCount.get());
    }

}
