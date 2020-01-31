//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server.session;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * FileSessionDataStore
 *
 * A file-based store of session data.
 */
@ManagedObject
public class FileSessionDataStore extends AbstractSessionDataStore
{
    private static final Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    protected File _storeDir;
    protected boolean _deleteUnrestorableFiles = false;
    protected Map<String, String> _sessionFileMap = new ConcurrentHashMap<>();
    protected String _contextString;
    protected long _lastSweepTime = 0L;

    @Override
    public void initialize(SessionContext context) throws Exception
    {
        super.initialize(context);
        _contextString = _context.getCanonicalContextPath() + "_" + _context.getVhost();
    }

    @Override
    protected void doStart() throws Exception
    {
        initializeStore();
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        _sessionFileMap.clear();
        _lastSweepTime = 0;
        super.doStop();
    }

    @ManagedAttribute(value = "dir where sessions are stored", readonly = true)
    public File getStoreDir()
    {
        return _storeDir;
    }

    public void setStoreDir(File storeDir)
    {
        checkStarted();
        _storeDir = storeDir;
    }

    public boolean isDeleteUnrestorableFiles()
    {
        return _deleteUnrestorableFiles;
    }

    public void setDeleteUnrestorableFiles(boolean deleteUnrestorableFiles)
    {
        checkStarted();
        _deleteUnrestorableFiles = deleteUnrestorableFiles;
    }

    /**
     * Delete a session
     *
     * @param id session id
     */
    @Override
    public boolean delete(String id) throws Exception
    {
        if (_storeDir != null)
        {
            //remove from our map
            String filename = _sessionFileMap.remove(getIdWithContext(id));
            if (filename == null)
                return false;

            //remove the file
            return deleteFile(filename);
        }

        return false;
    }

    /**
     * Delete the file associated with a session
     *
     * @param filename name of the file containing the session's information
     * @return true if file was deleted, false otherwise
     * @throws Exception indicating delete failure
     */
    public boolean deleteFile(String filename) throws Exception
    {
        if (filename == null)
            return false;
        File file = new File(_storeDir, filename);
        return Files.deleteIfExists(file.toPath());
    }

    /**
     * Check to see which sessions have expired.
     *
     * @param candidates the set of session ids that the SessionCache believes
     * have expired
     * @return the complete set of sessions that have expired, including those
     * that are not currently loaded into the SessionCache
     */
    @Override
    public Set<String> doGetExpired(final Set<String> candidates)
    {
        final long now = System.currentTimeMillis();
        HashSet<String> expired = new HashSet<String>();

        //iterate over the files and work out which have expired
        for (String filename : _sessionFileMap.values())
        {
            try
            {
                long expiry = getExpiryFromFilename(filename);
                if (expiry > 0 && expiry < now)
                    expired.add(getIdFromFilename(filename));
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
        }

        //check candidates that were not found to be expired, perhaps 
        //because they no longer exist and they should be expired
        for (String c : candidates)
        {
            if (!expired.contains(c))
            {
                //if it doesn't have a file then the session doesn't exist
                String filename = _sessionFileMap.get(getIdWithContext(c));
                if (filename == null)
                    expired.add(c);
            }
        }

        //Infrequently iterate over all files in the store, and delete those
        //that expired a long time ago, even if they belong to
        //another context. This ensures that files that
        //belong to defunct contexts are cleaned up. 
        //If the graceperiod is disabled, don't do the sweep!
        if ((_gracePeriodSec > 0) && ((_lastSweepTime == 0) || ((now - _lastSweepTime) >= (5 * TimeUnit.SECONDS.toMillis(_gracePeriodSec)))))
        {
            _lastSweepTime = now;
            sweepDisk();
        }
        return expired;
    }

    /**
     * Check all session files that do not belong to this context and
     * remove any that expired long ago (ie at least 5 gracePeriods ago).
     */
    public void sweepDisk()
    {
        //iterate over the files in the store dir and check expiry times
        long now = System.currentTimeMillis();
        if (LOG.isDebugEnabled())
            LOG.debug("Sweeping {} for old session files", _storeDir);
        try
        {
            Files.walk(_storeDir.toPath(), 1, FileVisitOption.FOLLOW_LINKS)
                .filter(p -> !Files.isDirectory(p)).filter(p -> !isOurContextSessionFilename(p.getFileName().toString()))
                .filter(p -> isSessionFilename(p.getFileName().toString()))
                .forEach(p ->
                {

                    try
                    {
                        sweepFile(now, p);
                    }
                    catch (Exception e)
                    {
                        LOG.warn(e);
                    }
                });
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }

    /**
     * Check to see if the expiry on the file is very old, and
     * delete the file if so. "Old" means that it expired at least
     * 5 gracePeriods ago. The session can belong to any context.
     *
     * @param now the time now in msec
     * @param p the file to check
     * @throws Exception indicating error in sweep
     */
    public void sweepFile(long now, Path p)
        throws Exception
    {
        if (p == null)
            return;

        try
        {
            long expiry = getExpiryFromFilename(p.getFileName().toString());
            //files with 0 expiry never expire
            if (expiry > 0 && ((now - expiry) >= (5 * TimeUnit.SECONDS.toMillis(_gracePeriodSec))))
            {
                Files.deleteIfExists(p);
                if (LOG.isDebugEnabled())
                    LOG.debug("Sweep deleted {}", p.getFileName());
            }
        }
        catch (NumberFormatException e)
        {
            LOG.warn("Not valid session filename {}", p.getFileName());
            LOG.warn(e);
        }
    }

    @Override
    public SessionData doLoad(String id) throws Exception
    {
        //load session info from its file
        String idWithContext = getIdWithContext(id);
        String filename = _sessionFileMap.get(idWithContext);
        if (filename == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unknown file {}", idWithContext);
            return null;
        }
        File file = new File(_storeDir, filename);
        if (!file.exists())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("No such file {}", filename);
            return null;
        }

        try (FileInputStream in = new FileInputStream(file))
        {
            SessionData data = load(in, id);
            data.setLastSaved(file.lastModified());
            return data;
        }
        catch (UnreadableSessionDataException e)
        {
            if (isDeleteUnrestorableFiles() && file.exists() && file.getParentFile().equals(_storeDir))
            {
                try
                {
                    delete(id);
                    LOG.warn("Deleted unrestorable file for session {}", id);
                }
                catch (Exception x)
                {
                    LOG.warn("Unable to delete unrestorable file {} for session {}", filename, id);
                    LOG.warn(x);
                }
            }
            throw e;
        }
    }

    @Override
    public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
    {
        File file = null;
        if (_storeDir != null)
        {
            delete(id);

            //make a fresh file using the latest session expiry
            String filename = getIdWithContextAndExpiry(data);
            String idWithContext = getIdWithContext(id);
            file = new File(_storeDir, filename);

            try (FileOutputStream fos = new FileOutputStream(file, false))
            {
                save(fos, id, data);
                _sessionFileMap.put(idWithContext, filename);
            }
            catch (Exception e)
            {
                if (file != null)
                    file.delete(); // No point keeping the file if we didn't save the whole session
                throw new UnwriteableSessionDataException(id, _context, e);
            }
        }
    }

    /**
     * Read the names of the existing session files and build a map of
     * fully qualified session ids (ie with context) to filename.  If there
     * is more than one file for the same session, only the most recently modified will
     * be kept and the rest deleted. At the same time, any files - for any context -
     * that expired a long time ago will be cleaned up.
     *
     * @throws Exception if storeDir doesn't exist, isn't readable/writeable
     * or contains 2 files with the same lastmodify time for the same session. Throws IOException
     * if the lastmodifytimes can't be read.
     */
    public void initializeStore()
        throws Exception
    {
        if (_storeDir == null)
            throw new IllegalStateException("No file store specified");

        if (!_storeDir.exists())
            _storeDir.mkdirs();
        else
        {
            if (!(_storeDir.isDirectory() && _storeDir.canWrite() && _storeDir.canRead()))
                throw new IllegalStateException(_storeDir.getAbsolutePath() + " must be readable/writeable dir");

            //iterate over files in _storeDir and build map of session id to filename.
            //if we come across files for sessions in other contexts, check if they're
            //ancient and remove if necessary.
            MultiException me = new MultiException();
            long now = System.currentTimeMillis();

            Files.walk(_storeDir.toPath(), 1, FileVisitOption.FOLLOW_LINKS)
                .filter(p -> !Files.isDirectory(p)).filter(p -> isSessionFilename(p.getFileName().toString()))
                .forEach(p ->
                {
                    //first get rid of all ancient files, regardless of which
                    //context they are for
                    try
                    {
                        sweepFile(now, p);
                    }
                    catch (Exception x)
                    {
                        me.add(x);
                    }

                    String filename = p.getFileName().toString();
                    String context = getContextFromFilename(filename);
                    //now process it if it wasn't deleted, and it is for our context
                    if (Files.exists(p) && _contextString.equals(context))
                    {
                        //the session is for our context, populate the map with it
                        String sessionIdWithContext = getIdWithContextFromFilename(filename);
                        if (sessionIdWithContext != null)
                        {
                            //handle multiple session files existing for the same session: remove all
                            //but the file with the most recent expiry time
                            String existing = _sessionFileMap.putIfAbsent(sessionIdWithContext, filename);
                            if (existing != null)
                            {
                                //if there was a prior filename, work out which has the most
                                //recent modify time
                                try
                                {
                                    long existingExpiry = getExpiryFromFilename(existing);
                                    long thisExpiry = getExpiryFromFilename(filename);

                                    if (thisExpiry > existingExpiry)
                                    {
                                        //replace with more recent file
                                        Path existingPath = _storeDir.toPath().resolve(existing);
                                        //update the file we're keeping
                                        _sessionFileMap.put(sessionIdWithContext, filename);
                                        //delete the old file
                                        Files.delete(existingPath);
                                        if (LOG.isDebugEnabled())
                                            LOG.debug("Replaced {} with {}", existing, filename);
                                    }
                                    else
                                    {
                                        //we found an older file, delete it
                                        Files.delete(p);
                                        if (LOG.isDebugEnabled())
                                            LOG.debug("Deleted expired session file {}", filename);
                                    }
                                }
                                catch (IOException e)
                                {
                                    me.add(e);
                                }
                            }
                        }
                    }
                });
            me.ifExceptionThrow();
        }
    }

    @Override
    @ManagedAttribute(value = "are sessions serialized by this store", readonly = true)
    public boolean isPassivating()
    {
        return true;
    }

    @Override
    public boolean exists(String id) throws Exception
    {
        String idWithContext = getIdWithContext(id);
        String filename = _sessionFileMap.get(idWithContext);

        if (filename == null)
            return false;

        //check the expiry
        long expiry = getExpiryFromFilename(filename);
        if (expiry <= 0)
            return true; //never expires
        else
            return (expiry > System.currentTimeMillis()); //hasn't yet expired
    }

    /**
     * Save the session data.
     *
     * @param os the output stream to save to
     * @param id identity of the session
     * @param data the info of the session
     */
    protected void save(OutputStream os, String id, SessionData data) throws IOException
    {
        DataOutputStream out = new DataOutputStream(os);
        out.writeUTF(id);
        out.writeUTF(_context.getCanonicalContextPath());
        out.writeUTF(_context.getVhost());
        out.writeUTF(data.getLastNode());
        out.writeLong(data.getCreated());
        out.writeLong(data.getAccessed());
        out.writeLong(data.getLastAccessed());
        out.writeLong(data.getCookieSet());
        out.writeLong(data.getExpiry());
        out.writeLong(data.getMaxInactiveMs());

        ObjectOutputStream oos = new ObjectOutputStream(out);
        SessionData.serializeAttributes(data, oos);
    }

    /**
     * Get the session id with its context.
     *
     * @param id identity of session
     * @return the session id plus context
     */
    protected String getIdWithContext(String id)
    {
        return _contextString + "_" + id;
    }

    /**
     * Get the session id with its context and its expiry time
     *
     * @return the session id plus context and expiry
     */
    protected String getIdWithContextAndExpiry(SessionData data)
    {
        return "" + data.getExpiry() + "_" + getIdWithContext(data.getId());
    }

    protected String getIdFromFilename(String filename)
    {
        if (filename == null)
            return null;
        return filename.substring(filename.lastIndexOf('_') + 1);
    }

    protected long getExpiryFromFilename(String filename)
    {
        if (StringUtil.isBlank(filename) || filename.indexOf("_") < 0)
            throw new IllegalStateException("Invalid or missing filename");

        String s = filename.substring(0, filename.indexOf('_'));
        return (s == null ? 0 : Long.parseLong(s));
    }

    protected String getContextFromFilename(String filename)
    {
        if (StringUtil.isBlank(filename))
            return null;

        int start = filename.indexOf('_');
        int end = filename.lastIndexOf('_');
        return filename.substring(start + 1, end);
    }

    /**
     * Extract the session id and context from the filename
     *
     * @param filename the name of the file to use
     * @return the session id plus context
     */
    protected String getIdWithContextFromFilename(String filename)
    {
        if (StringUtil.isBlank(filename) || filename.indexOf('_') < 0)
            return null;

        return filename.substring(filename.indexOf('_') + 1);
    }

    /**
     * Check if the filename is a session filename.
     *
     * @param filename the filename to check
     * @return true if the filename has the correct filename format
     */
    protected boolean isSessionFilename(String filename)
    {
        if (StringUtil.isBlank(filename))
            return false;
        String[] parts = filename.split("_");

        //Need at least 4 parts for a valid filename
        return parts.length >= 4;
    }

    /**
     * Check if the filename matches our session pattern
     * and is a session for our context.
     *
     * @param filename the filename to check
     * @return true if the filename has the correct filename format and is for this context
     */
    protected boolean isOurContextSessionFilename(String filename)
    {
        if (StringUtil.isBlank(filename))
            return false;
        String[] parts = filename.split("_");

        //Need at least 4 parts for a valid filename
        if (parts.length < 4)
            return false;

        //Also needs to be for our context
        String context = getContextFromFilename(filename);
        if (context == null)
            return false;
        return (_contextString.equals(context));
    }

    /**
     * Load the session data from a file.
     *
     * @param is file input stream containing session data
     * @param expectedId the id we've been told to load
     * @return the session data
     */
    protected SessionData load(InputStream is, String expectedId)
        throws Exception
    {
        String id = null; //the actual id from inside the file

        try
        {
            SessionData data = null;
            DataInputStream di = new DataInputStream(is);

            id = di.readUTF();
            String contextPath = di.readUTF();
            String vhost = di.readUTF();
            String lastNode = di.readUTF();
            long created = di.readLong();
            long accessed = di.readLong();
            long lastAccessed = di.readLong();
            long cookieSet = di.readLong();
            long expiry = di.readLong();
            long maxIdle = di.readLong();

            data = newSessionData(id, created, accessed, lastAccessed, maxIdle);
            data.setContextPath(contextPath);
            data.setVhost(vhost);
            data.setLastNode(lastNode);
            data.setCookieSet(cookieSet);
            data.setExpiry(expiry);
            data.setMaxInactiveMs(maxIdle);

            // Attributes
            ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream(is);
            SessionData.deserializeAttributes(data, ois);
            return data;
        }
        catch (Exception e)
        {
            throw new UnreadableSessionDataException(expectedId, _context, e);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s[dir=%s,deleteUnrestorableFiles=%b]", super.toString(), _storeDir, _deleteUnrestorableFiles);
    }
}
