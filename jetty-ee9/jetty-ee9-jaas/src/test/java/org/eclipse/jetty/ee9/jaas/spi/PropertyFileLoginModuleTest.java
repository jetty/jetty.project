//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.jaas.spi;

import java.io.File;
import java.util.Collections;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.AppConfigurationEntry.LoginModuleControlFlag;
import javax.security.auth.login.Configuration;

import org.eclipse.jetty.ee9.jaas.JAASLoginService;
import org.eclipse.jetty.ee9.jaas.PropertyUserStoreManager;
import org.eclipse.jetty.ee9.nested.UserIdentity;
import org.eclipse.jetty.ee9.security.DefaultIdentityService;
import org.eclipse.jetty.ee9.security.PropertyUserStore;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

public class PropertyFileLoginModuleTest
{
    @Test
    public void testPropertyFileLoginModule() throws Exception
    {
        //configure for PropertyFileLoginModule
        File loginProperties = MavenTestingUtils.getTestResourceFile("login.properties");

        Configuration testConfig = new Configuration()
        {
            @Override
            public AppConfigurationEntry[] getAppConfigurationEntry(String name)
            { 
                return new AppConfigurationEntry[]{new AppConfigurationEntry(PropertyFileLoginModule.class.getName(), 
                                                                             LoginModuleControlFlag.REQUIRED,
                                                                             Collections.singletonMap("file", loginProperties.getAbsolutePath()))};
            }
        };

        JAASLoginService ls = new JAASLoginService("foo");
        ls.setCallbackHandlerClass("org.eclipse.jetty.ee9.jaas.callback.DefaultCallbackHandler");
        ls.setIdentityService(new DefaultIdentityService());
        ls.setConfiguration(testConfig);
        ls.start();

        //test that the manager is created when the JAASLoginService starts
        PropertyUserStoreManager mgr = ls.getBean(PropertyUserStoreManager.class);
        assertThat(mgr, notNullValue());

        //test the PropertyFileLoginModule authentication and authorization
        Request request = new Request(null, null);
        UserIdentity uid = ls.login("fred", "pwd", request);
        assertThat(uid.isUserInRole("role1", null), is(true));
        assertThat(uid.isUserInRole("role2", null), is(true));
        assertThat(uid.isUserInRole("role3", null), is(true));
        assertThat(uid.isUserInRole("role4", null), is(false));

        //Test that the PropertyUserStore is created by the PropertyFileLoginModule
        PropertyUserStore store = mgr.getPropertyUserStore(loginProperties.getAbsolutePath());
        assertThat(store, is(notNullValue()));
        assertThat(store.isRunning(), is(true));
        assertThat(store.isHotReload(), is(false));

        //test that the PropertyUserStoreManager is stopped and all PropertyUserStores stopped
        ls.stop();
        assertThat(mgr.isStopped(), is(true));
        assertThat(mgr.getPropertyUserStore(loginProperties.getAbsolutePath()), is(nullValue()));
        assertThat(store.isStopped(), is(true));
    }
}
