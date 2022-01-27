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

package org.eclipse.jetty.util.log;

/**
 * Legacy Bridge API to Slf4j
 *
 * @deprecated
 */
@Deprecated
public interface Logger
{
    /**
     * @return the name of this logger
     */
    String getName();

    /**
     * Formats and logs at warn level.
     *
     * @param msg the formatting string
     * @param args the optional arguments
     */
    void warn(String msg, Object... args);

    /**
     * Logs the given Throwable information at warn level
     *
     * @param thrown the Throwable to log
     */
    void warn(Throwable thrown);

    /**
     * Logs the given message at warn level, with Throwable information.
     *
     * @param msg the message to log
     * @param thrown the Throwable to log
     */
    void warn(String msg, Throwable thrown);

    /**
     * Formats and logs at info level.
     *
     * @param msg the formatting string
     * @param args the optional arguments
     */
    void info(String msg, Object... args);

    /**
     * Logs the given Throwable information at info level
     *
     * @param thrown the Throwable to log
     */
    void info(Throwable thrown);

    /**
     * Logs the given message at info level, with Throwable information.
     *
     * @param msg the message to log
     * @param thrown the Throwable to log
     */
    void info(String msg, Throwable thrown);

    /**
     * @return whether the debug level is enabled
     */
    boolean isDebugEnabled();

    /**
     * Mutator used to turn debug on programmatically.
     *
     * @param enabled whether to enable the debug level
     */
    void setDebugEnabled(boolean enabled);

    /**
     * Formats and logs at debug level.
     *
     * @param msg the formatting string
     * @param args the optional arguments
     */
    void debug(String msg, Object... args);

    /**
     * Formats and logs at debug level.
     * avoids autoboxing of integers
     *
     * @param msg the formatting string
     * @param value long value
     */
    void debug(String msg, long value);

    /**
     * Logs the given Throwable information at debug level
     *
     * @param thrown the Throwable to log
     */
    void debug(Throwable thrown);

    /**
     * Logs the given message at debug level, with Throwable information.
     *
     * @param msg the message to log
     * @param thrown the Throwable to log
     */
    void debug(String msg, Throwable thrown);

    /**
     * @param name the name of the logger
     * @return a logger with the given name
     */
    Logger getLogger(String name);

    /**
     * Ignore an exception.
     * <p>This should be used rather than an empty catch block.
     *
     * @param ignored the throwable to log as ignored
     */
    void ignore(Throwable ignored);
}
