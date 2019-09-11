//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.maven.plugin;

import java.io.Console;
import java.util.EventListener;
import java.util.HashSet;
import java.util.Set;


public class ConsoleReader implements Runnable
{
    
    public interface Listener extends EventListener
    {
        public void consoleEvent (String line);
    }

    public Set<ConsoleReader.Listener> listeners = new HashSet<>();
    
    public void addListener(ConsoleReader.Listener listener)
    {
        listeners.add(listener);
    }
    
    public void removeListener(ConsoleReader.Listener listener)
    {
        listeners.remove(listener);
    }
    
    public void run()
    {
        Console console = System.console();
        if (console == null)
            return;

        String line ="";
        while (true && line != null)
        {
            line = console.readLine("%nHit <enter> to redeploy:%n%n");
            if (line != null)
                signalEvent(line);
        }
    }
    
    
    public void signalEvent(String line)
    {
        for (ConsoleReader.Listener l:listeners)
            l.consoleEvent(line);
    }
}