/*
 * Copyright (c) 2019 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at:
 *
 *     https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.jkube.kit.build.service.docker.auth.ecr;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.eclipse.jkube.kit.build.api.auth.AuthConfig;
import org.eclipse.jkube.kit.common.KitLogger;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

/**
 * Exchange local stored credentials for temporary ecr credentials
 *
 * @author chas
 * @since 2016-12-9
 */
public class EcrExtendedAuth {

    private static final Pattern AWS_REGISTRY =
            Pattern.compile("^(\\d{12})\\.dkr\\.ecr\\.([a-z\\-0-9]+)\\.amazonaws\\.com$");

    private final KitLogger logger;
    private final boolean isAwsRegistry;
    private final String accountId;
    private final String region;

    /**
     * Is given the registry an ecr registry?
     *
     * @param registry the registry name
     * @return true, if the registry matches the ecr pattern
     */
    public static boolean isAwsRegistry(String registry) {
        return (registry != null) && AWS_REGISTRY.matcher(registry).matches();
    }

    /**
     * Initialize an extended authentication for ecr registry.
     *
     * @param logger Kit Logger
     * @param registry The registry, we may or may not be an ecr registry.
     */
    public EcrExtendedAuth(KitLogger logger, String registry) {
        this.logger = logger;
        Matcher matcher = AWS_REGISTRY.matcher(registry);
        isAwsRegistry = matcher.matches();
        if (isAwsRegistry) {
            accountId = matcher.group(1);
            region = matcher.group(2);
        } else {
            accountId = null;
            region = null;
        }
        logger.debug("registry = %s, isValid= %b", registry, isAwsRegistry);
    }

    /**
     * Is the registry an ecr registry?
     * @return true, if the registry matches the ecr pattern
     */
    public boolean isAwsRegistry() {
        return isAwsRegistry;
    }

    /**
     * Perform extended authentication.  Use the provided credentials as IAM credentials and
     * get a temporary ECR token.
     *
     * @param localCredentials IAM id/secret
     * @return ECR base64 encoded username:password
     * @throws IOException IO Exception
     */
    public AuthConfig extendedAuth(AuthConfig localCredentials) throws IOException {
        JsonObject jo = getAuthorizationToken(localCredentials);

        JsonArray authorizationDatas = jo.getAsJsonArray("authorizationData");
        JsonObject authorizationData = authorizationDatas.get(0).getAsJsonObject();
        String authorizationToken = authorizationData.get("authorizationToken").getAsString();

        return AuthConfig.fromCredentialsEncoded(authorizationToken, "none");
    }

    private JsonObject getAuthorizationToken(AuthConfig localCredentials) throws IOException {
        HttpPost request = createSignedRequest(localCredentials, new Date());
        return executeRequest(createClient(), request);
    }

    CloseableHttpClient createClient() {
        return HttpClients.custom().useSystemProperties().build();
    }

    private JsonObject executeRequest(CloseableHttpClient client, HttpPost request) throws IOException {
        try {
            CloseableHttpResponse response = client.execute(request);
            int statusCode = response.getStatusLine().getStatusCode();
            logger.debug("Response status %d", statusCode);
            if (statusCode != HttpStatus.SC_OK) {
                throw new IOException("AWS authentication failure");
            }

            HttpEntity entity = response.getEntity();
            Reader jr = new InputStreamReader(entity.getContent(), StandardCharsets.UTF_8);
            return new Gson().fromJson(jr, JsonObject.class);
        }
        finally {
            client.close();
        }
    }

    HttpPost createSignedRequest(AuthConfig localCredentials, Date time) {
        String host = "api.ecr." + region + ".amazonaws.com";

        logger.debug("Get ECR AuthorizationToken from %s", host);

        HttpPost request = new HttpPost("https://" + host + '/');
        request.setHeader("host", host);
        request.setHeader("Content-Type", "application/x-amz-json-1.1");
        request.setHeader("X-Amz-Target", "AmazonEC2ContainerRegistry_V20150921.GetAuthorizationToken");
        request.setEntity(new StringEntity("{\"registryIds\":[\""+ accountId + "\"]}", StandardCharsets.UTF_8));

        AwsSigner4 signer = new AwsSigner4(region, "ecr");
        signer.sign(request, localCredentials, time);
        return request;
    }
}
