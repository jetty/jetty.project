// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.security;

import static org.junit.Assert.*;
import static org.hamcrest.CoreMatchers.*;

import java.util.Collection;

import org.junit.Before;
import org.junit.Test;


/* ------------------------------------------------------------ */
/**
 */
public class SecurityHandlerTest
{
    SecurityHandler securityHandler = new ConstraintSecurityHandler();
    HashLoginService _loginService = new HashLoginService("somerealm");

    /* ------------------------------------------------------------ */
    /**
     * @throws java.lang.Exception
     */
    @Before
    public void setUp() throws Exception
    {
        securityHandler.setLoginService(_loginService);
        securityHandler.doStart();
    }

    @Test
    public void test() throws Exception
    {
        
        Collection<Object> beans = securityHandler.getBeans();
        assertThat("2 Beans should have been added (LoginService and Authenticator)",beans.size(),is(2));
        securityHandler.doStop();
        assertThat("All beans should have been removed on doStop()", securityHandler.getBeans().size(), is(0));
    }

}
