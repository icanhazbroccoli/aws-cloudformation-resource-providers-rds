package software.amazon.rds.dbinstance;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;

import com.amazonaws.util.CollectionUtils;
import com.amazonaws.util.StringUtils;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.SecurityGroup;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DBParameterGroup;
import software.amazon.awssdk.services.rds.model.DBParameterGroupStatus;
import software.amazon.awssdk.services.rds.model.DbInstanceNotFoundException;
import software.amazon.awssdk.services.rds.model.DescribeDbEngineVersionsResponse;
import software.amazon.awssdk.services.rds.model.DescribeDbParameterGroupsResponse;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.HandlerErrorCode;
import software.amazon.cloudformation.proxy.Logger;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.ProxyClient;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;

public class UpdateHandler extends BaseHandlerStd {

    public static final String PENDING_REBOOT_STATUS = "pending-reboot";

    protected ProgressEvent<ResourceModel, CallbackContext> handleRequest(
            final AmazonWebServicesClientProxy proxy,
            final ResourceHandlerRequest<ResourceModel> request,
            final CallbackContext callbackContext,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProxyClient<Ec2Client> ec2ProxyClient,
            final Logger logger
    ) {

        final ResourceModel previousModel = request.getPreviousResourceState();
        final ResourceModel desiredModel = request.getDesiredResourceState();

        final Collection<Tag> previousTags = Translator.translateTagsFromRequest(
                mergeMaps(Arrays.asList(request.getPreviousSystemTags(), request.getPreviousResourceTags()))
        );
        final Collection<Tag> desiredTags = Translator.translateTagsFromRequest(
                mergeMaps(Arrays.asList(request.getSystemTags(), request.getDesiredResourceTags()))
        );

        final Collection<DBInstanceRole> previousRoles = previousModel.getAssociatedRoles();
        final Collection<DBInstanceRole> desiredRoles = desiredModel.getAssociatedRoles();

        return ProgressEvent.progress(request.getDesiredResourceState(), callbackContext)
                .then(progress -> {
                    if (shouldSetParameterGroupName(request)) {
                        return setParameterGroupName(proxy, rdsProxyClient, progress);
                    }
                    return progress;
                })
                .then(progress -> {
                    if (shouldSetDefaultVpcId(request)) {
                        return setDefaultVpcId(proxy, rdsProxyClient, ec2ProxyClient, progress);
                    }
                    return progress;
                })
                .then(progress -> ensureEngineSet(proxy, rdsProxyClient, progress))
                .then(progress -> execOnce(progress, () -> {
                    return proxy.initiate("rds::modify-db-instance", rdsProxyClient, progress.getResourceModel(), progress.getCallbackContext())
                            .translateToServiceRequest(resourceModel -> Translator.modifyDbInstanceRequest(previousModel, desiredModel, BooleanUtils.isTrue(request.getRollback())))
                            .backoffDelay(BACK_OFF)
                            .makeServiceCall((modifyRequest, proxyInvocation) -> proxyInvocation.injectCredentialsAndInvokeV2(
                                    modifyRequest,
                                    proxyInvocation.client()::modifyDBInstance
                            ))
                            .stabilize((modifyRequest, response, proxyInvocation, model, context) -> isDbInstanceStabilized(
                                    proxyInvocation, model
                            ))
                            .progress();
                }, CallbackContext::isUpdated, CallbackContext::setUpdated))
                .then(progress -> execOnce(progress, () -> {
                        if (shouldReboot(proxy, rdsProxyClient, progress)) {
                            return rebootAwait(proxy, rdsProxyClient, progress);
                        }
                        return progress;
                    }, CallbackContext::isRebooted, CallbackContext::setRebooted)
                )
                .then(progress -> execOnce(progress, () -> {
                            return updateAssociatedRoles(proxy, rdsProxyClient, progress, previousRoles, desiredRoles);
                        }, CallbackContext::isUpdatedRoles, CallbackContext::setUpdatedRoles)
                )
                .then(progress -> updateTags(proxy, rdsProxyClient, progress, previousTags, desiredTags))
                .then(progress -> new ReadHandler().handleRequest(proxy, request, callbackContext, rdsProxyClient, ec2ProxyClient, logger));
    }

    private boolean shouldReboot(
            final AmazonWebServicesClientProxy proxy,
            final ProxyClient<RdsClient> proxyClient,
            ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        Optional<DBInstance> maybeDbInstance = fetchDBInstance(proxyClient, progress.getResourceModel());
        if (maybeDbInstance.isPresent()) {
            Optional<DBParameterGroupStatus> dbParameterGroupStatus = maybeDbInstance.get().dbParameterGroups().stream().findFirst();
            if (dbParameterGroupStatus.isPresent()) {
                return PENDING_REBOOT_STATUS.equals(dbParameterGroupStatus.get().parameterApplyStatus());
            }
        }
        return false;
    }

    private boolean shouldSetParameterGroupName(final ResourceHandlerRequest<ResourceModel> request) {
        final ResourceModel desiredModel = request.getDesiredResourceState();
        final ResourceModel previousModel = request.getPreviousResourceState();
        return ObjectUtils.notEqual(desiredModel.getDBParameterGroupName(), previousModel.getDBParameterGroupName()) &&
                ObjectUtils.notEqual(desiredModel.getEngineVersion(), previousModel.getEngineVersion()) &&
                BooleanUtils.isTrue(request.getRollback());
    }

    private ProgressEvent<ResourceModel, CallbackContext> setParameterGroupName(
            final AmazonWebServicesClientProxy proxy,
            final ProxyClient<RdsClient> rdsProxyClient,
            ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        final String dbParameterGroupName = progress.getResourceModel().getDBParameterGroupName();

        if (StringUtils.isNullOrEmpty(dbParameterGroupName)) {
            return progress;
        }

        final String engine = progress.getResourceModel().getEngine();
        final String engineVersion = progress.getResourceModel().getEngineVersion();

        DescribeDbParameterGroupsResponse response = rdsProxyClient.injectCredentialsAndInvokeV2(
                Translator.describeDbParameterGroupsRequest(dbParameterGroupName),
                rdsProxyClient.client()::describeDBParameterGroups
        );

        final Optional<DBParameterGroup> maybeDbParameterGroup = response.dbParameterGroups().stream().findFirst();

        if (!maybeDbParameterGroup.isPresent()) {
            return progress;
        }

        final String dbParameterGroupFamily = maybeDbParameterGroup.get().dbParameterGroupFamily();
        final DescribeDbEngineVersionsResponse describeDbEngineVersionsResponse = rdsProxyClient.injectCredentialsAndInvokeV2(
                Translator.describeDbEngineVersionsRequest(dbParameterGroupFamily, engine, engineVersion),
                rdsProxyClient.client()::describeDBEngineVersions
        );

        if (CollectionUtils.isNullOrEmpty(describeDbEngineVersionsResponse.dbEngineVersions())) {
            progress.getResourceModel().setDBParameterGroupName(null);
        } else {
            progress.getResourceModel().setDBParameterGroupName(dbParameterGroupName);
        }

        return progress;
    }

    private boolean shouldSetDefaultVpcId(final ResourceHandlerRequest<ResourceModel> request) {
        return CollectionUtils.isNullOrEmpty(request.getDesiredResourceState().getVPCSecurityGroups());
    }

    private ProgressEvent<ResourceModel, CallbackContext> setDefaultVpcId(
            final AmazonWebServicesClientProxy proxy,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProxyClient<Ec2Client> ec2ProxyClient,
            ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        final Optional<DBInstance> maybeDbInstance = fetchDBInstance(rdsProxyClient, progress.getResourceModel());
        if (!maybeDbInstance.isPresent()) {
            return ProgressEvent.defaultFailureHandler(
                    DbInstanceNotFoundException.builder().build(),
                    HandlerErrorCode.NotFound
            );
        }
        final String vpcId = maybeDbInstance.get().dbSubnetGroup().vpcId();

        final Optional<SecurityGroup> maybeSecurityGroup = fetchSecurityGroup(ec2ProxyClient, vpcId, "default");
        if (maybeSecurityGroup.isPresent()) {
            final String groupId = maybeSecurityGroup.get().groupId();
            if (StringUtils.hasValue(groupId)) {
                progress.getResourceModel().setDBSecurityGroups(Collections.singletonList(groupId));
            }
        }
        return progress;
    }

    private ProgressEvent<ResourceModel, CallbackContext> updateTags(
            final AmazonWebServicesClientProxy proxy,
            final ProxyClient<RdsClient> rdsProxyClient,
            ProgressEvent<ResourceModel, CallbackContext> progress,
            final Collection<Tag> previousTags,
            final Collection<Tag> desiredTags
    ) {
        return proxy.initiate("rds::describe-db-instance", rdsProxyClient, progress.getResourceModel(), progress.getCallbackContext())
                .translateToServiceRequest(Translator::describeDbInstancesRequest)
                .makeServiceCall((describeRequest, proxyInvocation) -> proxyInvocation.injectCredentialsAndInvokeV2(
                        describeRequest,
                        proxyInvocation.client()::describeDBInstances
                ))
                .handleError((request, exception, client, model, context) -> handleException(
                        ProgressEvent.progress(model, context),
                        exception
                ))
                .done((describeRequest, describeResponse, proxyInvocation, model, context) -> {
                    final DBInstance dbInstance = describeResponse.dbInstances().get(0);

                    final Set<Tag> tagsToAdd = new HashSet<>(desiredTags);
                    final Set<Tag> tagsToRemove = new HashSet<>(previousTags);

                    tagsToAdd.removeAll(previousTags);
                    tagsToRemove.removeAll(desiredTags);

                    if (!CollectionUtils.isNullOrEmpty(tagsToAdd) || !CollectionUtils.isNullOrEmpty(tagsToRemove)) {
                        final String arn = dbInstance.dbInstanceArn();
                        removeOldTags(rdsProxyClient, arn, tagsToRemove);
                        addNewTags(rdsProxyClient, arn, tagsToAdd);
                    }

                    return ProgressEvent.progress(model, context);
                });
    }
}
