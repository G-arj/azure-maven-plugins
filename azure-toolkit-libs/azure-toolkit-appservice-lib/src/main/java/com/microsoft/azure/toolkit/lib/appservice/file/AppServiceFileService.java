/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.appservice.file;

import com.google.common.base.Joiner;
import com.microsoft.azure.management.appservice.WebAppBase;
import com.microsoft.azure.management.appservice.implementation.AppServiceManager;
import com.microsoft.azure.toolkit.lib.appservice.model.AppServiceFileLegacy;
import com.microsoft.azure.toolkit.lib.common.operation.AzureOperation;
import com.microsoft.rest.RestClient;
import lombok.SneakyThrows;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.apache.commons.codec.binary.StringUtils;
import rx.Emitter;
import rx.Observable;
import rx.functions.Action1;
import rx.functions.Func1;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Deprecated
public class AppServiceFileService {

    private final AppServiceFileClient client;
    private final WebAppBase app;

    private AppServiceFileService(final WebAppBase app, AppServiceFileClient client) {
        this.app = app;
        this.client = client;
    }

    @Nullable
    @AzureOperation(
        name = "appservice|file.get.path",
        params = {"path", "this.app.name()"},
        type = AzureOperation.Type.SERVICE
    )
    public AppServiceFileLegacy getFileByPath(String path) {
        final File file = new File(path);
        final List<? extends AppServiceFileLegacy> result = getFilesInDirectory(file.getParent());
        return result.stream()
            .filter(appServiceFile -> StringUtils.equals(file.getName(), appServiceFile.getName()))
            .findFirst()
            .orElse(null);
    }

    @AzureOperation(
        name = "appservice|file.list.dir",
        params = {"dir", "this.app.name()"},
        type = AzureOperation.Type.SERVICE
    )
    public List<? extends AppServiceFileLegacy> getFilesInDirectory(String dir) {
        // this file is generated by kudu itself, should not be visible to user.
        final Predicate<AppServiceFileLegacy> filter = file -> !"text/xml".equals(file.getMime()) || !file.getName().contains("LogFiles-kudu-trace_pending.xml");
        final List<AppServiceFileLegacy> files = this.client.getFilesInDirectory(dir).toBlocking().first().stream().filter(filter).collect(Collectors.toList());
        files.forEach(file -> {
            file.setApp(this.app);
            file.setPath(dir + "/" + file.getName());
        });
        return files;
    }

    @AzureOperation(
        name = "appservice|file.get_content",
        params = {"path", "this.app.name()"},
        type = AzureOperation.Type.SERVICE
    )
    public Observable<byte[]> getFileContent(final String path) {
        return this.client.getFileContent(path).flatMap((Func1<ResponseBody, Observable<byte[]>>) responseBody -> {
            final BufferedSource source = responseBody.source();
            return Observable.create((Action1<Emitter<byte[]>>) emitter -> {
                try {
                    while (!source.exhausted()) {
                        emitter.onNext(source.readByteArray());
                    }
                    emitter.onCompleted();
                } catch (final IOException e) {
                    emitter.onError(e);
                }
            }, Emitter.BackpressureMode.BUFFER);
        });
    }

    @AzureOperation(
        name = "appservice|file.upload",
        params = {"path", "this.app.name()"},
        type = AzureOperation.Type.SERVICE
    )
    public void uploadFileToPath(String content, String path) {
        // this file is generated by kudu itself, should not be visible to user.
        RequestBody body = RequestBody.create(MediaType.parse("application/octet-stream"), content);
        this.client.saveFile(path, body).toBlocking().single();
    }

    public static AppServiceFileService forApp(final WebAppBase app) {
        final AppServiceFileClient client = getClient(app);
        return new AppServiceFileService(app, client);
    }

    private static AppServiceFileClient getClient(WebAppBase app) {
        if (app.defaultHostName() == null) {
            throw new UnsupportedOperationException("Cannot initialize kudu vfs client before web app is created");
        } else {
            String host = app.defaultHostName().toLowerCase().replace("http://", "").replace("https://", "");
            final String[] parts = host.split("\\.", 2);
            host = Joiner.on('.').join(parts[0], "scm", parts[1]);
            final AppServiceManager manager = app.manager();
            final RestClient restClient = getRestClient(manager);
            return restClient.newBuilder()
                .withBaseUrl("https://" + host)
                .withConnectionTimeout(3L, TimeUnit.MINUTES)
                .withReadTimeout(3L, TimeUnit.MINUTES)
                .build()
                .retrofit()
                .create(KuduFileClient.class);
        }
    }

    @SneakyThrows
    private static RestClient getRestClient(final AppServiceManager manager) {
        //TODO: @wangmi find a proper way to get rest client.
        final Method method = manager.getClass().getDeclaredMethod("restClient");
        method.setAccessible(true);
        return (RestClient) method.invoke(manager);
    }
}
