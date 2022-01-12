/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.common.model;

import com.azure.core.management.exception.ManagementException;
import com.azure.resourcemanager.resources.fluentcore.model.Refreshable;
import com.microsoft.azure.toolkit.lib.Azure;
import com.microsoft.azure.toolkit.lib.account.IAzureAccount;
import com.microsoft.azure.toolkit.lib.common.event.AzureEventBus;
import com.microsoft.azure.toolkit.lib.common.exception.AzureToolkitRuntimeException;
import com.microsoft.azure.toolkit.lib.common.task.AzureTaskManager;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.http.HttpStatus;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

@Getter
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public abstract class AbstractAzResource<T extends AbstractAzResource<T, P, R>, P extends AbstractAzResource<P, ?, ?>, R> implements AzResource<T, P, R> {
    @Nonnull
    @ToString.Include
    @EqualsAndHashCode.Include
    private final String name;
    @Nonnull
    @ToString.Include
    @EqualsAndHashCode.Include
    private final String resourceGroupName;
    @Nonnull
    @EqualsAndHashCode.Include
    private final AbstractAzResourceModule<T, P, R> module;
    @Nullable
    @Getter(AccessLevel.NONE)
    private R remote;
    @ToString.Include
    @Getter(AccessLevel.NONE)
    private long syncTime = -1;
    @ToString.Include
    @Getter(AccessLevel.NONE)
    private String status = Status.DISCONNECTED;

    protected AbstractAzResource(@Nonnull String name, @Nonnull String resourceGroupName, @Nonnull AbstractAzResourceModule<T, P, R> module) {
        this.name = name;
        this.resourceGroupName = resourceGroupName;
        this.module = module;
    }

    public final boolean exists() {
        return Objects.nonNull(this.getRemote());
    }

    @Override
    public void refresh() {
        this.syncTime = -1;
        AzureEventBus.emit("resource.status_changed.resource", this);
    }

    private synchronized void reload() {
        Azure.az(IAzureAccount.class).account();
        this.doModify(() -> {
            try {
                return this.getModule().loadResourceFromAzure(this.getName(), this.getResourceGroupName());
            } catch (ManagementException e) {
                if (HttpStatus.SC_NOT_FOUND == e.getResponse().getStatusCode()) {
                    return null;
                }
                throw e;
            }
        }, Status.LOADING);
        this.doModify(() -> this.getSubModules().parallelStream().forEach(AzResourceModule::refresh), Status.LOADING);
    }

    @Override
    public AzResource.Draft<T, R> update() {
        return this.getModule().update(this.<T>cast(this));
    }

    @Override
    public void delete() {
        assert this.exists();
        this.doModify(() -> {
            this.getModule().deleteResourceFromAzure(this.getId());
            this.setStatus(Status.DELETED);
            this.getModule().deleteResourceFromLocal(this.getName());
            return null;
        }, Status.DELETING);
    }

    synchronized void setRemote(@Nullable R remote) {
        if (this.syncTime > 0 && Objects.equals(this.remote, remote)) {
            this.setStatus(Objects.nonNull(remote) ? this.loadStatus(remote) : Status.DISCONNECTED);
        } else {
            this.syncTime = System.currentTimeMillis();
            this.remote = remote;
            if (Objects.nonNull(remote)) {
                this.doModifyAsync(() -> this.setStatus(this.loadStatus(remote)), Status.LOADING);
            } else {
                this.setStatus(Status.DISCONNECTED);
            }
        }
    }

    @Override
    @Nullable
    public final synchronized R getRemote() {
        if (this.syncTime < 0) {
            this.reload();
        }
        return this.remote;
    }

    protected synchronized void setStatus(@Nonnull String status) {
        // TODO: state engine to manage status, e.g. DRAFT -> CREATING
        final String oldStatus = this.status;
        if (!Objects.equals(oldStatus, status)) {
            this.status = status;
            AzureEventBus.emit("resource.status_changed.resource", this);
        }
    }

    @Nonnull
    public String getStatus() {
        final R remote = this.getRemote();
        if (Objects.nonNull(remote) && Objects.isNull(this.status)) {
            this.setStatus(this.loadStatus(remote));
        }
        return this.status;
    }

    @Override
    public String getFormalStatus() {
        return this.formalizeStatus(this.getStatus());
    }

    protected void doModify(Runnable body, String status) {
        // TODO: lock so that can not modify if modifying.
        this.setStatus(Optional.ofNullable(status).orElse(Status.PENDING));
        try {
            body.run();
            this.refreshRemote();
            this.setRemote(this.remote);
        } catch (Throwable t) {
            this.setStatus(Status.ERROR);
            throw t;
        }
    }

    protected void refreshRemote() {
        if (this.remote instanceof Refreshable) {
            ((Refreshable<?>) this.remote).refresh();
        }
    }

    protected void doModifyAsync(Runnable body, String status) {
        this.setStatus(Optional.ofNullable(status).orElse(Status.PENDING));
        AzureTaskManager.getInstance().runOnPooledThread(() -> this.doModify(body, status));
    }

    protected R doModify(Callable<R> body, String status) {
        // TODO: lock so that can not modify if modifying.
        this.setStatus(Optional.ofNullable(status).orElse(Status.PENDING));
        try {
            final R remote = body.call();
            this.setRemote(remote);
            return remote;
        } catch (Throwable t) {
            this.setStatus(Status.ERROR);
            throw new AzureToolkitRuntimeException(t);
        }
    }

    protected void doModifyAsync(Callable<R> body, String status) {
        this.setStatus(Optional.ofNullable(status).orElse(Status.PENDING));
        AzureTaskManager.getInstance().runOnPooledThread(() -> this.doModify(body, status));
    }

    @Nonnull
    public String getId() {
        return this.getModule().toResourceId(this.getName(), this.getResourceGroupName());
    }

    public abstract List<AzResourceModule<?, T, ?>> getSubModules();

    public String formalizeStatus(String status) {
        return status;
    }

    @Nonnull
    public abstract String loadStatus(@Nonnull R remote);

    protected Optional<R> remoteOptional() {
        return Optional.ofNullable(this.getRemote());
    }

    private <D> D cast(@Nonnull Object origin) {
        //noinspection unchecked
        return (D) origin;
    }

    @Nonnull
    public AzResourceModule<?, T, ?> getSubModule(String moduleName) {
        return this.getSubModules().stream().filter(m -> m.getName().equals(moduleName)).findAny()
            .orElseThrow(() -> new AzureToolkitRuntimeException(String.format("invalid module\"%s\"", moduleName)));
    }
}
