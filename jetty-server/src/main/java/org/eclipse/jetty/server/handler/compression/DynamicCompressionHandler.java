package org.eclipse.jetty.server.handler.compression;

import org.eclipse.jetty.server.handler.HandlerWrapper;

/**
 * a generic parent class for all compression handler, including:
 * <ul>
 *     <li>{@link org.eclipse.jetty.server.handler.gzip.GzipHandler}</li>
 * </ul>
 */
public abstract class DynamicCompressionHandler extends HandlerWrapper {
}
