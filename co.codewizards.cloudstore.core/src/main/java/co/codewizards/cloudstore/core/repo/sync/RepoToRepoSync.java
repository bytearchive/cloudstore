package co.codewizards.cloudstore.core.repo.sync;

import static co.codewizards.cloudstore.core.util.Util.*;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import co.codewizards.cloudstore.core.dto.ChangeSet;
import co.codewizards.cloudstore.core.dto.DeleteModificationDTO;
import co.codewizards.cloudstore.core.dto.DirectoryDTO;
import co.codewizards.cloudstore.core.dto.EntityID;
import co.codewizards.cloudstore.core.dto.FileChunk;
import co.codewizards.cloudstore.core.dto.FileChunkSet;
import co.codewizards.cloudstore.core.dto.ModificationDTO;
import co.codewizards.cloudstore.core.dto.RepoFileDTO;
import co.codewizards.cloudstore.core.dto.RepoFileDTOTreeNode;
import co.codewizards.cloudstore.core.persistence.RemoteRepository;
import co.codewizards.cloudstore.core.persistence.RemoteRepositoryDAO;
import co.codewizards.cloudstore.core.progress.ProgressMonitor;
import co.codewizards.cloudstore.core.progress.SubProgressMonitor;
import co.codewizards.cloudstore.core.repo.local.LocalRepoManager;
import co.codewizards.cloudstore.core.repo.local.LocalRepoManagerFactory;
import co.codewizards.cloudstore.core.repo.local.LocalRepoTransaction;
import co.codewizards.cloudstore.core.repo.transport.RepoTransport;
import co.codewizards.cloudstore.core.repo.transport.RepoTransportFactory;
import co.codewizards.cloudstore.core.repo.transport.RepoTransportFactoryRegistry;

/**
 * Logic for synchronising a local with a remote repository.
 * @author Marco หงุ่ยตระกูล-Schulze - marco at codewizards dot co
 */
public class RepoToRepoSync {
	private static final Logger logger = LoggerFactory.getLogger(RepoToRepoSync.class);

	private final URL remoteRoot;
	private final LocalRepoManager localRepoManager;
	private final RepoTransport localRepoTransport;
	private final RepoTransport remoteRepoTransport;
	private final EntityID localRepositoryID;
	private EntityID remoteRepositoryID;

	public RepoToRepoSync(File localRoot, URL remoteRoot) {
		this.remoteRoot = assertNotNull("remoteRoot", remoteRoot);
		localRepoManager = LocalRepoManagerFactory.getInstance().createLocalRepoManagerForExistingRepository(assertNotNull("localRoot", localRoot));
		localRepoTransport = createRepoTransport(localRoot);

		localRepositoryID = localRepoTransport.getRepositoryID();
		if (localRepositoryID == null)
			throw new IllegalStateException("localRepoTransport.getRepositoryID() returned null!");

		remoteRepoTransport = createRepoTransport(remoteRoot);
	}

	public void sync(ProgressMonitor monitor) {
		assertNotNull("monitor", monitor);
		monitor.beginTask("Synchronising remotely...", 101);
		try {
			readRemoteRepositoryID();
			monitor.worked(1);
			sync(remoteRepoTransport, localRepoTransport, new SubProgressMonitor(monitor, 50));
			sync(localRepoTransport, remoteRepoTransport, new SubProgressMonitor(monitor, 50));
		} finally {
			monitor.done();
		}
	}

	private void readRemoteRepositoryID() {
		LocalRepoTransaction transaction = localRepoManager.beginTransaction();
		try {
			RemoteRepository remoteRepository = transaction.getDAO(RemoteRepositoryDAO.class).getRemoteRepositoryOrFail(remoteRoot);
			remoteRepositoryID = remoteRepository.getEntityID();

			EntityID repositoryID = remoteRepoTransport.getRepositoryID();
			if (repositoryID == null)
				throw new IllegalStateException("remoteRepoTransport.getRepositoryID() returned null!");

			if (!repositoryID.equals(remoteRepositoryID))
				throw new IllegalStateException(
						String.format("remoteRepoTransport.getRepositoryID() does not match repositoryID in local DB! %s != %s", repositoryID, remoteRepositoryID));

			transaction.commit();
		} finally {
			transaction.rollbackIfActive();
		}
	}

	private EntityID getRepositoryID(RepoTransport repoTransport) {
		if (localRepoTransport == repoTransport)
			return localRepositoryID;

		if (remoteRepoTransport == repoTransport)
			return remoteRepositoryID;

		throw new IllegalArgumentException("repoTransport is neither local nor remote!");
	}

	private RepoTransport createRepoTransport(File rootFile) {
		URL rootURL;
		try {
			rootURL = rootFile.toURI().toURL();
		} catch (MalformedURLException e) {
			throw new RuntimeException(e);
		}
		return createRepoTransport(rootURL);
	}

	private RepoTransport createRepoTransport(URL remoteRoot) {
		RepoTransportFactory repoTransportFactory = RepoTransportFactoryRegistry.getInstance().getRepoTransportFactoryOrFail(remoteRoot);
		return repoTransportFactory.createRepoTransport(remoteRoot);
	}

	private void sync(RepoTransport fromRepoTransport, RepoTransport toRepoTransport, ProgressMonitor monitor) {
		monitor.beginTask("Synchronising remotely...", 100);
		try {
			EntityID toRepositoryID = getRepositoryID(toRepoTransport);
			ChangeSet changeSet = fromRepoTransport.getChangeSet(toRepositoryID);
			monitor.worked(8);

			sync(fromRepoTransport, toRepoTransport, changeSet, new SubProgressMonitor(monitor, 90));

			fromRepoTransport.endSync(toRepositoryID);
			monitor.worked(2);
		} finally {
			monitor.done();
		}
	}

	private void sync(RepoTransport fromRepoTransport, RepoTransport toRepoTransport, ChangeSet changeSet, ProgressMonitor monitor) {
		monitor.beginTask("Synchronising remotely...", changeSet.getModificationDTOs().size() + changeSet.getRepoFileDTOs().size());
		try {
			for (ModificationDTO modificationDTO : changeSet.getModificationDTOs()) {
				syncModification(fromRepoTransport, toRepoTransport, modificationDTO, new SubProgressMonitor(monitor, 1));
			}

			RepoFileDTOTreeNode repoFileDTOTree = RepoFileDTOTreeNode.createTree(changeSet.getRepoFileDTOs());
			if (repoFileDTOTree != null) {
				for (RepoFileDTOTreeNode repoFileDTOTreeNode : repoFileDTOTree) {
					RepoFileDTO repoFileDTO = repoFileDTOTreeNode.getRepoFileDTO();
					if (repoFileDTO instanceof DirectoryDTO)
						syncDirectory(fromRepoTransport, toRepoTransport, repoFileDTOTreeNode, (DirectoryDTO) repoFileDTO, new SubProgressMonitor(monitor, 1));
					else if (repoFileDTO instanceof RepoFileDTO)
						syncFile(fromRepoTransport, toRepoTransport, repoFileDTOTreeNode, repoFileDTO, new SubProgressMonitor(monitor, 1));
					else
						throw new IllegalStateException("Unsupported RepoFileDTO type: " + repoFileDTO);
				}
			}
		} finally {
			monitor.done();
		}
	}

	private void syncModification(RepoTransport fromRepoTransport, RepoTransport toRepoTransport, ModificationDTO modificationDTO, ProgressMonitor monitor) {
		monitor.beginTask("Synchronising remotely...", 100);
		try {
			if (modificationDTO instanceof DeleteModificationDTO) {
				DeleteModificationDTO deleteModificationDTO = (DeleteModificationDTO) modificationDTO;
				toRepoTransport.delete(deleteModificationDTO.getPath());
			}
			else
				throw new IllegalStateException("Unknown modificationDTO type: " + modificationDTO);
		} finally {
			monitor.done();
		}
	}

	private void syncDirectory(RepoTransport fromRepoTransport, RepoTransport toRepoTransport, RepoFileDTOTreeNode repoFileDTOTreeNode, DirectoryDTO directoryDTO, ProgressMonitor monitor) {
		monitor.beginTask("Synchronising remotely...", 100);
		try {
			toRepoTransport.makeDirectory(repoFileDTOTreeNode.getPath(), directoryDTO.getLastModified());
		} finally {
			monitor.done();
		}
	}

	private void syncFile(RepoTransport fromRepoTransport, RepoTransport toRepoTransport, RepoFileDTOTreeNode repoFileDTOTreeNode, RepoFileDTO normalFileDTO, ProgressMonitor monitor) {
		monitor.beginTask("Synchronising remotely...", 100);
		try {
			// TODO the check-sums should be obtained simultaneously with 2 threads.
			FileChunkSet fromFileChunkSetResponse = fromRepoTransport.getFileChunkSet(repoFileDTOTreeNode.getPath());
			if (!assertNotNull("fromFileChunkSetResponse", fromFileChunkSetResponse).isFileExists()) {
				logger.warn("File was deleted during sync on source side: {}", repoFileDTOTreeNode.getPath());
				return;
			}
			assertNotNull("fromFileChunkSetResponse.lastModified", fromFileChunkSetResponse.getLastModified());
			monitor.worked(10);

			FileChunkSet toFileChunkSetResponse = toRepoTransport.getFileChunkSet(repoFileDTOTreeNode.getPath());
			if (areFilesExistingAndEqual(fromFileChunkSetResponse, assertNotNull("toFileChunkSetResponse", toFileChunkSetResponse))) {
				logger.info("File is already equal on destination side: {}", repoFileDTOTreeNode.getPath());
				return;
			}
			monitor.worked(10);

			List<FileChunk> fromFileChunksDirty = new ArrayList<FileChunk>();
			Iterator<FileChunk> toFileChunkIterator = toFileChunkSetResponse.getFileChunks().iterator();
			int fileChunkIndex = -1;
			for (FileChunk fromFileChunk : fromFileChunkSetResponse.getFileChunks()) {
				FileChunk toFileChunk = toFileChunkIterator.hasNext() ? toFileChunkIterator.next() : null;
				++fileChunkIndex;
				if (toFileChunk != null
						&& equal(fromFileChunk.getLength(), toFileChunk.getLength())
						&& equal(fromFileChunk.getSha1(), toFileChunk.getSha1())) {
					logger.debug("Skipping FileChunk {} (already equal on destination side). File: {}", fileChunkIndex, repoFileDTOTreeNode.getPath());
					continue;
				}
				fromFileChunksDirty.add(fromFileChunk);
			}

			toRepoTransport.beginFile(repoFileDTOTreeNode.getPath());
			monitor.worked(1);

			ProgressMonitor subMonitor = new SubProgressMonitor(monitor, 73);
			subMonitor.beginTask("Synchronising remotely...", fromFileChunksDirty.size());
			for (FileChunk fileChunk : fromFileChunksDirty) {
				byte[] fileData = fromRepoTransport.getFileData(repoFileDTOTreeNode.getPath(), fileChunk.getOffset(), fileChunk.getLength());

				if (fileData == null || fileData.length != fileChunk.getLength()) {
					logger.warn("Source file was modified or deleted during sync: {}", repoFileDTOTreeNode.getPath());
					// The file is left in state 'inProgress'. Thus it should definitely not be synced back in the opposite
					// direction. The file should be synced again in the correct direction in the next run (after the source
					// repo did a local sync, too).
					return;
				}

				toRepoTransport.putFileData(repoFileDTOTreeNode.getPath(), fileChunk.getOffset(), fileData);
				subMonitor.worked(1);
			}
			subMonitor.done();

			toRepoTransport.endFile(repoFileDTOTreeNode.getPath(), fromFileChunkSetResponse.getLastModified(), fromFileChunkSetResponse.getLength());
			monitor.worked(6);
		} finally {
			monitor.done();
		}
	}

	private boolean areFilesExistingAndEqual(FileChunkSet fromFileChunkSetResponse, FileChunkSet toFileChunkSetResponse) {
		return (fromFileChunkSetResponse.isFileExists()
				&& toFileChunkSetResponse.isFileExists()
				&& equal(fromFileChunkSetResponse.getLength(), toFileChunkSetResponse.getLength())
				&& equal(fromFileChunkSetResponse.getLastModified(), toFileChunkSetResponse.getLastModified())
				&& equal(fromFileChunkSetResponse.getSha1(), toFileChunkSetResponse.getSha1()));
	}

	public void close() {
		localRepoManager.close();
		localRepoTransport.close();
		remoteRepoTransport.close();
	}
}