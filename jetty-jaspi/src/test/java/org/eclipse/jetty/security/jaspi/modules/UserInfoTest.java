//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.security.jaspi.modules;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class UserInfoTest
{

    private String username;

    private char[] password;

    private UserInfo userInfo;

    @Test
    public void testUserInfo()
    {
        // given
        username = "Ravi";
        password = "pass".toCharArray();

        // when
        userInfo = new UserInfo(username, password);

        // then
        assertNotNull("UserInfo must not be null", userInfo);
        assertEquals("Usernames should be equal", username, userInfo.getUserName() );
        assertArrayEquals("Passwords should be equal", password, userInfo.getPassword() );
    }

    @Test
    public void testClearPassword()
    {
        // given
        password = "pass".toCharArray();
        userInfo = new UserInfo("Ravi", password);

        // when
        userInfo.clearPassword();

        // then
        assertNull("Password should be null as we cleared the password", userInfo.getPassword() );
    }
}