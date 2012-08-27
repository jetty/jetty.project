package org.eclipse.jetty.util.component;

import java.util.concurrent.Future;

/* ------------------------------------------------------------ */
/* A Lifecycle that can be gracefully shutdown.
 */
public interface Graceful 
{
    public <C> Future<C> shutdown(C c);
}