using Acme.Learn.Localization;
using Volo.Abp.Authorization.Permissions;
using Volo.Abp.Localization;
using Volo.Abp.MultiTenancy;

namespace Acme.Learn.Permissions;

public class LearnPermissionDefinitionProvider : PermissionDefinitionProvider
{
    public override void Define(IPermissionDefinitionContext context)
    {
        var myGroup = context.AddGroup(LearnPermissions.GroupName);

        var warehouses = myGroup.AddPermission(LearnPermissions.Warehouses.Default, L("Permission:Warehouses"));
        warehouses.AddChild(LearnPermissions.Warehouses.Create, L("Permission:Warehouses.Create"));
        warehouses.AddChild(LearnPermissions.Warehouses.Update, L("Permission:Warehouses.Update"));
        warehouses.AddChild(LearnPermissions.Warehouses.Delete, L("Permission:Warehouses.Delete"));

        //Define your own permissions here. Example:
        //myGroup.AddPermission(LearnPermissions.MyPermission1, L("Permission:MyPermission1"));
    }

    private static LocalizableString L(string name)
    {
        return LocalizableString.Create<LearnResource>(name);
    }
}
