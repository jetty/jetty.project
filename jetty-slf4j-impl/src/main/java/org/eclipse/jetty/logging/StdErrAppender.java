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

package org.eclipse.jetty.logging;

import java.io.PrintStream;
import java.util.Objects;
import java.util.TimeZone;

import org.slf4j.event.Level;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

public class StdErrAppender implements JettyAppender
{
    /**
     * Configuration keys specific to the StdErrAppender
     */
    static final String NAME_CONDENSE_KEY = "org.eclipse.jetty.logging.appender.NAME_CONDENSE";
    static final String MESSAGE_ALIGN_KEY = "org.eclipse.jetty.logging.appender.MESSAGE_ALIGN";
    static final String MESSAGE_ESCAPE_KEY = "org.eclipse.jetty.logging.appender.MESSAGE_ESCAPE";
    static final String ZONEID_KEY = "org.eclipse.jetty.logging.appender.ZONE_ID";
    private static final String EOL = System.lineSeparator();

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
     * The column to align the start of all messages to
     */
    private final int messageAlignColumn;

    /**
     * The stream to write logging events to.
     */
    private PrintStream stream;

    public StdErrAppender(JettyLoggerConfiguration config)
    {
        this(config, null);
    }

    public StdErrAppender(JettyLoggerConfiguration config, PrintStream stream)
    {
        this(config, stream, null);
    }

    public StdErrAppender(JettyLoggerConfiguration config, PrintStream stream, TimeZone timeZone)
    {
        Objects.requireNonNull(config, "JettyLoggerConfiguration");
        this.stream = stream;

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
        this.messageAlignColumn = config.getInt(MESSAGE_ALIGN_KEY, 0);
    }

    @Override
    public void emit(JettyLogger logger, Level level, long timestamp, String threadName, Throwable throwable, String message, Object... argumentArray)
    {
        StringBuilder builder = new StringBuilder(64);
        format(builder, logger, level, timestamp, threadName, throwable, message, argumentArray);
        if (stream != null)
        {
            stream.println(builder);
        }
        else
        {
            System.err.println(builder);
        }
    }

    public boolean isCondensedNames()
    {
        return condensedNames;
    }

    public boolean isEscapedMessages()
    {
        return escapedMessages;
    }

    public int getMessageAlignColumn()
    {
        return messageAlignColumn;
    }

    public PrintStream getStream()
    {
        return stream;
    }

    public void setStream(PrintStream stream)
    {
        this.stream = stream;
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
        builder.append(threadName);
        builder.append(':');

        // Message
        int padAmount = messageAlignColumn - builder.length();
        if (padAmount > 0)
            builder.append(" ".repeat(padAmount));
        else
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
        builder.append(EOL).append(indent);
        appendEscaped(builder, cause.toString());
        StackTraceElement[] elements = cause.getStackTrace();
        for (int i = 0; elements != null && i < elements.length; i++)
        {
            builder.append(EOL).append(indent).append("\tat ");
            appendEscaped(builder, elements[i].toString());
        }

        for (Throwable suppressed : cause.getSuppressed())
        {
            builder.append(EOL).append(indent).append("Suppressed: ");
            appendCause(builder, suppressed, "\t|" + indent);
        }

        Throwable by = cause.getCause();
        if (by != null && by != cause)
        {
            builder.append(EOL).append(indent).append("Caused by: ");
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
