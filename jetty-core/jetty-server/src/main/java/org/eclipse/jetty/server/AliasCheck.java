package org.eclipse.jetty.server;

import org.eclipse.jetty.util.resource.Resource;

/**
 * Interface to check aliases.
 */
public interface AliasCheck
{
    /**
     * Check an alias
     *
     * @param pathInContext The path the aliased resource was created for
     * @param resource The aliased resourced
     * @return True if the resource is OK to be served.
     */
    boolean checkAlias(String pathInContext, Resource resource);
}
