/**
 * Copyright (C) 2023 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.intersmash.provision.operator.hyperfoil.client.v05.invoker;

import java.util.List;
import java.util.Map;

/** API response returned by API call. */
public class ApiResponse<T> {
	private final int statusCode;
	private final Map<String, List<String>> headers;
	private final T data;

	/**
	 * Constructor for ApiResponse.
	 *
	 * @param statusCode The status code of HTTP response
	 * @param headers The headers of HTTP response
	 */
	public ApiResponse(int statusCode, Map<String, List<String>> headers) {
		this(statusCode, headers, null);
	}

	/**
	 * Constructor for ApiResponse.
	 *
	 * @param statusCode The status code of HTTP response
	 * @param headers The headers of HTTP response
	 * @param data The object deserialized from response bod
	 */
	public ApiResponse(int statusCode, Map<String, List<String>> headers, T data) {
		this.statusCode = statusCode;
		this.headers = headers;
		this.data = data;
	}

	/**
	 * Get the <code>status code</code>.
	 *
	 * @return the status code
	 */
	public int getStatusCode() {
		return statusCode;
	}

	/**
	 * Get the <code>headers</code>.
	 *
	 * @return a {@link java.util.Map} of headers
	 */
	public Map<String, List<String>> getHeaders() {
		return headers;
	}

	/**
	 * Get the <code>data</code>.
	 *
	 * @return the data
	 */
	public T getData() {
		return data;
	}
}
