//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.jaas.spi;

import java.io.File;
import java.util.HashMap;
import javax.security.auth.Subject;

import org.eclipse.jetty.jaas.callback.DefaultCallbackHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class PropertyFileLoginModuleTest
{
    @Test
    public void testRoles()
        throws Exception
    {
        File file = MavenTestingUtils.getTestResourceFile("login.properties");
        PropertyFileLoginModule module = new PropertyFileLoginModule();
        Subject subject = new Subject();
        HashMap<String, String> options = new HashMap<>();
        options.put("file", file.getCanonicalPath());
        module.initialize(subject, new DefaultCallbackHandler(), new HashMap<String, String>(), options);
        UserInfo fred = module.getUserInfo("fred");
        assertEquals("fred", fred.getUserName());
        assertThat(fred.getRoleNames(), containsInAnyOrder("role1", "role2", "role3"));
        assertThat(fred.getRoleNames(), not(contains("fred")));
    }
}
