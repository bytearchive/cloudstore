package co.codewizards.cloudstore.rest.client.request;

import static co.codewizards.cloudstore.core.util.AssertUtil.*;

import javax.ws.rs.core.Response;

import co.codewizards.cloudstore.core.dto.DateTime;

public class EndPutFile extends VoidRequest {

	private final String repositoryName;
	private final String path;
	private final DateTime lastModified;
	private final long length;
	private final String sha1;

	public EndPutFile(final String repositoryName, final String path, final DateTime lastModified, final long length, final String sha1) {
		this.repositoryName = assertNotNull("repositoryName", repositoryName);
		this.path = assertNotNull("path", path);
		this.lastModified = assertNotNull("lastModified", lastModified);
		this.length = length;
		this.sha1 = sha1;
	}

	@Override
	public Response _execute() {
		return assignCredentials(
				createWebTarget("_endPutFile", urlEncode(repositoryName), encodePath(path))
				.queryParam("lastModified", lastModified.toString())
				.queryParam("length", length)
				.queryParam("sha1", sha1)
				.request()).post(null);
	}

}
