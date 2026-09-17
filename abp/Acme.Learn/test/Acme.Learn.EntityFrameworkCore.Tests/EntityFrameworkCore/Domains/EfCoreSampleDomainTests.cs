using Acme.Learn.Samples;
using Xunit;

namespace Acme.Learn.EntityFrameworkCore.Domains;

[Collection(LearnTestConsts.CollectionDefinitionName)]
public class EfCoreSampleDomainTests : SampleDomainTests<LearnEntityFrameworkCoreTestModule>
{

}
