namespace Acme.Learn.Permissions;

public static class LearnPermissions
{
    public const string GroupName = "Learn";

    public static class Warehouses
    {
        public const string Default = GroupName + ".Warehouses";
        public const string Create  = Default + ".Create";
        public const string Update  = Default + ".Update";
        public const string Delete  = Default + ".Delete";
    }
}
