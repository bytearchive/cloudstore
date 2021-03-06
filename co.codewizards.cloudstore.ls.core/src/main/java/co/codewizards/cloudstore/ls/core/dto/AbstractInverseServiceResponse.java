package co.codewizards.cloudstore.ls.core.dto;

import static co.codewizards.cloudstore.core.util.AssertUtil.*;
import co.codewizards.cloudstore.core.dto.Uid;

public abstract class AbstractInverseServiceResponse implements InverseServiceResponse {
	private static final long serialVersionUID = 1L;

	private final Uid requestId;

	public AbstractInverseServiceResponse(InverseServiceRequest request) {
		this(assertNotNull("request.requestId", assertNotNull("request", request).getRequestId()));
	}

	public AbstractInverseServiceResponse(Uid requestId) {
		this.requestId = assertNotNull("requestId", requestId);
	}

	@Override
	public Uid getRequestId() {
		return requestId;
	}
}
