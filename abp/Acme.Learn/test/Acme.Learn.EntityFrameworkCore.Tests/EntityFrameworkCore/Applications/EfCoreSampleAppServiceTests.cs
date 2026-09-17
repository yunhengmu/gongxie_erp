using Acme.Learn.Samples;
using Xunit;

namespace Acme.Learn.EntityFrameworkCore.Applications;

[Collection(LearnTestConsts.CollectionDefinitionName)]
public class EfCoreSampleAppServiceTests : SampleAppServiceTests<LearnEntityFrameworkCoreTestModule>
{

}
