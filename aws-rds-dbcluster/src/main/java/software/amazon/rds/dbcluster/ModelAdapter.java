package software.amazon.rds.dbcluster;

import com.google.common.collect.Lists;
import java.util.List;

public class ModelAdapter {
    protected static final int DEFAULT_PORT= 3306;
    private static final int DEFAULT_BACKUP_RETENTION_PERIOD = 1;
    private static final String SERVERLESS_ENGINE_MODE = "serverless";
    private static final String DEFAULT_DB_CLUSTER_PARAMETER_GROUP_NAME = "default.aurora5.6";
    public static ResourceModel setDefaults(final ResourceModel resourceModel) {

        final Integer port = resourceModel.getPort();
        final String dBClusterParameterGroupName = resourceModel.getDBClusterParameterGroupName();
        final Integer backupRetentionPeriod = resourceModel.getBackupRetentionPeriod();
        final List<DBClusterRole> associatedRoles = resourceModel.getAssociatedRoles();
        final ScalingConfiguration scalingConfiguration = resourceModel.getScalingConfiguration();

        resourceModel.setBackupRetentionPeriod(backupRetentionPeriod==null ? DEFAULT_BACKUP_RETENTION_PERIOD : backupRetentionPeriod);
        resourceModel.setAssociatedRoles(associatedRoles == null ? Lists.newArrayList() : associatedRoles);

        if (resourceModel.getEngineMode() == null || !resourceModel.getEngineMode().equalsIgnoreCase(SERVERLESS_ENGINE_MODE)) { // not serverless
            resourceModel.setPort(port == null ? DEFAULT_PORT : port);
            resourceModel.setDBClusterParameterGroupName(dBClusterParameterGroupName==null ? DEFAULT_DB_CLUSTER_PARAMETER_GROUP_NAME : dBClusterParameterGroupName);

        } else { // serverless
            resourceModel.setScalingConfiguration(scalingConfiguration == null ? ScalingConfiguration.builder().build() : scalingConfiguration);
        }

        return resourceModel;
    }
}
