package co.codewizards.cloudstore.ls.rest.server;

import static co.codewizards.cloudstore.core.util.AssertUtil.*;
import static co.codewizards.cloudstore.core.util.Util.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import co.codewizards.cloudstore.core.dto.Error;
import co.codewizards.cloudstore.core.dto.RemoteException;
import co.codewizards.cloudstore.core.dto.RemoteExceptionUtil;
import co.codewizards.cloudstore.core.dto.Uid;
import co.codewizards.cloudstore.core.io.TimeoutException;
import co.codewizards.cloudstore.core.util.ExceptionUtil;
import co.codewizards.cloudstore.ls.core.dto.ErrorResponse;
import co.codewizards.cloudstore.ls.core.dto.InverseServiceRequest;
import co.codewizards.cloudstore.ls.core.dto.InverseServiceResponse;
import co.codewizards.cloudstore.ls.core.dto.NullResponse;
import co.codewizards.cloudstore.ls.core.invoke.ClassInfo;
import co.codewizards.cloudstore.ls.core.invoke.ClassInfoCache;
import co.codewizards.cloudstore.ls.core.invoke.ClassManager;
import co.codewizards.cloudstore.ls.core.invoke.GetClassInfoRequest;
import co.codewizards.cloudstore.ls.core.invoke.GetClassInfoResponse;
import co.codewizards.cloudstore.ls.core.invoke.InverseMethodInvocationRequest;
import co.codewizards.cloudstore.ls.core.invoke.InverseMethodInvocationResponse;
import co.codewizards.cloudstore.ls.core.invoke.MethodInvocationRequest;
import co.codewizards.cloudstore.ls.core.invoke.MethodInvocationResponse;
import co.codewizards.cloudstore.ls.core.invoke.ObjectManager;
import co.codewizards.cloudstore.ls.core.invoke.ObjectRef;
import co.codewizards.cloudstore.ls.core.invoke.RemoteObjectProxy;
import co.codewizards.cloudstore.ls.core.invoke.RemoteObjectProxyFactory;

public class InverseInvoker {
	/**
	 * Timeout (in milliseconds) before sending an empty HTTP response to the polling client. The client does
	 * <i>long polling</i> in order to allow for
	 * {@linkplain #performInverseServiceRequest(InverseServiceRequest) inverse service invocations}.
	 * <p>
	 * This timeout must be (significantly) shorter than {@link ObjectManager#TIMEOUT_EVICT_UNUSED_OBJECT_MANAGER_MS} to make sure, the
	 * {@linkplain #pollInverseServiceRequest() polling} serves additionally as a keep-alive for
	 * the server-side {@code ObjectManager}.
	 */
	private static final long POLL_INVERSE_SERVICE_REQUEST_TIMEOUT_MS = 30L * 1000L; // 30 seconds

	/**
	 * Timeout for {@link #performInverseServiceRequest(InverseServiceRequest)}.
	 * <p>
	 * If an inverse service-request does not receive a response within this timeout, a {@link TimeoutException} is thrown.
	 */
	private static final long PERFORM_INVERSE_SERVICE_REQUEST_TIMEOUT_MS = 15L * 60L * 1000L; // 15 minutes

	private final ObjectManager objectManager;
	private final LinkedList<InverseServiceRequest> inverseServiceRequests = new LinkedList<>();
	private final Set<Uid> requestIdsWaitingForResponse = new HashSet<Uid>(); // synchronized by: requestId2InverseServiceResponse
	private final Map<Uid, InverseServiceResponse> requestId2InverseServiceResponse = new HashMap<Uid, InverseServiceResponse>();
	private final ClassInfoCache classInfoCache = new ClassInfoCache();

	public static InverseInvoker getInverseInvoker(final ObjectManager objectManager) {
		assertNotNull("objectManager", objectManager);

		synchronized (objectManager) {
			InverseInvoker inverseInvoker = (InverseInvoker) objectManager.getContextObject(InverseInvoker.class.getName());
			if (inverseInvoker == null) {
				inverseInvoker = new InverseInvoker(objectManager);
				objectManager.putContextObject(InverseInvoker.class.getName(), inverseInvoker);
			}
			return inverseInvoker;
		}
	}

	private InverseInvoker(final ObjectManager objectManager) {
		this.objectManager = assertNotNull("objectManager", objectManager);
	}

	public ObjectManager getObjectManager() {
		return objectManager;
	}

	public <T> T invokeStatic(final Class<?> clazz, final String methodName, final Object ... arguments) {
		assertNotNull("clazz", clazz);
		assertNotNull("methodName", methodName);
		return invokeStatic(clazz.getName(), methodName, arguments);
	}

	public <T> T invokeStatic(final String className, final String methodName, final Object ... arguments) {
		assertNotNull("className", className);
		assertNotNull("methodName", methodName);
		final MethodInvocationRequest methodInvocationRequest = MethodInvocationRequest.forStaticInvocation(
				className, methodName, fromObjectsToObjectRefs(arguments));

		return invoke(methodInvocationRequest);
	}

	public <T> T invokeConstructor(final Class<?> clazz, final Object ... arguments) {
		assertNotNull("clazz", clazz);
		return invokeConstructor(clazz.getName(), arguments);
	}

	public <T> T invokeConstructor(final String className, final Object ... arguments) {
		assertNotNull("className", className);
		final MethodInvocationRequest methodInvocationRequest = MethodInvocationRequest.forConstructorInvocation(
				className, fromObjectsToObjectRefs(arguments));

		return invoke(methodInvocationRequest);
	}

	public <T> T invoke(final Object object, final String methodName, final Object ... arguments) {
		assertNotNull("object", object);
		assertNotNull("methodName", methodName);

		if (!(object instanceof RemoteObjectProxy))
			throw new IllegalArgumentException("object is not an instance of RemoteObjectProxy!");

		final ObjectRef objectRef = assertNotNull("object.getObjectRef()", ((RemoteObjectProxy)object).getObjectRef());
		return invoke(objectRef, methodName, (Class<?>[]) null, arguments);
	}

	private <T> T invoke(final ObjectRef objectRef, final String methodName, final Class<?>[] argumentTypes, final Object[] arguments) {
		assertNotNull("objectRef", objectRef);
		assertNotNull("methodName", methodName);

		final String[] argumentTypeNames;
		if (argumentTypes == null)
			argumentTypeNames = null;
		else {
			argumentTypeNames = new String[argumentTypes.length];
			for (int i = 0; i < argumentTypes.length; i++)
				argumentTypeNames[i] = argumentTypes[i].getName();
		}

		final MethodInvocationRequest methodInvocationRequest = MethodInvocationRequest.forObjectInvocation(
				objectRef, methodName, argumentTypeNames, fromObjectsToObjectRefs(arguments));

		return invoke(methodInvocationRequest);
	}

	private <T> T invoke(final MethodInvocationRequest methodInvocationRequest) {
		assertNotNull("methodInvocationRequest", methodInvocationRequest);

		final InverseMethodInvocationResponse inverseMethodInvocationResponse = performInverseServiceRequest(
				new InverseMethodInvocationRequest(methodInvocationRequest));

		assertNotNull("inverseMethodInvocationResponse", inverseMethodInvocationResponse);

		final MethodInvocationResponse methodInvocationResponse = inverseMethodInvocationResponse.getMethodInvocationResponse();

		final Object result = methodInvocationResponse.getResult();
		if (result == null)
			return null;

		if (result instanceof ObjectRef) {
			final ObjectRef resultObjectRef = (ObjectRef) result;
			return cast(getRemoteObjectProxyOrCreate(resultObjectRef));
		}

		return cast(result);
	}

	public Object getRemoteObjectProxyOrCreate(ObjectRef objectRef) {
		return objectManager.getRemoteObjectProxyManager().getRemoteObjectProxy(objectRef, new RemoteObjectProxyFactory() {
			@Override
			public RemoteObjectProxy createRemoteObject(ObjectRef objectRef) {
				return _createRemoteObjectProxy(objectRef);
			}
		});
	}

	private RemoteObjectProxy _createRemoteObjectProxy(final ObjectRef objectRef) {
		final Class<?>[] interfaces = getInterfaces(objectRef.getClassId());

		final ClassLoader classLoader = this.getClass().getClassLoader();
		return (RemoteObjectProxy) Proxy.newProxyInstance(classLoader, interfaces, new InvocationHandler() {
			@Override
			public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
				// BEGIN implement RemoteObjectProxy
				if ("getObjectRef".equals(method.getName()) && method.getParameterTypes().length == 0)
					return objectRef;
				// END implement RemoteObjectProxy

				return InverseInvoker.this.invoke(objectRef, method.getName(), method.getParameterTypes(), args);
			}

			@Override
			protected void finalize() throws Throwable {
				InverseInvoker.this.invoke(objectRef, ObjectRef.VIRTUAL_METHOD_NAME_REMOVE_OBJECT_REF, (Class<?>[])null, (Object[])null);
				super.finalize();
			}
		});
	}

	private Class<?>[] getInterfaces(int classId) {
		final ClassInfo classInfo = getClassInfoOrFail(classId);
		final ClassManager classManager = objectManager.getClassManager();
		final Set<String> interfaceNames = classInfo.getInterfaceNames();
		final List<Class<?>> interfaces = new ArrayList<>(interfaceNames.size() + 1);
		for (final String interfaceName : interfaceNames) {
			Class<?> iface = null;
			try {
				iface = classManager.getClassOrFail(interfaceName);
			} catch (RuntimeException x) {
				if (ExceptionUtil.getCause(x, ClassNotFoundException.class) == null)
					throw x;
			}
			if (iface != null)
				interfaces.add(iface);
		}
		interfaces.add(RemoteObjectProxy.class);
		return interfaces.toArray(new Class<?>[interfaces.size()]);
	}

	private ClassInfo getClassInfoOrFail(final int classId) {
		final ClassInfo classInfo = getClassInfo(classId);
		if (classInfo == null)
			throw new IllegalArgumentException("No ClassInfo found for classId=" + classId);

		return classInfo;
	}

	private ClassInfo getClassInfo(final int classId) {
		ClassInfo classInfo = classInfoCache.getClassInfo(classId);
		if (classInfo == null) {
			final GetClassInfoResponse getClassInfoResponse = performInverseServiceRequest(new GetClassInfoRequest(classId));
			if (getClassInfoResponse != null) {
				classInfo = getClassInfoResponse.getClassInfo();
				classInfoCache.putClassInfo(classInfo);
			}
		}
		return classInfo;
	}

	private Object[] fromObjectsToObjectRefs(final Object[] objects) {
		if (objects == null)
			return objects;

		final Object[] result = new Object[objects.length];
		for (int i = 0; i < objects.length; i++) {
			final Object object = objects[i];
			if (object instanceof RemoteObjectProxy) {
				result[i] = assertNotNull("object.getObjectRef()", ((RemoteObjectProxy)object).getObjectRef());
			} else
				result[i] = objectManager.getObjectRefOrObject(object);
		}
		return result;
	}

	/**
	 * Invokes a service on the client-side.
	 * <p>
	 * Normally, a client initiates a request-response-cycle by sending a request to a server-side service and waiting
	 * for the response. In order to notify client-side listeners, we need the inverse, though: the server must invoke
	 * a service on the client-side. This can be easily done by sending an implementation of {@code InverseServiceRequest}
	 * to a {@code InverseServiceRequestHandler} implementation on the client-side using this method.
	 *
	 * @param request the request to be processed on the client-side. Must not be <code>null</code>.
	 * @return the response created and sent back by the client-side {@code InverseServiceRequestHandler}.
	 * @throws TimeoutException if this method did not receive a response within the timeout
	 * {@link #PERFORM_INVERSE_SERVICE_REQUEST_TIMEOUT_MS}.
	 */
	public <T extends InverseServiceResponse> T performInverseServiceRequest(final InverseServiceRequest request) throws TimeoutException {
		assertNotNull("request", request);

		final Uid requestId = request.getRequestId();
		assertNotNull("request.requestId", requestId);

		synchronized (requestId2InverseServiceResponse) {
			if (!requestIdsWaitingForResponse.add(requestId))
				throw new IllegalStateException("requestId already queued: " + requestId);
		}
		try {
			synchronized (inverseServiceRequests) {
				inverseServiceRequests.add(request);
				inverseServiceRequests.notify();
			}

			// The request is pushed, hence from now on, we wait for the response until the timeout in PERFORM_INVERSE_SERVICE_REQUEST_TIMEOUT_MS.
			final long startTimestamp = System.currentTimeMillis();

			synchronized (requestId2InverseServiceResponse) {
				boolean first = true;
				while (first || System.currentTimeMillis() - startTimestamp < PERFORM_INVERSE_SERVICE_REQUEST_TIMEOUT_MS) {
					if (first)
						first = false;
					else {
						final long timeSpentTillNowMillis = System.currentTimeMillis() - startTimestamp;
						final long waitTimeout = PERFORM_INVERSE_SERVICE_REQUEST_TIMEOUT_MS - timeSpentTillNowMillis;
						if (waitTimeout > 0) {
							try {
								requestId2InverseServiceResponse.wait(waitTimeout);
							} catch (InterruptedException e) {
								doNothing();
							}
						}
					}

					final InverseServiceResponse response = requestId2InverseServiceResponse.remove(requestId);
					if (response != null) {
						if (response instanceof NullResponse)
							return null;
						else if (response instanceof ErrorResponse) {
							final Error error = ((ErrorResponse) response).getError();
							RemoteExceptionUtil.throwOriginalExceptionIfPossible(error);
							throw new RemoteException(error);
						}
						else {
							@SuppressWarnings("unchecked")
							final T t = (T) response;
							return t;
						}
					}
				}
			}
		} finally {
			boolean requestWasStillWaiting;
			// in case, it was not yet polled, we make sure garbage does not pile up.
			synchronized (requestId2InverseServiceResponse) {
				requestWasStillWaiting = requestIdsWaitingForResponse.remove(requestId);

				// Make sure, no garbage is left over by removing this together with the requestId from requestIdsWaitingForResponse.
				requestId2InverseServiceResponse.remove(requestId);
			}

			if (requestWasStillWaiting) {
				synchronized (inverseServiceRequests) {
					inverseServiceRequests.remove(request);
				}
			}
		}

		throw new TimeoutException(String.format("Timeout waiting for response matching requestId=%s!", requestId));
	}

	public InverseServiceRequest pollInverseServiceRequest() {
		final long startTimestamp = System.currentTimeMillis();

		synchronized (inverseServiceRequests) {
			boolean first = true;
			while (first || System.currentTimeMillis() - startTimestamp < POLL_INVERSE_SERVICE_REQUEST_TIMEOUT_MS) {
				if (first)
					first = false;
				else {
					final long timeSpentTillNowMillis = System.currentTimeMillis() - startTimestamp;
					final long waitTimeout = POLL_INVERSE_SERVICE_REQUEST_TIMEOUT_MS - timeSpentTillNowMillis;
					if (waitTimeout > 0) {
						try {
							inverseServiceRequests.wait(waitTimeout);
						} catch (InterruptedException e) {
							doNothing();
						}
					}
				}

				final InverseServiceRequest request = inverseServiceRequests.poll();
				if (request != null)
					return request;
			};
		}
		return null;
	}

	public void pushInverseServiceResponse(final InverseServiceResponse response) {
		assertNotNull("response", response);

		final Uid requestId = response.getRequestId();
		assertNotNull("response.requestId", requestId);

		synchronized (requestId2InverseServiceResponse) {
			if (!requestIdsWaitingForResponse.contains(requestId))
				throw new IllegalArgumentException(String.format("response.requestId=%s does not match any waiting request!", requestId));

			requestId2InverseServiceResponse.put(requestId, response);
			requestId2InverseServiceResponse.notifyAll();
		}
	}
}
