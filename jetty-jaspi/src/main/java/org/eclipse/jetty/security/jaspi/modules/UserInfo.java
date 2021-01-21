//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Arrays;

public class UserInfo
{
    private final String userName;

    private char[] password;

    public UserInfo(String userName, char[] password)
    {
        this.userName = userName;
        this.password = password;
    }

    public String getUserName()
    {
        return userName;
    }

    public char[] getPassword()
    {
        return password;
    }

    public void clearPassword()
    {
        Arrays.fill(password, (char)0);
        password = null;
    }
}
