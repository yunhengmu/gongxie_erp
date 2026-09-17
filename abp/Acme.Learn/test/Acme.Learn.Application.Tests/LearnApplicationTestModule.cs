using Volo.Abp.Modularity;

namespace Acme.Learn;

[DependsOn(
    typeof(LearnApplicationModule),
    typeof(LearnDomainTestModule)
)]
public class LearnApplicationTestModule : AbpModule
{

}
