using Xunit;

namespace Acme.Learn.EntityFrameworkCore;

[CollectionDefinition(LearnTestConsts.CollectionDefinitionName)]
public class LearnEntityFrameworkCoreCollection : ICollectionFixture<LearnEntityFrameworkCoreFixture>
{

}
