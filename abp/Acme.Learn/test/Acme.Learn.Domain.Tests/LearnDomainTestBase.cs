using Volo.Abp.Modularity;

namespace Acme.Learn;

/* Inherit from this class for your domain layer tests. */
public abstract class LearnDomainTestBase<TStartupModule> : LearnTestBase<TStartupModule>
    where TStartupModule : IAbpModule
{

}
