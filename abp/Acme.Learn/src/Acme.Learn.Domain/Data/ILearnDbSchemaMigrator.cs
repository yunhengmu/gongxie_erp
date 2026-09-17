using System.Threading.Tasks;

namespace Acme.Learn.Data;

public interface ILearnDbSchemaMigrator
{
    Task MigrateAsync();
}
