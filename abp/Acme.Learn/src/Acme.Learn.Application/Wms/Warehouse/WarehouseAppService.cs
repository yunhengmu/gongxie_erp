using System;
using System.Threading.Tasks;
using Volo.Abp.Application.Services;
using Volo.Abp.Domain.Repositories;
using Acme.Learn.Permissions;

namespace Acme.Learn.Wms.Warehouse;

public class WarehouseAppService :
    CrudAppService<Warehouse, WarehouseDto, Guid, GetWarehousesInput, CreateUpdateWarehouseDto>,
    IWarehouseAppService
{
    public WarehouseAppService(IRepository<Warehouse, Guid> repository)
        : base(repository)
    {
        GetPolicyName      = LearnPermissions.Warehouses.Default;
        GetListPolicyName  = LearnPermissions.Warehouses.Default;
        CreatePolicyName   = LearnPermissions.Warehouses.Create;
        UpdatePolicyName   = LearnPermissions.Warehouses.Update;
        DeletePolicyName   = LearnPermissions.Warehouses.Delete;
    }

    // 【新建】时调用：DTO → 新实体
    protected override Task<Warehouse> MapToEntityAsync(CreateUpdateWarehouseDto input)
    {
        var warehouse = new Warehouse(GuidGenerator.Create(), input.Code, input.Name);

        // 关键：IsActive 的 setter 是 private 的，外层改不了，
        // 只能通过聚合根自己暴露的业务方法改 —— 这正是 DDD 想要的约束。
        // 构造函数里给的是默认值 false（「新建未启用」），这里按 DTO 决定要不要启用。
        if (input.IsActive)
        {
            warehouse.Activate();
        }

        return Task.FromResult(warehouse);
    }

    // 【更新】时调用：DTO → 已存在的实体，只做增量修改
    protected override Task MapToEntityAsync(CreateUpdateWarehouseDto input, Warehouse entity)
    {
        // 注意这里没有改 Code —— 仓库编码是业务主键，建了就不许改。
        // DTO 里有 Code 字段只是因为新建时要用，更新时会被忽略。
        entity.Rename(input.Name);

        if (input.IsActive)
        {
            entity.Activate();
        }
        else
        {
            entity.Deactivate();
        }

        return Task.CompletedTask;
    }
}
