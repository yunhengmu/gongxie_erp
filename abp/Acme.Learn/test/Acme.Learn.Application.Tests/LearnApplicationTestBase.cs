using Volo.Abp.Modularity;

namespace Acme.Learn;

public abstract class LearnApplicationTestBase<TStartupModule> : LearnTestBase<TStartupModule>
    where TStartupModule : IAbpModule
{

}
