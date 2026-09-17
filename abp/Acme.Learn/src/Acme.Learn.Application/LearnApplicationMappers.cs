using Acme.Learn.Wms.Warehouse;
using Riok.Mapperly.Abstractions;
using Volo.Abp.Mapperly;

namespace Acme.Learn;

[Mapper]
public partial class WarehouseToDtoMapper : MapperBase<Warehouse, WarehouseDto>
{
    public override partial WarehouseDto Map(Warehouse source);
    public override partial void Map(Warehouse source, WarehouseDto destination);
}
