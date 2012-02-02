// Copyright (C) 2011 jOVAL.org.  All rights reserved.
// This software is licensed under the AGPL 3.0 license available at http://www.joval.org/agpl_v3.txt

package org.joval.os.unix.io;

import java.io.File;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Date;
import java.util.List;
import java.util.Vector;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import java.util.regex.Matcher;

import org.slf4j.cal10n.LocLogger;

import org.joval.intf.io.IFile;
import org.joval.intf.io.IFilesystem;
import org.joval.intf.io.IRandomAccess;
import org.joval.intf.io.IReader;
import org.joval.intf.system.IBaseSession;
import org.joval.intf.system.IEnvironment;
import org.joval.intf.system.IProcess;
import org.joval.intf.unix.io.IUnixFilesystem;
import org.joval.intf.unix.system.IUnixSession;
import org.joval.intf.util.IPathRedirector;
import org.joval.intf.util.IProperty;
import org.joval.intf.util.tree.INode;
import org.joval.intf.util.tree.ITreeBuilder;
import org.joval.intf.system.IEnvironment;
import org.joval.io.BaseFilesystem;
import org.joval.io.PerishableReader;
import org.joval.io.StreamLogger;
import org.joval.util.tree.CachingTree;
import org.joval.util.tree.Tree;
import org.joval.util.JOVALMsg;
import org.joval.util.JOVALSystem;
import org.joval.util.SafeCLI;
import org.joval.util.StringTools;

/**
 * A local IFilesystem implementation for Unix.
 *
 * @author David A. Solin
 * @version %I% %G%
 */
public class UnixFilesystem extends BaseFilesystem implements IUnixFilesystem {
    private final static String LOCAL_INDEX = "fs.index";

    protected final static char DELIM_CH = '/';
    protected final static String DELIM_STR = "/";

    protected long S, M, L, XL;

    private boolean preloaded = false;
    private int entries, maxEntries;
    private ITreeBuilder tree;

    public UnixFilesystem(IBaseSession session, IEnvironment env) {
	super(session, env, null);

	tree = new Tree("", DELIM_STR);
	cache.addTree(tree);

	S = session.getTimeout(IUnixSession.Timeout.S);
	M = session.getTimeout(IUnixSession.Timeout.M);
	L = session.getTimeout(IUnixSession.Timeout.L);
	XL= session.getTimeout(IUnixSession.Timeout.XL);
    }

    /**
     * Non-local subclasses should override this method.
     */
    protected boolean doPreload() {
	return props.getBooleanProperty(PROP_PRELOAD_LOCAL);
    }

    /**
     * @override
     */
    public String getDelimiter() {
	return DELIM_STR;
    }

    public boolean preload() {
	if (!doPreload()) {
	    return false;
	} else if (preloaded) {
	    return true;
	} else if (session.getType() != IBaseSession.Type.UNIX) {
	    return false;
	}

	entries = 0;
	maxEntries = props.getIntProperty(PROP_PRELOAD_MAXENTRIES);

	try {
	    String command = getFindCommand((IUnixSession)session);
	    List<String> mounts = getMounts((IUnixSession)session);

	    if (VAL_FILE_METHOD.equals(props.getProperty(PROP_PRELOAD_METHOD))) {
		IReader reader = null;

		File wsdir = session.getWorkspace();
		File local = null;
		long len = 0L;
		if(wsdir == null) {
		    //
		    // State cannot be saved locally, so create one on the remote box.
		    //
		    reader = PerishableReader.newInstance(getRemoteCache(command, mounts).getInputStream(), S);
		} else {
		    //
		    // Read from the local state file, or create one while reading from the remote state file.
		    //
		    local = new File(wsdir, LOCAL_INDEX);
		    IFile f = new FileProxy(this, local, local.getPath());
		    if (isValidCache(f)) {
			len = f.length();
			reader = PerishableReader.newInstance(f.getInputStream(), S);
		    } else {
			f = getRemoteCache(command, mounts);
			len = f.length();

/*
 TBD:  ???

Need to create a properties file containing:
1) a list of the mounts that were indexed
2) the length and CRC checksum of the cache file on the server
Then these properties need to be saved to a local file.

These need to be validated before the next load, so we can determine whether to use the remote cache.
 -> use a CheckInputStream(in, new CRC32()) to get the CRC checksum of the local file

*/

			InputStream in = new StreamLogger(null, f.getInputStream(), local, logger);
			reader = PerishableReader.newInstance(in, S);
		    }
		}
		addEntries(reader);
	    } else {
		for (String mount : mounts) {
		    IProcess p = null;
		    ErrorReader er = null;
		    IReader reader = null;
		    try {
			p = session.createProcess(command.replace("%MOUNT%", mount));
			logger.info(JOVALMsg.STATUS_PROCESS_START, p.getCommand());
			p.start();
			reader = PerishableReader.newInstance(p.getInputStream(), S);
			er = new ErrorReader(PerishableReader.newInstance(p.getErrorStream(), XL));
			er.start();
			addEntries(reader);
		    } finally {
			if (reader != null) {
			    reader.close();
			}
			//
			// Clean-up
			//
			if (p != null) {
			    p.waitFor(0);
			}
			if (er != null) {
			    er.join();
			}
		    }
		}
	    }
	    preloaded = true;
	    return true;
	} catch (PreloadOverflowException e) {
	    logger.warn(JOVALMsg.ERROR_PRELOAD_OVERFLOW, maxEntries);
	    preloaded = true;
	    return true;
	} catch (Exception e) {
	    logger.warn(JOVALMsg.ERROR_PRELOAD);
	    logger.warn(JOVALSystem.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
	    return false;
	}
    }

    // Private

    private void addEntries(IReader reader) throws PreloadOverflowException, IOException {
	String line = null;
	while((line = reader.readLine()) != null) {
	    if (entries++ < maxEntries) {
		if (entries % 20000 == 0) {
		    logger.info(JOVALMsg.STATUS_FS_PRELOAD_FILE_PROGRESS, entries);
		}
		String path = line;
		if (!path.equals(DELIM_STR)) { // skip the root node
		    INode node = tree.getRoot();
		    try {
			while ((path = trimToken(path, DELIM_STR)) != null) {
			    node = node.getChild(getToken(path, DELIM_STR));
			}
		    } catch (UnsupportedOperationException e) {
			do {
			    node = tree.makeNode(node, getToken(path, DELIM_STR));
			} while ((path = trimToken(path, DELIM_STR)) != null);
		    } catch (NoSuchElementException e) {
			do {
			    node = tree.makeNode(node, getToken(path, DELIM_STR));
			} while ((path = trimToken(path, DELIM_STR)) != null);
		    }
		}
	    } else {
		logger.warn(JOVALMsg.ERROR_PRELOAD_OVERFLOW, maxEntries);
		throw new PreloadOverflowException();
	    }
	}
	reader.close();
    }

    /**
     * Returns a string containing the correct find command for the Unix flavor.  The command contains the String "%MOUNT%"
     * where the actual path of the mount should be substituted.
     *
     * The resulting command will follow links, but restrict results to the originating filesystem.
     */
    private String getFindCommand(IUnixSession us) throws Exception {
	StringBuffer command = new StringBuffer("find -L %MOUNT%");
	switch(us.getFlavor()) {
	  case AIX:
	    command.append(" -xdev");
	    break;

	  default:
	    command.append(" -mount");
	    break;
	}
	return command.toString();
    }

    /**
     * Get a list of mount points.  The result will be filtered by the configured list of filesystem types.
     */
    private List<String> getMounts(IUnixSession us) throws Exception {
	List<String> fsTypeFilter = new Vector<String>();
	String filterStr = props.getProperty(PROP_PRELOAD_FSTYPE_FILTER);
	if (filterStr != null) {
	    fsTypeFilter = StringTools.toList(StringTools.tokenize(filterStr, ":", true));
	}

	List<String> mounts = new Vector<String>();
	int lineNum = 0;
	switch(us.getFlavor()) {
	  case AIX:
	    for (String line : SafeCLI.multiLine("mount", us, S)) {
		if (lineNum++ > 1) { // skip the first two lines
		    int mpToken = 1;
		    switch(line.charAt(0)) {
		      case ' ':
		      case '\t':
			mpToken = 1; // mount-point is the second token
			break;
		      default:
			mpToken = 2; // mount-point is the third token
			break;
		    }
		    StringTokenizer tok = new StringTokenizer(line);
		    String mountPoint = null, fsType = null;
		    for (int i=0; i <= mpToken; i++) {
			mountPoint = tok.nextToken();
		    }
		    fsType = tok.nextToken();

		    if (fsTypeFilter.contains(fsType)) {
			logger.info(JOVALMsg.STATUS_FS_PRELOAD_SKIP, mountPoint, fsType);
		    } else if (mountPoint.startsWith(DELIM_STR)) {
			logger.info(JOVALMsg.STATUS_FS_PRELOAD_MOUNT, mountPoint, fsType);
			mounts.add(mountPoint);
		    }
		}
	    }
	    break;

	  case MACOSX: {
	    StringBuffer command = new StringBuffer("df");
	    int filterSize = fsTypeFilter.size();
	    if (filterSize > 0) {
		command.append(" -T no");
		for (int i=0; i < filterSize; i++) {
		    if (i > 0) {
			command.append(",");
		    }
		    command.append(fsTypeFilter.get(i));
		}
	    }
	    for (String line : SafeCLI.multiLine(command.toString(), us, S)) {
		if (lineNum++ > 0) { // skip the first line
		    StringTokenizer tok = new StringTokenizer(line);
		    String mountPoint = null;
		    while(tok.hasMoreTokens()) {
			mountPoint = tok.nextToken();
		    }
		    if (mountPoint.startsWith(DELIM_STR)) {
			logger.info(JOVALMsg.STATUS_FS_PRELOAD_MOUNT, mountPoint, "?");
			mounts.add(mountPoint);
		    }
		}
	    }
	    break;
	  }

	  case SOLARIS: {
	    IReader reader = PerishableReader.newInstance(getFile("/etc/vfstab").getInputStream(), S);
	    String line = null;
	    while ((line = reader.readLine()) != null) {
		if (!line.startsWith("#")) { // skip comments
		    StringTokenizer tok = new StringTokenizer(line);
		    String dev = tok.nextToken();
		    String fixdev = tok.nextToken();
		    String mountPoint = tok.nextToken();
		    String fsType = tok.nextToken();
		    if (fsTypeFilter.contains(fsType)) {
			logger.info(JOVALMsg.STATUS_FS_PRELOAD_SKIP, mountPoint, fsType);
		    } else if (mountPoint.startsWith(DELIM_STR)) {
			logger.info(JOVALMsg.STATUS_FS_PRELOAD_MOUNT, mountPoint, fsType);
			mounts.add(mountPoint);
		    }
		}
	    }
	    break;
	  }

	  case LINUX:
	    for (String line : SafeCLI.multiLine("df -TP", us, S)) {
		if (lineNum++ > 0) { // skip the first line
		    StringTokenizer tok = new StringTokenizer(line);
		    String fsName = tok.nextToken();
		    String fsType = tok.nextToken();
		    String mountPoint = null;
		    while(tok.hasMoreTokens()) {
			mountPoint = tok.nextToken();
		    }
		    if (fsTypeFilter.contains(fsType)) {
			logger.info(JOVALMsg.STATUS_FS_PRELOAD_SKIP, mountPoint, fsType);
		    } else if (mountPoint.startsWith(DELIM_STR) && !mounts.contains(mountPoint)) { // skip over-mounts
			logger.info(JOVALMsg.STATUS_FS_PRELOAD_MOUNT, mountPoint, fsType);
			mounts.add(mountPoint);
		    }
		}
	    }
	    break;
	}
	return mounts;
    }

    /**
     * Check to see if the IFile represents a valid cache of the preload data.  This works on either a local or remote
     * copy of the cache.  The lastModified date is compared against the expiration, and if stale, the IFile is deleted.
     */
    private boolean isValidCache(IFile f) throws IOException {
	if (f.exists()) {
	    long expires = f.lastModified() + props.getLongProperty(PROP_PRELOAD_MAXAGE);
	    if (System.currentTimeMillis() >= expires) {
		logger.info(JOVALMsg.STATUS_FS_PRELOAD_CACHE_EXPIRED, f.getPath(), new Date(expires));
		f.delete();
		return false;
	    }
	    logger.info(JOVALMsg.STATUS_FS_PRELOAD_CACHE_REUSE, f.getPath());
	    return true;
	} else {
	    return false;
	}
    }

    private static final String CACHE_TEMP = "%HOME%/.jOVAL.find~";
    private static final String CACHE_FILE = "%HOME%/.jOVAL.find";

    /**
     * Return a valid cache file on the remote machine, if available, or create a new one and return it.
     */
    private IFile getRemoteCache(String command, List<String> mounts) throws Exception {
	String tempPath = env.expand(CACHE_TEMP);
	String destPath = env.expand(CACHE_FILE);

	IFile temp = getFile(destPath);
	if (isValidCache(temp)) {
	    return temp;
	}

	logger.info(JOVALMsg.STATUS_FS_PRELOAD_CACHE_TEMP, tempPath);
	for (int i=0; i < mounts.size(); i++) {
	    StringBuffer sb = new StringBuffer(command.replace("%MOUNT%", mounts.get(i)));
	    if (i > 0) {
		sb.append(" >> "); // append
	    } else {
		sb.append(" > ");  // over-write
	    }
	    sb.append(env.expand(tempPath)).toString();

	    IProcess p = session.createProcess(sb.toString());
	    logger.info(JOVALMsg.STATUS_PROCESS_START, p.getCommand());
	    p.start();
	    InputStream in = p.getInputStream();
	    if (in instanceof PerishableReader) {
		// This could take a while!
		((PerishableReader)in).setTimeout(XL);
	    }
	    ErrorReader er = new ErrorReader(PerishableReader.newInstance(p.getErrorStream(), XL));
	    er.start();

	    //
	    // Log a status update every 15 seconds while we wait, but wait for no more than an hour.
	    //
	    boolean done = false;
	    for (int j=0; !done && j < 240; j++) {
		for (int k=0; !done && k < 15; k++) {
		    if (p.isRunning()) {
			Thread.sleep(1000);
		    } else {
			done = true;
		    }
		}
		logger.info(JOVALMsg.STATUS_FS_PRELOAD_CACHE_PROGRESS, getFile(tempPath).length());
	    }
	    if (!done) {
		p.destroy();
	    }
	    in.close();
	    er.close();
	    er.join();
	}

	SafeCLI.exec("mv " + tempPath + " " + destPath, session, S);
	logger.info(JOVALMsg.STATUS_FS_PRELOAD_CACHE_CREATE, destPath);
	return getFile(destPath);
    }

    private class ErrorReader implements Runnable {
	IReader err;
	Thread t;

	ErrorReader(IReader err) {
	    err.setLogger(logger);
	    this.err = err;
	}

	void start() {
	    t = new Thread(this);
	    t.start();
	}

	void join() throws InterruptedException {
	    t.join();
	}

	void close() {
	    if (t.isAlive()) {
		t.interrupt();
	    }
	}

	public void run() {
	    try {
		String line = null;
		while((line = err.readLine()) != null) {
		    logger.warn(JOVALMsg.ERROR_PRELOAD_LINE, line);
		}
	    } catch (InterruptedIOException e) {
		// ignore
	    } catch (IOException e) {
		logger.warn(JOVALSystem.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
	    } finally {
		try {
		    err.close();
		} catch (IOException e) {
		}
	    }
	}
    }
}
