package org.eclipse.jetty.util.component;



/**
 * <p>A Destroyable is an object which can be destroyed.</p>
 * <p>Typically a Destroyable is a {@link LifeCycle} component that can hold onto
 * resources over multiple start/stop cycles.   A call to destroy will release all
 * resources and will prevent any further start/stop cycles from being successful.</p>
 */
public interface Destroyable
{
    void destroy();
}
