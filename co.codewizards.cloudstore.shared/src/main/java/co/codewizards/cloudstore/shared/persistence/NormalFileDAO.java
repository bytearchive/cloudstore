package co.codewizards.cloudstore.shared.persistence;

import static co.codewizards.cloudstore.shared.util.Util.*;

import java.util.ArrayList;
import java.util.Collection;

import javax.jdo.Query;

public class NormalFileDAO extends DAO<NormalFile, NormalFileDAO> {

	/**
	 * Get those {@link RepoFile}s whose {@link RepoFile#getSha1() sha1} and {@link RepoFile#getLength() length}
	 * match the given parameters.
	 * @param sha1 the {@link RepoFile#getSha1() sha1} for which to query. Must not be <code>null</code>.
	 * @param length the {@link RepoFile#getLength() length} for which to query.
	 * @return those {@link RepoFile}s matching the given criteria. Never <code>null</code>; but maybe empty.
	 */
	public Collection<RepoFile> getRepoFilesForSha1(String sha1, long length) {
		assertNotNull("sha1", sha1);
		Query query = pm().newNamedQuery(getEntityClass(), "getRepoFiles_sha1_length");
		try {
			@SuppressWarnings("unchecked")
			Collection<RepoFile> repoFiles = (Collection<RepoFile>) query.execute(sha1, length);
			return new ArrayList<RepoFile>(repoFiles);
		} finally {
			query.closeAll();
		}
	}

}