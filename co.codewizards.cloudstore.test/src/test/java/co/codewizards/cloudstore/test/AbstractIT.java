package co.codewizards.cloudstore.test;

import static co.codewizards.cloudstore.core.oio.file.FileFactory.*;
import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;

import co.codewizards.cloudstore.core.config.ConfigDir;
import co.codewizards.cloudstore.core.oio.file.File;
import co.codewizards.cloudstore.core.repo.local.LocalRepoManager;
import co.codewizards.cloudstore.core.repo.local.LocalRepoManagerFactory;
import co.codewizards.cloudstore.core.repo.local.LocalRepoTransaction;
import co.codewizards.cloudstore.core.repo.transport.RepoTransportFactoryRegistry;
import co.codewizards.cloudstore.core.util.IOUtil;
import co.codewizards.cloudstore.local.FilenameFilterSkipMetaDir;
import co.codewizards.cloudstore.local.persistence.Directory;
import co.codewizards.cloudstore.local.persistence.NormalFile;
import co.codewizards.cloudstore.local.persistence.RepoFile;
import co.codewizards.cloudstore.local.persistence.RepoFileDao;
import co.codewizards.cloudstore.local.persistence.Symlink;
import co.codewizards.cloudstore.rest.client.ssl.CheckServerTrustedCertificateExceptionContext;
import co.codewizards.cloudstore.rest.client.ssl.CheckServerTrustedCertificateExceptionResult;
import co.codewizards.cloudstore.rest.client.ssl.DynamicX509TrustManagerCallback;
import co.codewizards.cloudstore.rest.client.transport.RestRepoTransportFactory;

public abstract class AbstractIT {
	static {
		System.setProperty(ConfigDir.SYSTEM_PROPERTY_CONFIG_DIR, "target/.cloudstore");
		System.setProperty(LocalRepoManager.SYSTEM_PROPERTY_KEY_SIZE, "1024");
	}

	protected static final SecureRandom random = new SecureRandom();
	private static final CloudStoreServerTestSupport cloudStoreServerTestSupport = new CloudStoreServerTestSupport();

	public static int getSecurePort() {
		return cloudStoreServerTestSupport.getSecurePort();
	}

	public static String getSecureUrl() {
		return cloudStoreServerTestSupport.getSecureUrl();
	}

	private static RestRepoTransportFactory restRepoTransportFactory;

	public static class TestDynamicX509TrustManagerCallback implements DynamicX509TrustManagerCallback {
		@Override
		public CheckServerTrustedCertificateExceptionResult handleCheckServerTrustedCertificateException(final CheckServerTrustedCertificateExceptionContext context) {
			final CheckServerTrustedCertificateExceptionResult result = new CheckServerTrustedCertificateExceptionResult();
			result.setTrusted(true);
			return result;
		}
	}

	@BeforeClass
	public static void abstractIT_beforeClass() {
		if (cloudStoreServerTestSupport.beforeClass()) {
			// *IMPORTANT* We run *all* tests in parallel in the same JVM. Therefore, we must - in this entire project - *not*
			// set any other dynamicX509TrustManagerCallbackClass!!! This setting is JVM-wide!
			restRepoTransportFactory = RepoTransportFactoryRegistry.getInstance().getRepoTransportFactoryOrFail(RestRepoTransportFactory.class);
			restRepoTransportFactory.setDynamicX509TrustManagerCallbackClass(TestDynamicX509TrustManagerCallback.class);
		}
	}

	@AfterClass
	public static void abstractIT_afterClass() {
		if (cloudStoreServerTestSupport.afterClass()) {
			restRepoTransportFactory.setDynamicX509TrustManagerCallbackClass(null);
			restRepoTransportFactory = null;
		}
	}

	protected static LocalRepoManagerFactory localRepoManagerFactory = LocalRepoManagerFactory.Helper.getInstance();
	private final Map<File, Set<File>> localRoot2FilesInRepo = new HashMap<File, Set<File>>();

	protected File newTestRepositoryLocalRoot(final String suffix) throws IOException {
		assertThat(suffix).isNotNull();
		final long timestamp = System.currentTimeMillis();
		final int randomNumber = random.nextInt(BigInteger.valueOf(36).pow(5).intValue());
		final String repoName = Long.toString(timestamp, 36) + '-' + Integer.toString(randomNumber, 36) + (suffix.isEmpty() ? "" : "-") + suffix;
		final File localRoot = newFile(getTestRepositoryBaseDir(), repoName);
		addToFilesInRepo(localRoot, localRoot);
		return localRoot;
	}

	protected File getTestRepositoryBaseDir() {
		final File dir = newFile(newFile("target"), "repo");
		dir.mkdirs();
		return dir;
	}

	@Before
	public void before() {
		localRoot2FilesInRepo.clear();
	}

	protected File createDirectory(final File parent, final String name) throws IOException {
		final File dir = newFile(parent, name);
		return createDirectory(dir);
	}
	protected File createDirectory(final File dir) throws IOException {
		assertThat(dir.exists()).isFalse();
		dir.mkdir();
		assertThat(dir.isDirectory()).isTrue();
		addToFilesInRepo(dir);
		return dir;
	}

	protected void addToFilesInRepo(File file) throws IOException {
		file = file.getAbsoluteFile();
		final File localRoot = getLocalRootOrFail(file);
		addToFilesInRepo(localRoot, file);
	}
	protected void addToFilesInRepo(File localRoot, File file) throws IOException {
		localRoot = localRoot.getCanonicalFile();
		file = file.getAbsoluteFile();
		Set<File> filesInRepo = localRoot2FilesInRepo.get(localRoot);
		if (filesInRepo == null) {
			filesInRepo = new HashSet<File>();
			localRoot2FilesInRepo.put(localRoot, filesInRepo);
		}
		filesInRepo.add(file);
	}

	protected File createFileWithRandomContent(final File parent, final String name) throws IOException {
		final File file = newFile(parent, name);
		return createFileWithRandomContent(file);
	}

	protected File createFileWithRandomContent(final File file) throws IOException {
		assertThat(file.exists()).isFalse(); // prevent accidentally overwriting important data ;-)
		final OutputStream out = file.createFileOutputStream();
		final byte[] buf = new byte[1 + random.nextInt(10241)];
		final int loops = 1 + random.nextInt(100);
		for (int i = 0; i < loops; ++i) {
			random.nextBytes(buf);
			out.write(buf);
		}
		out.close();
		assertThat(file.isFile()).isTrue();
		addToFilesInRepo(file);
		return file;
	}

	/** TODO Remove duplicate code: AbstractIT.java and AbstractTest.java */
	protected File createRelativeSymlink(final File symlink, final File target) throws IOException {
		assertThat(symlink.exists()).isFalse();
		final File symlinkParent = symlink.getParentFile();

//		final Path symlinkParentPath = symlink.getParentFile().toPath();
//		final Path symlinkPath = symlink.toPath();
//		final Path relativeTargetPath = symlinkParentPath.relativize(target.toPath());
//		final Path symbolicLink = Files.createSymbolicLink(symlinkPath, relativeTargetPath);

		final String relativeTargetString = symlinkParent.relativize(target);
		final String symbolicLinkString = symlink.createSymbolicLink(relativeTargetString);
		final File symLinkFile = newFile(symbolicLinkString);
		assertThat(symLinkFile.getAbsoluteFile()).isEqualTo(symlink.getAbsoluteFile());
//		assertThat(Files.exists(symlinkPath, LinkOption.NOFOLLOW_LINKS)).isTrue();
		assertThat(symLinkFile.existsNoFollow()).isTrue();
		addToFilesInRepo(symlink);
		return symlink;
	}

	protected void deleteFile(File file) throws IOException {
		file = file.getAbsoluteFile();
		assertThat(file.exists()).isTrue();
		file.delete();
		assertThat(file.exists()).isFalse();;

		final File localRoot = getLocalRootOrFail(file);
		final Set<File> filesInRepo = localRoot2FilesInRepo.get(localRoot);
		if (filesInRepo == null)
			throw new IllegalStateException("No filesInRepo for localRoot: " + localRoot);

		if (!filesInRepo.remove(file))
			throw new IllegalStateException("File did not exist in filesInRepo: " + file);
	}

	private File getLocalRootOrFail(final File file) throws IOException {
		final String filePath = file.getCanonicalPath();
		final Set<File> localRoots = localRepoManagerFactory.getLocalRoots();
		for (final File localRoot : localRoots) {
			final String localRootPath = localRoot.getPath();
			if (filePath.startsWith(localRootPath)) {
				return localRoot;
			}
		}
		throw new IllegalArgumentException("file is not contained in any open repository: " + filePath);
	}

	protected void assertThatFilesInRepoAreCorrect(File localRoot) {
		final LocalRepoManager localRepoManager = LocalRepoManagerFactory.Helper.getInstance().createLocalRepoManagerForExistingRepository(localRoot);
		localRoot = localRepoManager.getLocalRoot(); // get canonical File
		final LocalRepoTransaction transaction = localRepoManager.beginReadTransaction();
		try {
			final RepoFileDao repoFileDao = transaction.getDao(RepoFileDao.class);
			Set<File> filesInRepo = localRoot2FilesInRepo.get(localRoot);
			assertThat(filesInRepo).isNotNull();

			for (final File file : filesInRepo) {
				final RepoFile repoFile = repoFileDao.getRepoFile(localRoot, file);
				if (repoFile == null) {
					Assert.fail("Corresponding RepoFile missing in repository for file: " + file);
				}
				if (file.isSymbolicLink())
					assertThat(repoFile).isInstanceOf(Symlink.class);
				else if (file.isFile())
					assertThat(repoFile).isInstanceOf(NormalFile.class);
				else if (file.isDirectory())
					assertThat(repoFile).isInstanceOf(Directory.class);
			}

			filesInRepo = new HashSet<File>(filesInRepo);
			final Collection<RepoFile> repoFiles = repoFileDao.getObjects();
			final Map<File, RepoFile> file2RepoFile = new HashMap<File, RepoFile>();
			for (final RepoFile repoFile : repoFiles) {
				final File file = repoFile.getFile(localRoot);
				final RepoFile duplicateRepoFile = file2RepoFile.put(file, repoFile);
				if (duplicateRepoFile != null)
					Assert.fail("There are 2 RepoFile instances for the same file! " + repoFile + " " + duplicateRepoFile + " " + file);

				if (!filesInRepo.remove(file))
					Assert.fail("Corresponding file in file-system missing for RepoFile: " + repoFile + " " + file);
			}
		} finally {
			transaction.rollbackIfActive();
			localRepoManager.close();
		}
	}

	protected void assertDirectoriesAreEqualRecursively(final File dir1, final File dir2) throws IOException {
		assertThat(dir1.isDirectory()).isTrue();
		assertThat(dir2.isDirectory()).isTrue();

		final boolean dir1IsSymbolicLink = dir1.isSymbolicLink();
		final boolean dir2IsSymbolicLink = dir2.isSymbolicLink();

		assertThat(dir1IsSymbolicLink).isEqualTo(dir2IsSymbolicLink);

		if (dir1IsSymbolicLink) {
			final String target1 = dir1.readSymbolicLinkToPathString();
			final String target2 = dir2.readSymbolicLinkToPathString();
			assertThat(target1).isEqualTo(target2);
			return;
		}

		final String[] children1 = dir1.list(new FilenameFilterSkipMetaDir());
		assertThat(children1).isNotNull();

		final String[] children2 = dir2.list(new FilenameFilterSkipMetaDir());
		assertThat(children2).isNotNull();

		Arrays.sort(children1);
		Arrays.sort(children2);

		assertThat(children1).containsOnly(children2);

		for (final String childName : children1) {
			final File child1 = newFile(dir1, childName);
			final File child2 = newFile(dir2, childName);

			final boolean child1IsSymbolicLink = child1.isSymbolicLink();
			final boolean child2IsSymbolicLink = child2.isSymbolicLink();

			assertThat(child1IsSymbolicLink).isEqualTo(child2IsSymbolicLink);

			if (child1IsSymbolicLink) {
				final String target1 = child1.readSymbolicLinkToPathString();
				final String target2 = child2.readSymbolicLinkToPathString();
				assertThat(target1).isEqualTo(target2);
			}
			else if (child1.isFile()) {
				assertThat(child2.isFile());
				assertThat(IOUtil.compareFiles(child1, child2)).isTrue();
			}
			else if (child1.isDirectory())
				assertDirectoriesAreEqualRecursively(child1, child2);
		}
	}

}
