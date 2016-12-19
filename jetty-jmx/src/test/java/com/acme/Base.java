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

package com.acme;

import org.eclipse.jetty.util.component.AbstractLifeCycle;

public class Base extends AbstractLifeCycle
{
    String name;
    int value;
    String[] messages;

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the messages.
     */
    public String[] getMessages()
    {
        return messages;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param messages
     *            The messages to set.
     */
    public void setMessages(String[] messages)
    {
        this.messages = messages;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the name.
     */
    public String getName()
    {
        return name;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param name
     *            The name to set.
     */
    public void setName(String name)
    {
        this.name = name;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the value.
     */
    public int getValue()
    {
        return value;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param value
     *            The value to set.
     */
    public void setValue(int value)
    {
        this.value = value;
    }

    /* ------------------------------------------------------------ */
    public void doSomething(int arg)
    {
        System.err.println("doSomething " + arg);
    }

    /* ------------------------------------------------------------ */
    public String findSomething(int arg)
    {
        return ("found " + arg);
    }

}
