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

package org.eclipse.jetty.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.thread.AutoLock;

/**
 * RolloverFileOutputStream.
 *
 * <p>
 * This output stream puts content in a file that is rolled over every 24 hours.
 * The filename must include the string "yyyy_mm_dd", which is replaced with the
 * actual date when creating and rolling over the file.
 * </p>
 * <p>
 * Old files are retained for a number of days before being deleted.
 * </p>
 */
public class RolloverFileOutputStream extends OutputStream
{
    static final String YYYY_MM_DD = "yyyy_mm_dd";
    static final String ROLLOVER_FILE_DATE_FORMAT = "yyyy_MM_dd";
    static final String ROLLOVER_FILE_BACKUP_FORMAT = "HHmmssSSS";
    static final int ROLLOVER_FILE_RETAIN_DAYS = 31;
    private static final ScheduledExecutorService __scheduler = Executors.newSingleThreadScheduledExecutor(job ->
    {
        Thread thread = new Thread(job, RolloverFileOutputStream.class.getName());
        thread.setDaemon(true);
        return thread;
    });

    private final AutoLock _lock = new AutoLock();
    private OutputStream _out;
    private ScheduledFuture<?> _rollTask;
    private final SimpleDateFormat _fileBackupFormat;
    private final SimpleDateFormat _fileDateFormat;
    private String _filename;
    private File _file;
    private final boolean _append;
    private final int _retainDays;

    /**
     * @param filename The filename must include the string "yyyy_mm_dd",
     * which is replaced with the actual date when creating and rolling over the file.
     * @throws IOException if unable to create output
     */
    public RolloverFileOutputStream(String filename)
        throws IOException
    {
        this(filename, true, ROLLOVER_FILE_RETAIN_DAYS);
    }

    /**
     * @param filename The filename must include the string "yyyy_mm_dd",
     * which is replaced with the actual date when creating and rolling over the file.
     * @param append If true, existing files will be appended to.
     * @throws IOException if unable to create output
     */
    public RolloverFileOutputStream(String filename, boolean append)
        throws IOException
    {
        this(filename, append, ROLLOVER_FILE_RETAIN_DAYS);
    }

    /**
     * @param filename The filename must include the string "yyyy_mm_dd",
     * which is replaced with the actual date when creating and rolling over the file.
     * @param append If true, existing files will be appended to.
     * @param retainDays The number of days to retain files before deleting them.  0 to retain forever.
     * @throws IOException if unable to create output
     */
    public RolloverFileOutputStream(String filename,
                                    boolean append,
                                    int retainDays)
        throws IOException
    {
        this(filename, append, retainDays, TimeZone.getDefault());
    }

    /**
     * @param filename The filename must include the string "yyyy_mm_dd",
     * which is replaced with the actual date when creating and rolling over the file.
     * @param append If true, existing files will be appended to.
     * @param retainDays The number of days to retain files before deleting them. 0 to retain forever.
     * @param zone the timezone for the output
     * @throws IOException if unable to create output
     */
    public RolloverFileOutputStream(String filename,
                                    boolean append,
                                    int retainDays,
                                    TimeZone zone)
        throws IOException
    {
        this(filename, append, retainDays, zone, null, null, ZonedDateTime.now(zone.toZoneId()));
    }

    /**
     * @param filename The filename must include the string "yyyy_mm_dd",
     * which is replaced with the actual date when creating and rolling over the file.
     * @param append If true, existing files will be appended to.
     * @param retainDays The number of days to retain files before deleting them. 0 to retain forever.
     * @param zone the timezone for the output
     * @param dateFormat The format for the date file substitution. The default is "yyyy_MM_dd". If set to the
     * empty string, the file is rolledover to the same filename, with the current file being renamed to the backup filename.
     * @param backupFormat The format for the file extension of backup files. The default is "HHmmssSSS".
     * @throws IOException if unable to create output
     */
    public RolloverFileOutputStream(String filename,
                                    boolean append,
                                    int retainDays,
                                    TimeZone zone,
                                    String dateFormat,
                                    String backupFormat)
        throws IOException
    {
        this(filename, append, retainDays, zone, dateFormat, backupFormat, ZonedDateTime.now(zone.toZoneId()));
    }

    RolloverFileOutputStream(String filename,
                             boolean append,
                             int retainDays,
                             TimeZone zone,
                             String dateFormat,
                             String backupFormat,
                             ZonedDateTime now)
        throws IOException
    {
        if (dateFormat == null)
            dateFormat = ROLLOVER_FILE_DATE_FORMAT;
        _fileDateFormat = new SimpleDateFormat(dateFormat);

        if (backupFormat == null)
            backupFormat = ROLLOVER_FILE_BACKUP_FORMAT;
        _fileBackupFormat = new SimpleDateFormat(backupFormat);

        _fileBackupFormat.setTimeZone(zone);
        _fileDateFormat.setTimeZone(zone);

        if (filename != null)
        {
            filename = filename.trim();
            if (filename.length() == 0)
                filename = null;
        }
        if (filename == null)
            throw new IllegalArgumentException("Invalid filename");

        _filename = filename;
        _append = append;
        _retainDays = retainDays;

        // Calculate Today's Midnight, based on Configured TimeZone (will be in past, even if by a few milliseconds)
        setFile(now);

        // This will schedule the rollover event to the next midnight
        scheduleNextRollover(now);
    }

    /**
     * Get the "start of day" for the provided DateTime at the zone specified.
     *
     * @param now the date time to calculate from
     * @return start of the day of the date provided
     */
    public static ZonedDateTime toMidnight(ZonedDateTime now)
    {
        return now.toLocalDate().atStartOfDay(now.getZone()).plus(1, ChronoUnit.DAYS);
    }

    private void scheduleNextRollover(ZonedDateTime now)
    {
        // Get tomorrow's midnight based on Configured TimeZone
        ZonedDateTime midnight = toMidnight(now);

        // Schedule next rollover event to occur, based on local machine's Unix Epoch milliseconds
        long delay = midnight.toInstant().toEpochMilli() - now.toInstant().toEpochMilli();
        _rollTask = __scheduler.schedule(this::rollOver, delay, TimeUnit.MILLISECONDS);
    }

    public String getFilename()
    {
        return _filename;
    }

    public String getDatedFilename()
    {
        if (_file == null)
            return null;
        return _file.toString();
    }

    public int getRetainDays()
    {
        return _retainDays;
    }

    void setFile(ZonedDateTime now)
        throws IOException
    {
        File oldFile = null;
        File newFile = null;
        File backupFile = null;
        try (AutoLock l = _lock.lock())
        {
            // Check directory
            File file = new File(_filename);
            _filename = file.getCanonicalPath();
            file = new File(_filename);
            File dir = new File(file.getParent());
            if (!dir.isDirectory() || !dir.canWrite())
                throw new IOException("Cannot write log directory " + dir);

            // Is this a rollover file?
            String filename = file.getName();
            int datePattern = filename.toLowerCase(Locale.ENGLISH).indexOf(YYYY_MM_DD);
            if (datePattern >= 0)
            {
                file = new File(dir,
                    filename.substring(0, datePattern) +
                        _fileDateFormat.format(new Date(now.toInstant().toEpochMilli())) +
                        filename.substring(datePattern + YYYY_MM_DD.length()));
            }

            if (file.exists() && !file.canWrite())
                throw new IOException("Cannot write log file " + file);

            // Do we need to change the output stream?
            if (_out == null || datePattern >= 0)
            {
                // Yep
                oldFile = _file;
                _file = file;
                newFile = _file;

                OutputStream oldOut = _out;
                if (oldOut != null)
                    oldOut.close();

                if (!_append && file.exists())
                {
                    backupFile = new File(file.toString() + "." + _fileBackupFormat.format(new Date(now.toInstant().toEpochMilli())));
                    renameFile(file, backupFile);
                }
                _out = new FileOutputStream(file.toString(), _append);
                //if(log.isDebugEnabled())log.debug("Opened "+_file);
            }
        }

        if (newFile != null)
            rollover(oldFile, backupFile, newFile);
    }

    private void renameFile(File src, File dest) throws IOException
    {
        // Try old school rename
        if (!src.renameTo(dest))
        {
            try
            {
                // Try new move
                Files.move(src.toPath(), dest.toPath());
            }
            catch (IOException e)
            {
                // Copy
                Files.copy(src.toPath(), dest.toPath());
                // Delete
                Files.deleteIfExists(src.toPath());
            }
        }
    }

    /**
     * This method is called whenever a log file is rolled over
     *
     * @param oldFile The original filename or null if this is the first creation
     * @param backupFile The backup filename or null if the filename is dated.
     * @param newFile The new filename that is now being used for logging
     */
    protected void rollover(File oldFile, File backupFile, File newFile)
    {
    }

    void removeOldFiles(ZonedDateTime now)
    {
        if (_retainDays > 0)
        {
            // Establish expiration time, based on configured TimeZone
            long expired = now.minus(_retainDays, ChronoUnit.DAYS).toInstant().toEpochMilli();

            File file = new File(_filename);
            File dir = new File(file.getParent());
            String fn = file.getName();
            int s = fn.toLowerCase(Locale.ENGLISH).indexOf(YYYY_MM_DD);
            if (s < 0)
                return;
            String prefix = fn.substring(0, s);
            String suffix = fn.substring(s + YYYY_MM_DD.length());

            String[] logList = dir.list();
            for (int i = 0; i < logList.length; i++)
            {
                fn = logList[i];
                if (fn.startsWith(prefix) && fn.indexOf(suffix, prefix.length()) >= 0)
                {
                    File f = new File(dir, fn);
                    if (f.lastModified() < expired)
                    {
                        f.delete();
                    }
                }
            }
        }
    }

    @Override
    public void write(int b) throws IOException
    {
        try (AutoLock l = _lock.lock())
        {
            _out.write(b);
        }
    }

    @Override
    public void write(byte[] buf) throws IOException
    {
        try (AutoLock l = _lock.lock())
        {
            _out.write(buf);
        }
    }

    @Override
    public void write(byte[] buf, int off, int len) throws IOException
    {
        try (AutoLock l = _lock.lock())
        {
            _out.write(buf, off, len);
        }
    }

    @Override
    public void flush() throws IOException
    {
        try (AutoLock l = _lock.lock())
        {
            _out.flush();
        }
    }

    @Override
    public void close() throws IOException
    {
        try (AutoLock l = _lock.lock())
        {
            try
            {
                _out.close();
            }
            finally
            {
                _out = null;
                _file = null;
            }
        }

        ScheduledFuture<?> rollTask = _rollTask;
        if (rollTask != null)
            rollTask.cancel(false);
    }

    private void rollOver()
    {
        try
        {
            ZonedDateTime now = ZonedDateTime.now(_fileDateFormat.getTimeZone().toZoneId());
            RolloverFileOutputStream.this.setFile(now);
            RolloverFileOutputStream.this.removeOldFiles(now);
            RolloverFileOutputStream.this.scheduleNextRollover(now);
        }
        catch (Throwable t)
        {
            // Cannot log this exception to a LOG, as RolloverFOS can be used by logging
            t.printStackTrace(System.err);
        }
    }
}
