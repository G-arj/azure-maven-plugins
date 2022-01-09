/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.sqlserver;

import com.azure.resourcemanager.resources.fluentcore.arm.ResourceId;
import com.azure.resourcemanager.sql.models.SqlFirewallRule;
import com.azure.resourcemanager.sql.models.SqlFirewallRuleOperations;
import com.azure.resourcemanager.sql.models.SqlServer;
import com.google.common.base.Preconditions;
import com.microsoft.azure.toolkit.lib.common.model.AbstractAzResourceModule;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
import java.util.stream.Stream;

public class MicrosoftSqlFirewallRuleModule extends AbstractAzResourceModule<MicrosoftSqlFirewallRule, MicrosoftSqlServer, SqlFirewallRule> {
    public static final String NAME = "firewallRules";

    public MicrosoftSqlFirewallRuleModule(@Nonnull MicrosoftSqlServer parent) {
        super(NAME, parent);
    }

    @Override
    protected MicrosoftSqlFirewallRule newResource(@Nonnull SqlFirewallRule rule) {
        return new MicrosoftSqlFirewallRule(rule, this);
    }

    @Nonnull
    @Override
    protected Stream<SqlFirewallRule> loadResourcesFromAzure() {
        return this.getClient().list().stream();
    }

    @Nullable
    @Override
    protected SqlFirewallRule loadResourceFromAzure(@Nonnull String name, String resourceGroup) {
        return this.getClient().get(name);
    }

    @Override
    protected void deleteResourceFromAzure(@Nonnull String id) {
        final ResourceId resourceId = ResourceId.fromString(id);
        final String name = resourceId.name();
        this.getClient().delete(name);
    }

    @Override
    protected SqlFirewallRuleOperations.SqlFirewallRuleActionsDefinition getClient() {
        return Optional.ofNullable(this.getParent().getRemote()).map(SqlServer::firewallRules).orElse(null);
    }

    public void toggleAzureServiceAccess(boolean allowed) {
        final String ruleName = MicrosoftSqlFirewallRule.AZURE_SERVICES_ACCESS_FIREWALL_RULE_NAME;
        final String rgName = this.getParent().getResourceGroupName();
        final boolean exists = this.exists(rgName, rgName);
        if (!allowed && exists) {
            this.delete(ruleName, rgName);
        }
        if (allowed && !exists) {
            final MicrosoftSqlFirewallRuleDraft draft = this.create(ruleName, rgName);
            draft.setStartIpAddress(MicrosoftSqlFirewallRule.IP_ALLOW_ACCESS_TO_AZURE_SERVICES);
            draft.setEndIpAddress(MicrosoftSqlFirewallRule.IP_ALLOW_ACCESS_TO_AZURE_SERVICES);
            draft.commit();
        }
    }

    public void toggleLocalMachineAccess(boolean allowed) {
        final String ruleName = MicrosoftSqlFirewallRule.getLocalMachineAccessRuleName();
        final String rgName = this.getParent().getResourceGroupName();
        final boolean exists = this.exists(rgName, rgName);
        if (!allowed && exists) {
            this.delete(ruleName, rgName);
        }
        if (allowed && !exists) {
            final String publicIp = this.getParent().getLocalMachinePublicIp();
            Preconditions.checkArgument(StringUtils.isNotBlank(publicIp),
                "Cannot enable local machine access to postgre sql server due to error: cannot get public ip.");
            final MicrosoftSqlFirewallRuleDraft draft = this.updateOrCreate(ruleName, rgName);
            draft.setStartIpAddress(publicIp);
            draft.setEndIpAddress(publicIp);
            draft.commit();
        }
    }
}
