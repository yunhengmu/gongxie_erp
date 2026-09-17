using System.Threading.Tasks;
using Volo.Abp.DependencyInjection;

namespace Acme.Learn.Data;

/* This is used if database provider does't define
 * ILearnDbSchemaMigrator implementation.
 */
public class NullLearnDbSchemaMigrator : ILearnDbSchemaMigrator, ITransientDependency
{
    public Task MigrateAsync()
    {
        return Task.CompletedTask;
    }
}
