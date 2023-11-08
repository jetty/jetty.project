//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.example;

import java.util.Hashtable;
import javax.naming.Context;
import javax.naming.Name;
import javax.naming.spi.ObjectFactory;

public class MockUserTransactionFactory implements ObjectFactory
{
    /**
     * @param obj The possibly null object containing location or reference 
     * information that can be used in creating an object.
     * @param name The name of this object relative to {@code nameCtx},
     * or null if no name is specified.
     * @param nameCtx The context relative to which the {@code name}
     * parameter is specified, or null if {@code name} is
     * relative to the default initial context.
     * @param environment The possibly null environment that is used in
     * creating the object.
     * @return
     * @throws Exception
     */
    @Override
    public Object getObjectInstance(Object obj, Name name, Context nameCtx, Hashtable<?, ?> environment) throws Exception
    {
        return  new MockUserTransaction();
    }
}
