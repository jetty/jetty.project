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

package org.eclipse.jetty.maven.plugin;

import java.io.Console;
import java.util.EventListener;
import java.util.HashSet;
import java.util.Set;

/**
 * ConsoleReader
 * 
 * Reads lines from the System console and supplies them
 * to ConsoleReader.Listeners.
 */
public class ConsoleReader implements Runnable
{
    public interface Listener extends EventListener
    {
        public void consoleEvent(String line);
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

        String line = "";
        while (true && line != null)
        {
            line = console.readLine("%nHit <enter> to redeploy:%n%n");
            if (line != null)
                signalEvent(line);
        }
    }
    
    private void signalEvent(String line)
    {
        for (ConsoleReader.Listener l:listeners)
            l.consoleEvent(line);
    }
}
