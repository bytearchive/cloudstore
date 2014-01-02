package co.codewizards.cloudstore.shared.repo.local;

import static co.codewizards.cloudstore.shared.util.DerbyUtil.*;
import static co.codewizards.cloudstore.shared.util.Util.*;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jdo.JDOHelper;
import javax.jdo.PersistenceManager;
import javax.jdo.PersistenceManagerFactory;

import co.codewizards.cloudstore.shared.dto.EntityID;
import co.codewizards.cloudstore.shared.persistence.Directory;
import co.codewizards.cloudstore.shared.persistence.LocalRepository;
import co.codewizards.cloudstore.shared.persistence.LocalRepositoryDAO;
import co.codewizards.cloudstore.shared.persistence.NormalFile;
import co.codewizards.cloudstore.shared.persistence.RemoteRepository;
import co.codewizards.cloudstore.shared.persistence.RemoteRepositoryDAO;
import co.codewizards.cloudstore.shared.persistence.Symlink;
import co.codewizards.cloudstore.shared.progress.ProgressMonitor;
import co.codewizards.cloudstore.shared.util.IOUtil;
import co.codewizards.cloudstore.shared.util.PropertiesUtil;

/**
 * Manager of a repository.
 * <p>
 * All operations on a repository are performed via this manager (or an object associated with it).
 * <p>
 * For every repository (identified by its root directory) there is one single instance. Use the
 * {@link LocalRepoManagerFactory} to obtain a {@code LocalRepoManager}.
 * @author Marco หงุ่ยตระกูล-Schulze - marco at codewizards dot co
 */
class LocalRepoManagerImpl implements LocalRepoManager {

	private static final String VAR_LOCAL_ROOT = "repository.localRoot";
	private static final String VAR_META_DIR = "repository.metaDir";

	private static final String PERSISTENCE_PROPERTIES_FILE_NAME = "cloudstore-persistence.properties";

	private static final String CONNECTION_URL_KEY = "javax.jdo.option.ConnectionURL";

	private final File localRoot;
	private EntityID repositoryID;
	private PersistenceManagerFactory persistenceManagerFactory;
	private final AtomicInteger openReferenceCounter = new AtomicInteger();
	private List<LocalRepoManagerCloseListener> localRepoManagerCloseListeners = new CopyOnWriteArrayList<LocalRepoManagerCloseListener>();
	private String connectionURL;

	private boolean deleteMetaDir;

	protected LocalRepoManagerImpl(File localRoot, boolean createRepository) throws LocalRepoManagerException {
		this.localRoot = assertValidLocalRoot(localRoot);
		deleteMetaDir = false; // only delete, if it is created in initMetaDirectory(...)
		try {
			// TODO Make this more robust: If we have a power-outage between directory creation and the finally block,
			// we end in an inconsistent state. We can avoid this by tracking the creation process in a properties
			// file later (somehow making this really transactional).
			initMetaDir(createRepository);
			initPersistenceManagerFactory(createRepository);
			deleteMetaDir = false; // if we come here, creation is successful => NO deletion
		} finally {
			if (deleteMetaDir)
				IOUtil.deleteDirectoryRecursively(getMetaDir());
		}
	}

	protected void open() {
		openReferenceCounter.incrementAndGet();
	}

	private File assertValidLocalRoot(File localRoot) {
		assertNotNull("localRoot", localRoot);

		if (!localRoot.exists())
			throw new FileNotFoundException(localRoot);

		if (!localRoot.isDirectory())
			throw new FileNoDirectoryException(localRoot);

		assertNotInsideOtherRepository(localRoot);
		return localRoot;
	}

	private void assertNotInsideOtherRepository(File localRoot) {
		File parentFile = localRoot.getParentFile();
		while (parentFile != null) {
			File parentMetaDir = new File(parentFile, META_DIR_NAME);
			if (parentMetaDir.exists()) {
				throw new FileAlreadyRepositoryException(localRoot);
			}
			parentFile = parentFile.getParentFile();
		}
	}

	private void initMetaDir(boolean createRepository) throws LocalRepoManagerException {
		File metaDirectory = getMetaDir();
		if (createRepository) {
			if (metaDirectory.exists()) {
				throw new FileAlreadyRepositoryException(localRoot);
			}

			deleteMetaDir = true;
			metaDirectory.mkdir();

			try {
				IOUtil.copyResource(LocalRepoManagerImpl.class, "/" + PERSISTENCE_PROPERTIES_FILE_NAME, new File(metaDirectory, PERSISTENCE_PROPERTIES_FILE_NAME));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		else {
			if (!metaDirectory.exists()) {
				throw new FileNoRepositoryException(localRoot);
			}
		}
	}

	private void initPersistenceManagerFactory(boolean createRepository) throws LocalRepoManagerException {
		Map<String, String> persistenceProperties = getPersistenceProperties(createRepository);
		persistenceManagerFactory = JDOHelper.getPersistenceManagerFactory(persistenceProperties );
		PersistenceManager pm = persistenceManagerFactory.getPersistenceManager();
		try {
			initPersistenceCapableClasses(pm);

			pm.currentTransaction().begin();

			if (createRepository) {
				createAndPersistLocalRepository(pm);
			} else {
				assertSinglePersistentLocalRepository(pm);
			}

			pm.currentTransaction().commit();
		} finally {
			if (pm.currentTransaction().isActive())
				pm.currentTransaction().rollback();

			pm.close();
		}
	}

	private void initPersistenceCapableClasses(PersistenceManager pm) {
		pm.getExtent(Directory.class);
		pm.getExtent(LocalRepository.class);
		pm.getExtent(NormalFile.class);
		pm.getExtent(RemoteRepository.class);
		pm.getExtent(Symlink.class);
	}

	private void assertSinglePersistentLocalRepository(PersistenceManager pm) {
		try {
			LocalRepository localRepository = new LocalRepositoryDAO().persistenceManager(pm).getLocalRepositoryOrFail();
			repositoryID = localRepository.getEntityID();
		} catch (IllegalStateException x) {
			throw new RepositoryCorruptException(localRoot, x.getMessage());
		}
	}

	private void createAndPersistLocalRepository(PersistenceManager pm) {
		LocalRepository localRepository = new LocalRepository();
		Directory root = new Directory();
		root.setName("");
		localRepository.setRoot(root);
		localRepository = pm.makePersistent(localRepository);
		repositoryID = localRepository.getEntityID();
	}

	private File getMetaDir() {
		return new File(localRoot, META_DIR_NAME);
	}

	private Map<String, String> getPersistenceProperties(boolean createRepository) {
		File metaDirectory = getMetaDir();
		File persistencePropertiesFile = new File(metaDirectory, PERSISTENCE_PROPERTIES_FILE_NAME);

		Map<String, String> variablesMap = new HashMap<String, String>();
		variablesMap.put(VAR_LOCAL_ROOT, localRoot.getPath());
		variablesMap.put(VAR_META_DIR, getMetaDir().getPath());

		Properties rawProperties;
		try {
			rawProperties = PropertiesUtil.load(persistencePropertiesFile);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		Map<String, String> persistenceProperties = PropertiesUtil.filterProperties(rawProperties, variablesMap);
		connectionURL = persistenceProperties.get(CONNECTION_URL_KEY);

		if (createRepository) {
			modifyConnectionURLForCreate(persistenceProperties);
		}
		return persistenceProperties;
	}

	private void modifyConnectionURLForCreate(Map<String, String> persistenceProperties) {
		String value = connectionURL;
		if (value == null || value.trim().isEmpty()) {
			throw new RepositoryCorruptException(localRoot,
					String.format("Property '%s' missing in '%s'.", CONNECTION_URL_KEY, PERSISTENCE_PROPERTIES_FILE_NAME));
		}

		String newValue = value.trim() + ";create=true";
		persistenceProperties.put(CONNECTION_URL_KEY, newValue);
	}

	@Override
	public File getLocalRoot() {
		return localRoot;
	}

	protected PersistenceManagerFactory getPersistenceManagerFactory() {
		return persistenceManagerFactory;
	}

	@Override
	public void addLocalRepoManagerCloseListener(LocalRepoManagerCloseListener listener) {
		localRepoManagerCloseListeners.add(listener);
	}

	@Override
	public void removeLocalRepoManagerCloseListener(LocalRepoManagerCloseListener listener) {
		localRepoManagerCloseListeners.remove(listener);
	}

	@Override
	public void close() {
		int openReferenceCounterValue = openReferenceCounter.decrementAndGet();
		if (openReferenceCounterValue > 0) {
			return;
		}
		if (openReferenceCounterValue < 0) {
			throw new IllegalStateException("openReferenceCounterValue < 0");
		}

		// TODO defer this (don't immediately close)
		// TODO the timeout should be configurable
		LocalRepoManagerCloseEvent event = new LocalRepoManagerCloseEvent(this, this, true);
		for (LocalRepoManagerCloseListener listener : localRepoManagerCloseListeners) {
			listener.preClose(event);
		}

		synchronized (this) {
			if (persistenceManagerFactory != null) {
				persistenceManagerFactory.close();
				persistenceManagerFactory = null;
				shutdownDerbyDatabase(connectionURL);
			}
		}

		for (LocalRepoManagerCloseListener listener : localRepoManagerCloseListeners) {
			listener.postClose(event);
		}
	}

	@Override
	public EntityID getLocalRepositoryID() {
		return repositoryID;
	}

	@Override
	public synchronized boolean isOpen() {
		return persistenceManagerFactory != null;
	}

	protected void assertOpen() {
		if (!isOpen())
			throw new IllegalStateException("This LocalRepoManagerImpl is closed!");
	}

	@Override
	public synchronized LocalRepoTransaction beginTransaction() {
		assertOpen();
		return new LocalRepoTransaction(this);
	}

	@Override
	public void localSync(ProgressMonitor monitor) { // TODO use this monitor properly (commit might take a bit)
		LocalRepoTransaction transaction = beginTransaction();
		try {
			new LocalRepositorySync(transaction).sync(monitor);
			transaction.commit();
		} finally {
			transaction.rollbackIfActive();
		}
	}

	@Override
	public void addRemoteRepository(EntityID repositoryID, URL remoteRoot) {
		assertNotNull("entityID", repositoryID);
		assertNotNull("remoteRoot", remoteRoot);
		LocalRepoTransaction transaction = beginTransaction();
		try {
			RemoteRepositoryDAO remoteRepositoryDAO = transaction.createDAO(RemoteRepositoryDAO.class);
			RemoteRepository remoteRepository = new RemoteRepository(repositoryID);
			remoteRepository.setRemoteRoot(remoteRoot);
			remoteRepository.setRevision(-1);
			remoteRepositoryDAO.makePersistent(remoteRepository);
			transaction.commit();
		} finally {
			transaction.rollbackIfActive();
		}
	}

	@Override
	public void moveRemoteRepository(EntityID repositoryID, URL newRemoteRoot) {
		assertNotNull("entityID", repositoryID);
		assertNotNull("newRemoteRoot", newRemoteRoot);
		LocalRepoTransaction transaction = beginTransaction();
		try {
			RemoteRepositoryDAO remoteRepositoryDAO = transaction.createDAO(RemoteRepositoryDAO.class);
			RemoteRepository remoteRepository = remoteRepositoryDAO.getObjectByIdOrFail(repositoryID);
			remoteRepository.setRemoteRoot(newRemoteRoot);
			remoteRepositoryDAO.makePersistent(remoteRepository);
			transaction.commit();
		} finally {
			transaction.rollbackIfActive();
		}
	}

	@Override
	public void deleteRemoteRepository(EntityID repositoryID) {
		assertNotNull("entityID", repositoryID);
		LocalRepoTransaction transaction = beginTransaction();
		try {
			RemoteRepositoryDAO remoteRepositoryDAO = transaction.createDAO(RemoteRepositoryDAO.class);
			RemoteRepository remoteRepository = remoteRepositoryDAO.getObjectByIdOrNull(repositoryID);
			if (remoteRepository != null)
				remoteRepositoryDAO.deletePersistent(remoteRepository);

			transaction.commit();
		} finally {
			transaction.rollbackIfActive();
		}
	}
}
