using Acme.Learn.EntityFrameworkCore;
using Volo.Abp.Autofac;
using Volo.Abp.Modularity;

namespace Acme.Learn.DbMigrator;

[DependsOn(
    typeof(AbpAutofacModule),
    typeof(LearnEntityFrameworkCoreModule),
    typeof(LearnApplicationContractsModule)
)]
public class LearnDbMigratorModule : AbpModule
{
}
