package co.codewizards.cloudstore.rest.client.request;

import static co.codewizards.cloudstore.core.util.Util.*;

import javax.ws.rs.core.Response;

public class Delete extends VoidRequest {

	private final String repositoryName;
	private final String path;

	public Delete(final String repositoryName, final String path) {
		this.repositoryName = assertNotNull("repositoryName", repositoryName);
		this.path = path;
	}

	@Override
	protected Response _execute() {
		return assignCredentials(
				createWebTarget(urlEncode(repositoryName), encodePath(path)).request()).delete();
	}

}