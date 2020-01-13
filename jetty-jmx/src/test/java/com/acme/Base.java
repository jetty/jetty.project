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

package com.acme;

import org.eclipse.jetty.util.component.AbstractLifeCycle;

public class Base extends AbstractLifeCycle
{
    String name;
    int value;
    String[] messages;

    /**
     * @return Returns the messages.
     */
    public String[] getMessages()
    {
        return messages;
    }

    /**
     * @param messages The messages to set.
     */
    public void setMessages(String[] messages)
    {
        this.messages = messages;
    }

    /**
     * @return Returns the name.
     */
    public String getName()
    {
        return name;
    }

    /**
     * @param name The name to set.
     */
    public void setName(String name)
    {
        this.name = name;
    }

    /**
     * @return Returns the value.
     */
    public int getValue()
    {
        return value;
    }

    /**
     * @param value The value to set.
     */
    public void setValue(int value)
    {
        this.value = value;
    }

    public void doSomething(int arg)
    {
        System.err.println("doSomething " + arg);
    }

    public String findSomething(int arg)
    {
        return ("found " + arg);
    }
}
