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

package org.eclipse.jetty.start;

import java.security.Permission;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

public class SystemExitAsException implements TestRule
{
    @SuppressWarnings("serial")
    public static class SystemExitException extends RuntimeException
    {
        public SystemExitException(int status)
        {
            super("Encountered System.exit(" + status + ")");
        }
    }

    private static class NoExitSecurityManager extends SecurityManager
    {
        @Override
        public void checkPermission(Permission perm)
        {
        }

        @Override
        public void checkPermission(Permission perm, Object context)
        {
        }

        @Override
        public void checkExit(int status)
        {
            super.checkExit(status);
            throw new SystemExitException(status);
        }
    }

    @Override
    public Statement apply(final Statement statement, Description description)
    {
        return new Statement()
        {
            @Override
            public void evaluate() throws Throwable
            {
                SecurityManager origSecurityManager = System.getSecurityManager();
                try
                {
                    System.setSecurityManager(new NoExitSecurityManager());
                    statement.evaluate();
                }
                finally
                {
                    System.setSecurityManager(origSecurityManager);
                }
            }
        };
    }
}
