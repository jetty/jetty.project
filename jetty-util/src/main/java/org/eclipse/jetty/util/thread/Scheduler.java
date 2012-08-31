package org.eclipse.jetty.util.thread;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.component.LifeCycle;

public interface Scheduler extends LifeCycle
{
    interface Task
    {
        boolean cancel();
    }
    
    Task schedule(Runnable task, long delay, TimeUnit units);
}
