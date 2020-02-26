//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.logging;

import java.io.PrintStream;
import java.util.Objects;
import java.util.TimeZone;

import org.slf4j.event.Level;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.helpers.NormalizedParameters;

public class StdErrAppender implements JettyAppender
{
    /**
     * Configuration keys specific to the StdErrAppender
     */
    public static final String NAME_CONDENSE_KEY = "org.eclipse.jetty.logging.appender.NAME_CONDENSE";
    public static final String THREAD_PADDING_KEY = "org.eclipse.jetty.logging.appender.THREAD_PADDING";
    public static final String MESSAGE_ESCAPE_KEY = "org.eclipse.jetty.logging.appender.MESSAGE_ESCAPE";
    public static final String ZONEID_KEY = "org.eclipse.jetty.logging.appender.ZONE_ID";

    private static final Object[] EMPTY_ARGS = new Object[0];
    private final Timestamp timestamper;

    /**
     * True to have output show condensed logger names, false to use the as defined long names.
     */
    private final boolean condensedNames;

    /**
     * True to have messages escaped for control characters, false to leave messages alone.
     */
    private final boolean escapedMessages;

    /**
     * The fixed size of the thread name to use for output
     */
    private final int threadPadding;

    /**
     * The stream to write logging events to.
     */
    private PrintStream stderr;

    public StdErrAppender(JettyLoggerConfiguration config)
    {
        this(config, System.err);
    }

    public StdErrAppender(JettyLoggerConfiguration config, PrintStream stream)
    {
        this(config, stream, null);
    }

    public StdErrAppender(JettyLoggerConfiguration config, PrintStream stream, TimeZone timeZone)
    {
        Objects.requireNonNull(config, "JettyLoggerConfiguration");
        this.stderr = Objects.requireNonNull(stream, "PrintStream");

        TimeZone tzone = timeZone;
        if (tzone == null)
        {
            tzone = config.getTimeZone(ZONEID_KEY);
            if (tzone == null)
            {
                tzone = TimeZone.getDefault();
            }
        }

        this.timestamper = new Timestamp(tzone);

        this.condensedNames = config.getBoolean(NAME_CONDENSE_KEY, true);
        this.escapedMessages = config.getBoolean(MESSAGE_ESCAPE_KEY, true);
        this.threadPadding = config.getInt(THREAD_PADDING_KEY, -1);
    }

    @Override
    public void emit(JettyLogger logger, Level level, long timestamp, String threadName, String message)
    {
        emit(logger, level, timestamp, threadName, null, message, EMPTY_ARGS);
    }

    @Override
    public void emit(JettyLogger logger, Level level, long timestamp, String threadName, Throwable throwable, String message)
    {
        emit(logger, level, timestamp, threadName, throwable, message, EMPTY_ARGS);
    }

    @Override
    public void emit(JettyLogger logger, Level level, long timestamp, String threadName, String message, Object... argumentArray)
    {
        Throwable cause = NormalizedParameters.getThrowableCandidate(argumentArray);
        emit(logger, level, timestamp, threadName, cause, message, argumentArray);
    }

    @Override
    public void emit(JettyLogger logger, Level level, long timestamp, String threadName, Throwable throwable, String message, Object... argumentArray)
    {
        StringBuilder builder = new StringBuilder(64);
        format(builder, logger, level, timestamp, threadName, throwable, message, argumentArray);
        stderr.println(builder);
    }

    public boolean isCondensedNames()
    {
        return condensedNames;
    }

    public boolean isEscapedMessages()
    {
        return escapedMessages;
    }

    public int getThreadPadding()
    {
        return threadPadding;
    }

    public void setStream(PrintStream stream)
    {
        this.stderr = stream;
    }

    private void format(StringBuilder builder, JettyLogger logger, Level level, long timestamp, String threadName, Throwable throwable, String message, Object... argumentArray)
    {
        Throwable cause = throwable;

        // Timestamp
        timestamper.formatNow(timestamp, builder);

        // Level
        builder.append(':').append(renderedLevel(level));

        // Logger Name
        builder.append(':');
        if (condensedNames)
        {
            builder.append(logger.getCondensedName());
        }
        else
        {
            builder.append(logger.getName());
        }

        // Thread Name
        builder.append(':');
        builder.append(threadName); // TODO: support TAG_PAD configuration
        builder.append(':');

        // Message
        builder.append(' ');

        FormattingTuple ft = MessageFormatter.arrayFormat(message, argumentArray);
        appendEscaped(builder, ft.getMessage());
        if (cause == null)
        {
            cause = ft.getThrowable();
        }

        // Throwable
        if (cause != null)
        {
            if (logger.isHideStacks())
            {
                builder.append(": ").append(cause);
            }
            else
            {
                appendCause(builder, cause, "");
            }
        }
    }

    private String renderedLevel(Level level)
    {
        switch (level)
        {
            case ERROR:  // New for Jetty 10+
                return "ERROR";
            case WARN:
                return "WARN ";
            case INFO:
                return "INFO ";
            case DEBUG:
                return "DEBUG";
            case TRACE: // New for Jetty 10+
                return "TRACE";
            default:
                return "UNKNOWN";
        }
    }

    private void appendCause(StringBuilder builder, Throwable cause, String indent)
    {
        builder.append(System.lineSeparator()).append(indent);
        appendEscaped(builder, cause.toString());
        StackTraceElement[] elements = cause.getStackTrace();
        for (int i = 0; elements != null && i < elements.length; i++)
        {
            builder.append(System.lineSeparator()).append(indent).append("\tat ");
            appendEscaped(builder, elements[i].toString());
        }

        for (Throwable suppressed : cause.getSuppressed())
        {
            builder.append(System.lineSeparator()).append(indent).append("Suppressed: ");
            appendCause(builder, suppressed, "\t|" + indent);
        }

        Throwable by = cause.getCause();
        if (by != null && by != cause)
        {
            builder.append(System.lineSeparator()).append(indent).append("Caused by: ");
            appendCause(builder, by, indent);
        }
    }

    private void appendEscaped(StringBuilder builder, String str)
    {
        if (str == null)
            return;

        if (escapedMessages)
        {
            for (int i = 0; i < str.length(); ++i)
            {
                char c = str.charAt(i);
                if (Character.isISOControl(c))
                {
                    if (c == '\n')
                    {
                        builder.append('|');
                    }
                    else if (c == '\r')
                    {
                        builder.append('<');
                    }
                    else
                    {
                        builder.append('?');
                    }
                }
                else
                {
                    builder.append(c);
                }
            }
        }
        else
            builder.append(str);
    }
}
