package co.codewizards.cloudstore.client;

import static co.codewizards.cloudstore.core.util.Util.*;

import java.io.File;

import org.kohsuke.args4j.Argument;

import co.codewizards.cloudstore.core.repo.local.LocalRepoManager;
import co.codewizards.cloudstore.core.repo.local.LocalRepoManagerFactory;

/**
 * {@link SubCommand} implementation for showing information about a repository in the local file system.
 *
 * @author Marco หงุ่ยตระกูล-Schulze - marco at nightlabs dot de
 */
public class RepoInfoSubCommand extends SubCommand
{
	// TODO support sub-dirs!
	@Argument(metaVar="<local>", required=false, usage="A path inside a repository in the local file system. This may be the local repository's root or any directory inside it. If it is not specified, it defaults to the current working directory. NOTE: Sub-dirs are NOT YET SUPPORTED!")
	private String local;

	private File localFile;

	public RepoInfoSubCommand() { }

	protected RepoInfoSubCommand(File localRootFile) {
		this.localFile = assertNotNull("localFile", localRootFile);
		this.local = localRootFile.getPath();
	}

	@Override
	public String getSubCommandName() {
		return "repoInfo";
	}

	@Override
	public String getSubCommandDescription() {
		return "Show information about an existing repository.";
	}

	@Override
	public void prepare() throws Exception {
		super.prepare();

		if (local == null)
			localFile = new File("").getAbsoluteFile();
		else
			localFile = new File(local).getAbsoluteFile();

		local = localFile.getPath();
	}

	@Override
	public void run() throws Exception {
		// TODO find localRoot, if localFile is a sub-dir!
		LocalRepoManager localRepoManager = LocalRepoManagerFactory.getInstance().createLocalRepoManagerForExistingRepository(localFile);
		try {
			System.out.println("repository.localRoot = " + localRepoManager.getLocalRoot());
			System.out.println("repository.repositoryID = " + localRepoManager.getRepositoryID());
		} finally {
			localRepoManager.close();
		}
	}
}
