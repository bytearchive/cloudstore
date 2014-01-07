package co.codewizards.cloudstore.core.repo.local;

import static co.codewizards.cloudstore.core.util.Util.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import javax.jdo.PersistenceManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import co.codewizards.cloudstore.core.persistence.DeleteModification;
import co.codewizards.cloudstore.core.persistence.Directory;
import co.codewizards.cloudstore.core.persistence.ModificationDAO;
import co.codewizards.cloudstore.core.persistence.NormalFile;
import co.codewizards.cloudstore.core.persistence.RemoteRepository;
import co.codewizards.cloudstore.core.persistence.RemoteRepositoryDAO;
import co.codewizards.cloudstore.core.persistence.RepoFile;
import co.codewizards.cloudstore.core.persistence.RepoFileDAO;
import co.codewizards.cloudstore.core.progress.ProgressMonitor;
import co.codewizards.cloudstore.core.util.HashUtil;

public class LocalRepoSync {

	private static final Logger logger = LoggerFactory.getLogger(LocalRepoSync.class);

	private final LocalRepoTransaction transaction;
	private final File localRoot;
	private final RepoFileDAO repoFileDAO;
	private final RemoteRepositoryDAO remoteRepositoryDAO;
	private final ModificationDAO modificationDAO;

	public LocalRepoSync(LocalRepoTransaction transaction) {
		this.transaction = assertNotNull("transaction", transaction);
		localRoot = this.transaction.getLocalRepoManager().getLocalRoot();
		repoFileDAO = this.transaction.getDAO(RepoFileDAO.class);
		remoteRepositoryDAO = this.transaction.getDAO(RemoteRepositoryDAO.class);
		modificationDAO = this.transaction.getDAO(ModificationDAO.class);
	}

	public void sync(ProgressMonitor monitor) { // TODO use this monitor!!!
		sync(null, localRoot);
	}

	private void sync(RepoFile parentRepoFile, File file) {
		RepoFile repoFile = repoFileDAO.getRepoFile(localRoot, file);

		// If the type changed - e.g. from normal file to directory - we must delete
		// the old instance.
		if (repoFile != null && !isRepoFileTypeCorrect(repoFile, file)) {
			deleteRepoFile(repoFile, false);
			repoFile = null;
		}

		if (repoFile == null) {
			repoFile = createRepoFile(parentRepoFile, file);
			if (repoFile == null) { // ignoring non-normal files.
				return;
			}
		} else if (isModified(repoFile, file)) {
			updateRepoFile(repoFile, file);
		}

		Set<String> childNames = new HashSet<String>();
		File[] children = file.listFiles(new FilenameFilterSkipMetaDir());
		if (children != null) {
			for (File child : children) {
				childNames.add(child.getName());
				sync(repoFile, child);
			}
		}

		Collection<RepoFile> childRepoFiles = repoFileDAO.getChildRepoFiles(repoFile);
		for (RepoFile childRepoFile : childRepoFiles) {
			if (!childNames.contains(childRepoFile.getName())) {
				deleteRepoFile(childRepoFile);
			}
		}
	}

	private boolean isRepoFileTypeCorrect(RepoFile repoFile, File file) {
		// TODO support symlinks!
		if (file.isFile())
			return repoFile instanceof NormalFile;

		if (file.isDirectory())
			return repoFile instanceof Directory;

		return false;
	}

	private boolean isModified(RepoFile repoFile, File file) {
		if (repoFile.getLastModified().getTime() != file.lastModified()) {
			if (logger.isDebugEnabled()) {
				logger.debug("isModified: repoFile.lastModified != file.lastModified: repoFile.lastModified={} file.lastModified={} file={}",
						repoFile.getLastModified(), new Date(file.lastModified()), file);
			}
			return true;
		}

		if (file.isFile()) {
			if (!(repoFile instanceof NormalFile))
				throw new IllegalArgumentException("repoFile is not an instance of NormalFile! file=" + file);

			NormalFile normalFile = (NormalFile) repoFile;
			if (normalFile.getLength() != file.length()) {
				if (logger.isDebugEnabled()) {
					logger.debug("isModified: normalFile.length != file.length: repoFile.length={} file.length={} file={}",
							normalFile.getLength(), file.length(), file);
				}
				return true;
			}
		}

		return false;
	}

	private RepoFile createRepoFile(RepoFile parentRepoFile, File file) {
		if (parentRepoFile == null)
			throw new IllegalStateException("Creating the root this way is not possible! Why is it not existing, yet?!???");

		// TODO support symlinks!

		RepoFile repoFile;

		if (file.isDirectory()) {
			repoFile = new Directory();
		} else if (file.isFile()) {
			NormalFile normalFile = (NormalFile) (repoFile = new NormalFile());
			normalFile.setLength(file.length());
			normalFile.setSha1(sha(file));
		} else {
			logger.warn("File is neither a directory nor a normal file! Skipping: {}", file);
			return null;
		}

		repoFile.setParent(parentRepoFile);
		repoFile.setName(file.getName());
		repoFile.setLastModified(new Date(file.lastModified()));

		return repoFileDAO.makePersistent(repoFile);
	}

	public void updateRepoFile(RepoFile repoFile, File file) {
		logger.debug("updateRepoFile: entityID={} idHigh={} idLow={} file={}", repoFile.getEntityID(), repoFile.getIdHigh(), repoFile.getIdLow(), file);
		if (file.isFile()) {
			if (!(repoFile instanceof NormalFile))
				throw new IllegalArgumentException("repoFile is not an instance of NormalFile!");

			NormalFile normalFile = (NormalFile) repoFile;
			normalFile.setLength(file.length());
			normalFile.setSha1(sha(file));
		}
		repoFile.setLastModified(new Date(file.lastModified()));
	}

	public void deleteRepoFile(RepoFile repoFile) {
		deleteRepoFile(repoFile, true);
	}
	
	private void deleteRepoFile(RepoFile repoFile, boolean createDeleteModifications) {
		RepoFile parentRepoFile = assertNotNull("repoFile", repoFile).getParent();
		if (parentRepoFile == null)
			throw new IllegalStateException("Deleting the root is not possible!");

		PersistenceManager pm = transaction.getPersistenceManager();

		// We make sure, nothing interferes with our deletions (see comment below).
		pm.flush();

		if (createDeleteModifications)
			createDeleteModifications(repoFile);

		deleteRepoFileWithAllChildrenRecursively(repoFile);

		// DN batches UPDATE and DELETE statements. This sometimes causes foreign key violations and other errors in
		// certain situations. Additionally, the deleted objects still linger in the 1st-level-cache and re-using them
		// causes "javax.jdo.JDOUserException: Cannot read fields from a deleted object". This happens when switching
		// from a directory to a file (or vice versa).
		// We therefore must flush to be on the safe side. And to be extra-safe, we flush before and after deletion.
		pm.flush();
	}

	private void createDeleteModifications(RepoFile repoFile) {
		assertNotNull("repoFile", repoFile);
		for (RemoteRepository remoteRepository : remoteRepositoryDAO.getObjects()) {
			DeleteModification deleteModification = new DeleteModification();
			deleteModification.setRemoteRepository(remoteRepository);
			deleteModification.setPath(repoFile.getPath());
			modificationDAO.makePersistent(deleteModification);
		}
	}

	private void deleteRepoFileWithAllChildrenRecursively(RepoFile repoFile) {
		assertNotNull("repoFile", repoFile);
		for (RepoFile childRepoFile : repoFileDAO.getChildRepoFiles(repoFile)) {
			deleteRepoFileWithAllChildrenRecursively(childRepoFile);
		}
		repoFileDAO.deletePersistent(repoFile);
		repoFileDAO.getPersistenceManager().flush(); // We run *sometimes* into foreign key violations if we don't delete immediately :-(
	}

	private String sha(File file) {
		assertNotNull("file", file);
		if (!file.isFile()) {
			return null;
		}
		try {
			FileInputStream in = new FileInputStream(file);
			byte[] hash = HashUtil.hash(HashUtil.HASH_ALGORITHM_SHA, in);
			in.close();
			return HashUtil.encodeHexStr(hash);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}