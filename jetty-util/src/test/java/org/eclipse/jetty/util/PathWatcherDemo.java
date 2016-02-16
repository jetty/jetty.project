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

package org.eclipse.jetty.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.PathWatcher.PathWatchEvent;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class PathWatcherDemo implements PathWatcher.Listener
{
    private static final Logger LOG = Log.getLogger(PathWatcherDemo.class);

    public static void main(String[] args)
    {
        List<Path> paths = new ArrayList<>();
        for (String arg : args)
        {
            paths.add(new File(arg).toPath());
        }

        if (paths.isEmpty())
        {
            LOG.warn("No paths specified on command line");
            System.exit(-1);
        }

        PathWatcherDemo demo = new PathWatcherDemo();
        try
        {
            demo.run(paths);
        }
        catch (Throwable t)
        {
            LOG.warn(t);
        }
    }

    public void run(List<Path> paths) throws Exception
    {
        PathWatcher watcher = new PathWatcher();
        //watcher.addListener(new PathWatcherDemo());
        watcher.addListener (new PathWatcher.EventListListener(){

            @Override
            public void onPathWatchEvents(List<PathWatchEvent> events)
            {
               if (events == null)
                   LOG.warn("Null events received");
               if (events.isEmpty())
                   LOG.warn("Empty events received");
               
               LOG.info("Bulk notification received");
               for (PathWatchEvent e:events)
                   onPathWatchEvent(e);
                
            }
            
        });
        
        watcher.setNotifyExistingOnStart(false);

        List<String> excludes = new ArrayList<>();
        excludes.add("glob:*.bak"); // ignore backup files
        excludes.add("regex:^.*/\\~[^/]*$"); // ignore scratch files

        for (Path path : paths)
        {
            if (Files.isDirectory(path))
            {
                PathWatcher.Config config = new PathWatcher.Config(path);
                config.addExcludeHidden();
                config.addExcludes(excludes);
                config.setRecurseDepth(4);
                watcher.watch(config);
            }
            else
            {
                watcher.watch(path);
            }
        }
        watcher.start();
        
        Thread.currentThread().join();
    }

    @Override
    public void onPathWatchEvent(PathWatchEvent event)
    {
        StringBuilder msg = new StringBuilder();
        msg.append("onPathWatchEvent: [");
        msg.append(event.getType());
        msg.append("] ");
        msg.append(event.getPath());
        msg.append(" (count=").append(event.getCount()).append(")");
        if (Files.isRegularFile(event.getPath()))
        {
            try
            {
                String fsize = String.format(" (filesize=%,d)",Files.size(event.getPath()));
                msg.append(fsize);
            }
            catch (IOException e)
            {
                LOG.warn("Unable to get filesize",e);
            }
        }
        LOG.info("{}",msg.toString());
    }
}
