using System;
using Volo.Abp.Application.Dtos;
using Volo.Abp.Application.Services;

namespace Acme.Learn.Wms.Warehouse;

public class GetWarehousesInput : PagedAndSortedResultRequestDto
{
    public string? Filter { get; set; }
}

public interface IWarehouseAppService
    : ICrudAppService<WarehouseDto, Guid, GetWarehousesInput, CreateUpdateWarehouseDto>
{
}
