using Volo.Abp.Modularity;

namespace Acme.Learn;

[DependsOn(
    typeof(LearnDomainModule),
    typeof(LearnTestBaseModule)
)]
public class LearnDomainTestModule : AbpModule
{

}
