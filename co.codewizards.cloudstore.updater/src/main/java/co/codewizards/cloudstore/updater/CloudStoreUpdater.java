package co.codewizards.cloudstore.updater;

import static co.codewizards.cloudstore.core.util.Util.*;

import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;
import co.codewizards.cloudstore.core.config.ConfigDir;
import co.codewizards.cloudstore.core.updater.CloudStoreUpdaterCore;
import co.codewizards.cloudstore.core.util.IOUtil;

public class CloudStoreUpdater extends CloudStoreUpdaterCore {
	private static final Logger logger = LoggerFactory.getLogger(CloudStoreUpdater.class);

	private final String[] args;
	private boolean throwException = true;

	@Option(name="-installationDir", required=true, usage="Base-directory of the installation containing the 'bin' directory as well as the 'installation.properties' file - e.g. '/opt/cloudstore'. The installation in this directory will be updated.")
	private String installationDir;
	private File installationDirFile;

	private Properties remoteUpdateProperties;
	private File tempDownloadDir;

	public static void main(String[] args) throws Exception {
		initLogging();
		try {
			int programExitStatus = new CloudStoreUpdater(args).throwException(false).execute();
			System.exit(programExitStatus);
		} catch (Throwable x) {
			logger.error(x.toString(), x);
			System.exit(999);
		}
	}

	public CloudStoreUpdater(String[] args) {
		this.args = args;
	}

	public boolean isThrowException() {
		return throwException;
	}
	public void setThrowException(boolean throwException) {
		this.throwException = throwException;
	}
	public CloudStoreUpdater throwException(boolean throwException) {
		setThrowException(throwException);
		return this;
	}

	public int execute() throws Exception {
		int programExitStatus = 1;
		CmdLineParser parser = new CmdLineParser(this);
		try {
			parser.parseArgument(args);
			this.run();
			programExitStatus = 0;
		} catch (CmdLineException e) {
			// handling of wrong arguments
			programExitStatus = 2;
			System.err.println("Error: " + e.getMessage());
			System.err.println();
			if (throwException)
				throw e;
		} catch (Exception x) {
			programExitStatus = 3;
			logger.error(x.toString(), x);
			if (throwException)
				throw x;
		}
		return programExitStatus;
	}

	private static void initLogging() throws IOException, JoranException {
		ConfigDir.getInstance().getLogDir();

		final String logbackXmlName = "logback.updater.xml";
		File logbackXmlFile = new File(ConfigDir.getInstance().getFile(), logbackXmlName);
		if (!logbackXmlFile.exists())
			IOUtil.copyResource(CloudStoreUpdater.class, logbackXmlName, logbackXmlFile);

		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
	    try {
	      JoranConfigurator configurator = new JoranConfigurator();
	      configurator.setContext(context);
	      // Call context.reset() to clear any previous configuration, e.g. default
	      // configuration. For multi-step configuration, omit calling context.reset().
	      context.reset();
	      configurator.doConfigure(logbackXmlFile);
	    } catch (JoranException je) {
	    	// StatusPrinter will handle this
	    	doNothing();
	    }
	    StatusPrinter.printInCaseOfErrorsOrWarnings(context);
	}

	private void run() throws Exception {
		System.out.println("CloudStore updater started. Downloading meta-data.");

		boolean restoreRenamedFiles = false;
		try {
			final File downloadFile = downloadURLViaRemoteUpdateProperties("artifact[${artifactId}].downloadURL");
			final File signatureFile = downloadURLViaRemoteUpdateProperties("artifact[${artifactId}].signatureURL");

			System.out.println("Verifying PGP signature.");
			new PGPVerifier().verify(downloadFile, signatureFile);

			checkAvailableDiskSpace(getInstallationDir(), downloadFile.length() * 5);

			final File backupDir = getBackupDir();
			backupDir.mkdirs();
			final File backupTarGzFile = new File(backupDir, resolve(String.format("${artifactId}-${localVersion}.backup-%s.tar.gz", Long.toString(System.currentTimeMillis(), 36))));
			System.out.println("Creating backup: " + backupTarGzFile);

			new TarGzFile(backupTarGzFile)
			.fileFilter(fileFilterIgnoringBackupAndUpdaterDir)
			.compress(getInstallationDir());

			// Because of f***ing Windows and its insane file-locking, we first try to move all
			// files out of the way by renaming them. If this fails, we restore the previous
			// state. This way, we increase the probability that we leave a consistent state.
			// If a file is locked, this should fail already now, rather than later after we extracted
			// half of the tarball.
			System.out.println("Renaming files in installation directory: " + getInstallationDir());
			restoreRenamedFiles = true;
			renameFiles(getInstallationDir(), fileFilterIgnoringBackupAndUpdaterDir);

			System.out.println("Overwriting installation directory: " + getInstallationDir());
			final Set<File> keepFiles = new HashSet<>();
			keepFiles.add(getInstallationDir());
			populateFilesRecursively(getBackupDir(), keepFiles);
			populateFilesRecursively(getUpdaterDir(), keepFiles);

			new TarGzFile(downloadFile)
			.tarGzEntryNameConverter(new ExtractTarGzEntryNameConverter())
			.fileFilter(new FileFilterTrackingExtractedFiles(keepFiles))
			.extract(getInstallationDir());

			restoreRenamedFiles = false;

			System.out.println("Deleting old files from installation directory: " + getInstallationDir());
			deleteAllExcept(getInstallationDir(), keepFiles);
		} finally {
			if (restoreRenamedFiles)
				restoreRenamedFiles(getInstallationDir());

			if (tempDownloadDir != null) {
				System.out.println("Deleting temporary download-directory.");
				IOUtil.deleteDirectoryRecursively(tempDownloadDir);
			}
		}
		System.out.println("Update successfully done. Exiting.");
	}

	private void checkAvailableDiskSpace(final File dir, final long expectedRequiredSpace) throws IOException {
		final long usableSpace = dir.getUsableSpace();
		logger.debug("checkAvailableDiskSpace: dir='{}' dir.usableSpace='{} MiB' expectedRequiredSpace='{} MiB'",
				dir, usableSpace / 1024 / 1024, expectedRequiredSpace / 1024 / 1024);

		if (usableSpace < expectedRequiredSpace) {
			final String msg = String.format("Insufficient disk space! The file system of the directory '%s' has %s MiB (%s B) available, but %s MiB (%s B) are required!",
					dir, usableSpace / 1024 / 1024, usableSpace, expectedRequiredSpace / 1024 / 1024, expectedRequiredSpace);
			logger.error("checkAvailableDiskSpace: " + msg);
			throw new IOException(msg);
		}
	}

	private static final String RENAMED_FILE_SUFFIX = ".csupdbak";

	private void renameFiles(final File dir, final FileFilter fileFilter) throws IOException {
		final File[] children = dir.listFiles(fileFilter);
		if (children != null) {
			for (final File child : children) {
				if (child.isDirectory())
					renameFiles(child, fileFilter);
				else {
					final File newChild = new File(dir, child.getName() + RENAMED_FILE_SUFFIX);
					logger.debug("renameFiles: file='{}', newName='{}'", child, newChild.getName());
					if (!child.renameTo(newChild)) {
						final String msg = String.format("Failed to rename the file '%s' to '%s' (in the same directory)!", child, newChild.getName());
						logger.error("renameFiles: {}", msg);
						throw new IOException(msg);
					}
				}
			}
		}
	}

	private void restoreRenamedFiles(File dir) {
		final File[] children = dir.listFiles();
		if (children != null) {
			for (final File child : children) {
				if (child.isDirectory())
					restoreRenamedFiles(child);
				else if (child.getName().endsWith(RENAMED_FILE_SUFFIX)) {
					final File newChild = new File(dir, child.getName().substring(0, child.getName().length() - RENAMED_FILE_SUFFIX.length()));
					logger.debug("restoreRenamedFiles: file='{}', newName='{}'", child, newChild.getName());
					newChild.delete();
					if (!child.renameTo(newChild))
						logger.warn("restoreRenamedFiles: Failed to rename the file '{}' back to its original name '{}' (in the same directory)!", child, newChild.getName());
				}
			}
		}
	}

	private static class FileFilterTrackingExtractedFiles implements FileFilter {
		private final Collection<File> files;

		public FileFilterTrackingExtractedFiles(Collection<File> files) {
			this.files = assertNotNull("files", files);
		}

		@Override
		public boolean accept(File file) {
			files.add(file);
			files.add(file.getParentFile()); // just in case the parent didn't have its own entry and was created implicitly
			return true;
		}
	}

	private static class ExtractTarGzEntryNameConverter implements TarGzEntryNameConverter {
		@Override
		public String getEntryName(final File rootDir, final File file) { throw new UnsupportedOperationException(); }

		@Override
		public File getFile(final File rootDir, String entryName) {
			final String prefix = "cloudstore/";
			if (entryName.startsWith(prefix))
				entryName = entryName.substring(prefix.length());

			return entryName.isEmpty() ? rootDir : new File(rootDir, entryName);
		}
	}

	private void populateFilesRecursively(final File fileOrDir, final Set<File> files) {
		assertNotNull("fileOrDir", fileOrDir);
		assertNotNull("files", files);
		files.add(fileOrDir);
		final File[] children = fileOrDir.listFiles();
		if (children != null) {
			for (File child : children)
				populateFilesRecursively(child, files);
		}
	}

	private void deleteAllExcept(final File fileOrDir, final Set<File> keepFiles) {
		assertNotNull("fileOrDir", fileOrDir);
		assertNotNull("keepFiles", keepFiles);
		if (keepFiles.contains(fileOrDir)) {
			logger.debug("deleteAllExcept: Keeping: {}", fileOrDir);
			final File[] children = fileOrDir.listFiles();
			if (children != null) {
				for (File child : children)
					deleteAllExcept(child, keepFiles);
			}
		}
		else {
			logger.debug("deleteAllExcept: Deleting: {}", fileOrDir);
			IOUtil.deleteDirectoryRecursively(fileOrDir);
		}
	}

	private File downloadURLViaRemoteUpdateProperties(final String remoteUpdatePropertiesKey) {
		logger.debug("downloadURLViaRemoteUpdateProperties: remoteUpdatePropertiesKey='{}'", remoteUpdatePropertiesKey);
		final String resolvedKey = resolve(remoteUpdatePropertiesKey);
		final String urlStr = getRemoteUpdateProperties().getProperty(resolvedKey);
		if (urlStr == null || urlStr.trim().isEmpty())
			throw new IllegalStateException("No value for key in remoteUpdateProperties: " + resolvedKey);

		final String resolvedURLStr = resolve(urlStr);
		logger.debug("downloadURLViaRemoteUpdateProperties: resolvedURLStr='{}'", resolvedURLStr);

		final File tempDownloadDir = getTempDownloadDir();

		try {
			System.out.println("Downloading: " + resolvedURLStr);
			final URL url = new URL(resolvedURLStr);
			long contentLength = url.openConnection().getContentLengthLong();
			if (contentLength < 0)
				logger.warn("downloadURLViaRemoteUpdateProperties: contentLength unknown! url='{}'", url);
			else {
				logger.debug("downloadURLViaRemoteUpdateProperties: contentLength={} url='{}'", contentLength, url);
				checkAvailableDiskSpace(tempDownloadDir, Math.max(1024 * 1024, contentLength * 3 / 2));
			}

			final String path = url.getPath();
			final int lastSlashIndex = path.lastIndexOf('/');
			if (lastSlashIndex < 0)
				throw new IllegalStateException("No '/' found in URL?!");

			final String fileName = path.substring(lastSlashIndex + 1);
			final File downloadFile = new File(tempDownloadDir, fileName);

			boolean successful = false;
			final InputStream in = url.openStream();
			try {
				final FileOutputStream out = new FileOutputStream(downloadFile);
				try {
					IOUtil.transferStreamData(in, out);
				} finally {
					out.close();
				}
				successful = true;
			} finally {
				in.close();

				if (!successful)
					downloadFile.delete();
			}

			return downloadFile;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private File getTempDownloadDir() {
		if (tempDownloadDir == null) {
			try {
				tempDownloadDir = IOUtil.createUniqueRandomFolder(IOUtil.getTempDir(), "cloudstore-update-");
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return tempDownloadDir;
	}

	/**
	 * Gets the installation directory that was passed as command line parameter.
	 */
	@Override
	protected File getInstallationDir() {
		if (installationDirFile == null) {
			final String path = IOUtil.simplifyPath(new File(assertNotNull("installationDir", installationDir)));
			final File f = new File(path);
			if (!f.exists())
				throw new IllegalArgumentException(String.format("installationDir '%s' (specified as '%s') does not exist!", f, installationDir));

			if (!f.isDirectory())
				throw new IllegalArgumentException(String.format("installationDir '%s' (specified as '%s') is not a directory!", f, installationDir));

			installationDirFile = f;
		}
		return installationDirFile;
	}

	private Properties getRemoteUpdateProperties() {
		if (remoteUpdateProperties == null) {
			final String resolvedRemoteUpdatePropertiesURL = resolve(remoteUpdatePropertiesURL);
			final Properties properties = new Properties();
			try {
				final URL url = new URL(resolvedRemoteUpdatePropertiesURL);
				final InputStream in = url.openStream();
				try {
					properties.load(in);
				} finally {
					in.close();
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			remoteUpdateProperties = properties;
		}
		return remoteUpdateProperties;
	}

	private static final void doNothing() { }
}