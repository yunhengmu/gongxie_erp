using System;
using Volo.Abp.Application.Dtos;

namespace Acme.Learn.Wms.Warehouse;

public class WarehouseDto : EntityDto<Guid>
{
    public string Code { get; set; } = string.Empty;
    public string Name { get; set; } = string.Empty;
    public bool IsActive { get; set; }
}
