package software.amazon.rds.dbinstance;

import software.amazon.cloudformation.proxy.StdCallbackContext;

@lombok.Getter
@lombok.Setter
@lombok.ToString
@lombok.EqualsAndHashCode(callSuper = true)
public class CallbackContext extends StdCallbackContext {
    private boolean created;
    private boolean deleted;
    private boolean updatedRoles;
    private boolean updated;
    private boolean rebooted;
}
